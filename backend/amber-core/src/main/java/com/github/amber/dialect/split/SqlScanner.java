package com.github.amber.dialect.split;

import com.github.amber.dialect.split.ScanToken.Type;

/**
 * A small, forgiving lexer used only to find statement boundaries. It knows strings, quoted
 * identifiers, comments and dialect quoting forms, so a {@code ;}, {@code /} or {@code GO} inside
 * them is never mistaken for a terminator. It never fails: malformed input yields
 * {@link Type#UNTERMINATED} tokens, and the real ANTLR parser reports the error later.
 *
 * <p>Whitespace and comments are skipped. The scanner is positioned: splitters can jump over raw
 * lines (client commands, COPY data) with {@link #reset(int)}.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SqlScanner {

    private final String s;
    private final ScannerOptions options;
    private int pos;
    private int versionedDepth;
    private int versionedOpener = -1;

    public SqlScanner(String text, ScannerOptions options) {
        this.s = text;
        this.options = options;
    }

    public int position() {
        return pos;
    }

    public void reset(int position) {
        this.pos = position;
    }

    public String text() {
        return s;
    }

    /** Returns the next significant token, or {@code null} at end of input. */
    public ScanToken next() {
        skipTrivia();
        if (pos >= s.length()) {
            return null;
        }
        int start = pos;
        char c = s.charAt(pos);

        if (c == '\'') {
            return quoted(start, '\'', Type.STRING, options.backslashStrings());
        }
        if (c == '"') {
            return quoted(start, '"', Type.QUOTED_IDENTIFIER, options.backslashStrings());
        }
        if (c == '[' && options.bracketIdentifiers()) {
            return quoted(start, ']', Type.QUOTED_IDENTIFIER, false);
        }
        if (c == '`' && options.backtickIdentifiers()) {
            return quoted(start, '`', Type.QUOTED_IDENTIFIER, false);
        }
        if (c == '$' && options.dollarQuotes()) {
            ScanToken dollar = dollarQuoted(start);
            if (dollar != null) {
                return dollar;
            }
        }
        if (isWordStart(c)) {
            pos++;
            while (pos < s.length() && isWordPart(s.charAt(pos))) {
                pos++;
            }
            String word = s.substring(start, pos);
            if (pos < s.length() && s.charAt(pos) == '\'') {
                if (options.qQuotes() && (word.equalsIgnoreCase("q") || word.equalsIgnoreCase("nq"))) {
                    return qQuoted(start);
                }
                if (options.escapeStrings() && word.equalsIgnoreCase("e")) {
                    pos = start + 1;
                    ScanToken str = quoted(pos, '\'', Type.STRING, true);
                    return new ScanToken(str.type(), start, str.end(), s.substring(start, str.end()));
                }
            }
            return new ScanToken(Type.WORD, start, pos, word);
        }
        if (Character.isDigit(c) || (c == '.' && pos + 1 < s.length() && Character.isDigit(s.charAt(pos + 1)))) {
            pos++;
            while (pos < s.length()) {
                char d = s.charAt(pos);
                if (Character.isLetterOrDigit(d) || d == '.' || d == '_') {
                    pos++;
                } else if ((d == '+' || d == '-') && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')) {
                    pos++;
                } else {
                    break;
                }
            }
            return new ScanToken(Type.NUMBER, start, pos, s.substring(start, pos));
        }
        pos++;
        return new ScanToken(Type.SYMBOL, start, pos, String.valueOf(c));
    }

    /** Peeks the next significant token without consuming it. */
    public ScanToken peek() {
        int saved = pos;
        ScanToken t = next();
        pos = saved;
        return t;
    }

    // ----- line helpers -------------------------------------------------------------------

    /** Offset of the first char of the line containing {@code offset}. */
    public int lineStart(int offset) {
        int i = Math.min(offset, s.length());
        while (i > 0 && s.charAt(i - 1) != '\n' && s.charAt(i - 1) != '\r') {
            i--;
        }
        return i;
    }

    /** Offset of the line break ending the line containing {@code offset} (or end of input). */
    public int lineEnd(int offset) {
        int i = offset;
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    /** Offset just after the line break ending the line containing {@code offset}. */
    public int nextLineStart(int offset) {
        int i = lineEnd(offset);
        if (i < s.length() && s.charAt(i) == '\r') {
            i++;
        }
        if (i < s.length() && s.charAt(i) == '\n') {
            i++;
        }
        return i;
    }

    /**
     * True when only whitespace precedes {@code token} on its line. Walks back over the blanks only, so
     * it costs nothing on very long lines.
     */
    public boolean startsLine(ScanToken token) {
        int i = Math.min(token.start(), s.length());
        while (i > 0) {
            char c = s.charAt(i - 1);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
            i--;
        }
        return true;
    }

    /** True when only whitespace or a line comment follows {@code offset} on its line. */
    public boolean restOfLineEmpty(int offset) {
        int i = offset;
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r' && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i >= s.length() || s.charAt(i) == '\n' || s.charAt(i) == '\r'
                || (s.startsWith("--", i) && isDashComment(i)) || (options.hashComments() && s.charAt(i) == '#');
    }

    /** True when {@code token} is the only significant token on its line. */
    public boolean aloneOnLine(ScanToken token) {
        return startsLine(token) && restOfLineEmpty(token.end());
    }

    // ----- lexing -------------------------------------------------------------------------

    /**
     * Start of the MySQL versioned-comment opener ({@code /*!40101}) skipped right before the token
     * {@link #next()} returned last, or -1; a unit starting at that token starts at the opener.
     */
    public int versionedOpenerBefore() {
        return versionedOpener;
    }

    private void skipTrivia() {
        versionedOpener = -1;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && s.startsWith("--", pos) && isDashComment(pos)) {
                pos = lineEnd(pos);
            } else if (c == '#' && options.hashComments()) {
                pos = lineEnd(pos);
            } else if (c == '/' && options.slashComments() && s.startsWith("//", pos)) {
                pos = lineEnd(pos);
            } else if (c == '/' && options.versionedComments() && s.startsWith("/*!", pos)) {
                if (versionedOpener < 0) {
                    versionedOpener = pos;
                }
                pos += 3;                             // MySQL versioned comment: its content is SQL
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
                versionedDepth++;
            } else if (c == '*' && versionedDepth > 0 && s.startsWith("*/", pos)) {
                pos += 2;
                versionedDepth--;
            } else if (c == '/' && s.startsWith("/*", pos)) {
                skipBlockComment();
            } else {
                return;
            }
        }
    }

    private boolean isDashComment(int at) {
        if (!options.dashCommentNeedsSpace()) {
            return true;
        }
        return at + 2 >= s.length() || Character.isWhitespace(s.charAt(at + 2)) || Character.isISOControl(s.charAt(at + 2));
    }

    private void skipBlockComment() {
        int depth = 0;
        while (pos < s.length()) {
            if (s.startsWith("/*", pos) && (depth == 0 || options.nestedBlockComments())) {
                depth++;
                pos += 2;
            } else if (s.startsWith("*/", pos)) {
                depth--;
                pos += 2;
                if (depth == 0) {
                    return;
                }
            } else {
                pos++;
            }
        }
    }

    private ScanToken quoted(int start, char close, Type type, boolean backslashEscapes) {
        pos = start + 1;
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
                return new ScanToken(type, start, pos, s.substring(start, pos));
            }
        }
        pos = s.length();
        return new ScanToken(Type.UNTERMINATED, start, pos, s.substring(start));
    }

    /** {@code q'[...]'}, {@code q'{...}'}, {@code q'<...>'}, {@code q'(...)'} or {@code q'X...X'}. */
    private ScanToken qQuoted(int start) {
        int open = pos + 1;
        if (open >= s.length()) {
            pos = s.length();
            return new ScanToken(Type.UNTERMINATED, start, pos, s.substring(start));
        }
        int delimiter = s.codePointAt(open);                // may be a supplementary character (emoji)
        int close = switch (delimiter) {
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            case '(' -> ')';
            default -> delimiter;
        };
        String end = new String(Character.toChars(close)) + "'";
        int at = s.indexOf(end, open + Character.charCount(delimiter));
        if (at >= 0) {
            pos = at + end.length();
            return new ScanToken(Type.STRING, start, pos, s.substring(start, pos));
        }
        pos = s.length();
        return new ScanToken(Type.UNTERMINATED, start, pos, s.substring(start));
    }

    /** {@code $$...$$} or {@code $tag$...$tag$}; returns null when '$' does not open a dollar quote. */
    private ScanToken dollarQuoted(int start) {
        int i = start + 1;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            if (i == start + 1 && Character.isDigit(s.charAt(i))) {
                return null;                      // $1 positional parameter
            }
            i++;
        }
        if (i >= s.length() || s.charAt(i) != '$') {
            return null;
        }
        String tag = s.substring(start, i + 1);
        int close = s.indexOf(tag, i + 1);
        if (close < 0) {
            pos = s.length();
            return new ScanToken(Type.UNTERMINATED, start, pos, s.substring(start));
        }
        pos = close + tag.length();
        return new ScanToken(Type.STRING, start, pos, s.substring(start, pos));
    }

    private boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || (options.sigilWords() && (c == '@' || c == '#'));
    }

    private boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || (c == '#' && options.hashInNames())
                || (c == '@' && options.sigilWords());
    }
}
