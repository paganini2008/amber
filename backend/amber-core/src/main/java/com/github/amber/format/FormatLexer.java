package com.github.amber.format;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.format.FormatToken.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer for the formatter. Unlike {@link com.github.amber.dialect.split.SqlScanner} it keeps comments
 * as tokens and records the whitespace before every token, because the formatter must reproduce
 * comments exactly and needs to know where they sat. It mirrors the scanner's dialect rules (nested
 * block comments, backslash escapes, bracket and backtick identifiers, {@code q'[…]'}, dollar quotes,
 * {@code #} comments, {@code --} needing a space in MySQL) and adds the forms whose inner spacing must
 * not change: SQL*Plus substitution variables ({@code &name.}), MySQL user variables ({@code @v},
 * {@code @@global}) and multi-character operators.
 *
 * <p>The lexer never fails. Text that runs to the end of input inside a string, quoted identifier or
 * block comment becomes an {@link Kind#UNTERMINATED} token; the formatter then leaves the unit alone.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class FormatLexer {

    /** Multi-character operators of all dialects except PostgreSQL, longest first. */
    private static final String[] OPERATORS = {
            "<=>", "->>", "::", ":=", "=>", "<=", ">=", "<>", "!=", "!<", "!>", "||", "&&", "**", "*=", "=*",
            "+=", "-=", "/=", "%=", "&=", "|=", "^=", "->", "<<", ">>", ".."};

    /** Characters PostgreSQL combines into operators. */
    private static final String PG_OPERATOR_CHARS = "+-*/<>=~!@#%^&|`?";

    private final String s;
    private final ScannerOptions o;
    private final Dialect dialect;
    private final boolean postgres;
    /** '#' may continue a name (Oracle, SQL Server, Db2, H2); elsewhere it is an operator or a comment. */
    private final boolean hashInWords;
    private int pos;

    private FormatLexer(String text, Dialect dialect) {
        this.s = text;
        this.dialect = dialect;
        this.o = ScannerOptions.forDialect(dialect);
        this.postgres = dialect == Dialect.POSTGRESQL;
        this.hashInWords = dialect == Dialect.ORACLE || dialect == Dialect.SQLSERVER || dialect == Dialect.DB2
                || dialect == Dialect.H2;
    }

    /**
     * Tokenizes {@code text}. When {@code terminator} is not null and ends the text, it becomes one
     * {@link Kind#TERMINATOR} token.
     */
    static List<FormatToken> lex(String text, Dialect dialect, String terminator) {
        int bodyEnd = text.length();
        if (terminator != null && !terminator.isEmpty() && text.endsWith(terminator)) {
            bodyEnd -= terminator.length();
        }
        FormatLexer lexer = new FormatLexer(text.substring(0, bodyEnd), dialect);
        List<FormatToken> tokens = lexer.run();
        if (bodyEnd < text.length()) {
            int i = bodyEnd;
            int nl = 0;
            int j = i;
            while (j > 0 && Character.isWhitespace(text.charAt(j - 1))) {
                j--;
            }
            nl = countNewlines(text, j, i);
            tokens.add(new FormatToken(Kind.TERMINATOR, terminator, bodyEnd, text.length(), nl, j < i));
        }
        return tokens;
    }

    static boolean hasUnterminated(List<FormatToken> tokens) {
        for (FormatToken t : tokens) {
            if (t.kind == Kind.UNTERMINATED) {
                return true;
            }
        }
        return false;
    }

    private static int countNewlines(String text, int from, int to) {
        int nl = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                nl++;
            }
        }
        return nl;
    }

    private List<FormatToken> run() {
        List<FormatToken> out = new ArrayList<>();
        int newlines = 0;
        boolean space = false;
        int n = s.length();
        while (pos < n) {
            char c = s.charAt(pos);
            if (Character.isWhitespace(c)) {
                if (c == '\n' || (c == '\r' && (pos + 1 >= n || s.charAt(pos + 1) != '\n'))) {
                    newlines++;
                }
                space = true;
                pos++;
                continue;
            }
            int start = pos;
            Kind kind = token(c);
            out.add(new FormatToken(kind, s.substring(start, pos), start, pos, newlines, space));
            newlines = 0;
            space = false;
        }
        return out;
    }

    private Kind token(char c) {
        if (c == '-' && s.startsWith("--", pos) && isDashComment(pos)) {
            pos = lineEnd(pos);
            return Kind.LINE_COMMENT;
        }
        if (c == '#' && o.hashComments()) {
            pos = lineEnd(pos);
            return Kind.LINE_COMMENT;
        }
        if (c == '/' && o.slashComments() && s.startsWith("//", pos)) {
            pos = lineEnd(pos);
            return Kind.LINE_COMMENT;
        }
        if (c == '/' && s.startsWith("/*", pos)) {
            return blockComment();
        }
        if (c == '\'') {
            return quoted('\'', Kind.STRING, o.backslashStrings());
        }
        if (c == '"') {
            return quoted('"', Kind.QUOTED, o.backslashStrings());
        }
        if (c == '[' && o.bracketIdentifiers()) {
            return quoted(']', Kind.QUOTED, false);
        }
        if (c == '`' && o.backtickIdentifiers()) {
            return quoted('`', Kind.QUOTED, false);
        }
        if (c == '$') {
            Kind k = dollar();
            if (k != null) {
                return k;
            }
        }
        if (c == '&' && dialect == Dialect.ORACLE && substitutionVariable()) {
            return Kind.WORD;
        }
        if (c == '@' && o.versionedComments()) {
            Kind k = mysqlVariable();
            if (k != null) {
                return k;
            }
        }
        if (isWordStart(c)) {
            return word();
        }
        if (Character.isDigit(c) || (c == '.' && pos + 1 < s.length() && Character.isDigit(s.charAt(pos + 1))
                && (pos == 0 || s.charAt(pos - 1) != '.'))) {
            return number();
        }
        return operator(c);
    }

    // ----- comments ------------------------------------------------------------------------

    private boolean isDashComment(int at) {
        if (!o.dashCommentNeedsSpace()) {
            return true;
        }
        return at + 2 >= s.length() || Character.isWhitespace(s.charAt(at + 2)) || Character.isISOControl(s.charAt(at + 2));
    }

    private int lineEnd(int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    private Kind blockComment() {
        boolean versioned = o.versionedComments() && s.startsWith("/*!", pos);
        boolean nested = o.nestedBlockComments() && !versioned;
        int depth = 0;
        while (pos < s.length()) {
            if (s.startsWith("/*", pos) && (depth == 0 || nested)) {
                depth++;
                pos += 2;
            } else if (s.startsWith("*/", pos)) {
                depth--;
                pos += 2;
                if (depth == 0) {
                    return Kind.BLOCK_COMMENT;
                }
            } else {
                pos++;
            }
        }
        return Kind.UNTERMINATED;
    }

    // ----- quoted forms --------------------------------------------------------------------

    private Kind quoted(char close, Kind kind, boolean backslashEscapes) {
        pos++;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (backslashEscapes && c == '\\') {
                pos += 2;
                continue;
            }
            pos++;
            if (c == close) {
                if (pos < s.length() && s.charAt(pos) == close) {
                    pos++;
                    continue;
                }
                return kind;
            }
        }
        pos = s.length();
        return Kind.UNTERMINATED;
    }

    private Kind qQuoted() {
        int open = pos + 1;                       // pos is at the quote after q / nq
        if (open >= s.length()) {
            pos = s.length();
            return Kind.UNTERMINATED;
        }
        int delimiter = s.codePointAt(open);
        int close = switch (delimiter) {
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            case '(' -> ')';
            default -> delimiter;
        };
        String closing = new StringBuilder().appendCodePoint(close).append('\'').toString();
        int found = s.indexOf(closing, open + Character.charCount(delimiter));
        if (found >= 0) {
            pos = found + closing.length();
            return Kind.STRING;
        }
        pos = s.length();
        return Kind.UNTERMINATED;
    }

    /** Dollar-quoted string, PostgreSQL positional parameter, or a {@code $name} word; null for a lone '$'. */
    private Kind dollar() {
        int start = pos;
        int i = start + 1;
        if (o.dollarQuotes()) {
            if (i < s.length() && Character.isDigit(s.charAt(i))) {
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                pos = i;
                return Kind.WORD;                 // $1
            }
            int j = i;
            while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                j++;
            }
            if (j < s.length() && s.charAt(j) == '$') {
                String tag = s.substring(start, j + 1);
                int close = s.indexOf(tag, j + 1);
                if (close < 0) {
                    pos = s.length();
                    return Kind.UNTERMINATED;
                }
                pos = close + tag.length();
                return Kind.STRING;
            }
        }
        if (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '_')) {
            while (i < s.length() && isWordPart(s.charAt(i))) {
                i++;
            }
            pos = i;
            return Kind.WORD;                     // $action, $IF, $PARTITION
        }
        return null;
    }

    /** SQL*Plus {@code &name}, {@code &&name}, {@code &1}, with an optional terminating '.'. */
    private boolean substitutionVariable() {
        int i = pos;
        while (i < s.length() && s.charAt(i) == '&') {
            i++;
        }
        if (i - pos > 2) {
            return false;
        }
        int nameStart = i;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'
                || s.charAt(i) == '$' || s.charAt(i) == '#')) {
            i++;
        }
        if (i == nameStart) {
            return false;
        }
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
        }
        pos = i;
        return true;
    }

    /** MySQL {@code @var}, {@code @@var}, {@code @'quoted'}; null when '@' is not a variable. */
    private Kind mysqlVariable() {
        int i = pos + 1;
        if (i < s.length() && s.charAt(i) == '@') {
            i++;
        }
        if (i >= s.length()) {
            return null;
        }
        char c = s.charAt(i);
        if (c == '\'' || c == '"' || c == '`') {
            pos = i;
            Kind k = quoted(c, Kind.WORD, c != '`');
            return k == Kind.UNTERMINATED ? k : Kind.WORD;
        }
        if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) {
                i++;
            }
            pos = i;
            return Kind.WORD;
        }
        return null;
    }

    // ----- words, numbers, operators -------------------------------------------------------

    private Kind word() {
        int start = pos;
        pos++;
        while (pos < s.length() && isWordPart(s.charAt(pos))) {
            pos++;
        }
        if (pos < s.length() && s.charAt(pos) == '\'') {
            String w = s.substring(start, pos);
            if (o.qQuotes() && (w.equalsIgnoreCase("q") || w.equalsIgnoreCase("nq"))) {
                return qQuoted();
            }
            if (o.escapeStrings() && w.equalsIgnoreCase("e")) {
                return quoted('\'', Kind.STRING, true);
            }
        }
        return Kind.WORD;
    }

    private Kind number() {
        pos++;
        while (pos < s.length()) {
            char d = s.charAt(pos);
            if (d == '.' && pos + 1 < s.length() && s.charAt(pos + 1) == '.') {
                break;                            // 1..10 range
            }
            if (Character.isLetterOrDigit(d) || d == '.' || d == '_') {
                pos++;
            } else if ((d == '+' || d == '-') && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')) {
                pos++;
            } else {
                break;
            }
        }
        return Kind.NUMBER;
    }

    private Kind operator(char c) {
        if (postgres && PG_OPERATOR_CHARS.indexOf(c) >= 0) {
            int end = pos;
            while (end < s.length() && PG_OPERATOR_CHARS.indexOf(s.charAt(end)) >= 0) {
                if (end > pos && (s.startsWith("--", end) || s.startsWith("/*", end))) {
                    break;
                }
                end++;
            }
            String op = s.substring(pos, end);
            if (op.length() > 1 && !containsAny(op, "~!@#%^&|`?")) {
                while (op.length() > 1 && (op.endsWith("+") || op.endsWith("-"))) {
                    op = op.substring(0, op.length() - 1);
                }
            }
            pos += op.length();
            return Kind.SYMBOL;
        }
        if (c == ':' && (s.startsWith("::", pos) || s.startsWith(":=", pos))) {
            pos += 2;
            return Kind.SYMBOL;
        }
        if (!postgres) {
            for (String op : OPERATORS) {
                if (s.startsWith(op, pos)) {
                    pos += op.length();
                    return Kind.SYMBOL;
                }
            }
        } else if (s.startsWith("..", pos)) {
            pos += 2;
            return Kind.SYMBOL;
        }
        pos += Character.charCount(s.codePointAt(pos));
        return Kind.SYMBOL;
    }

    private static boolean containsAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || (o.sigilWords() && (c == '@' || c == '#'));
    }

    private boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || (c == '#' && hashInWords)
                || (c == '@' && o.sigilWords());
    }
}
