package com.github.amber.ai;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The business logic of SQL as two sets that a rewrite must keep: its conditions and its literal values.
 * They come from the tokens, so every dialect is covered, and they ignore what a refactoring may change:
 * table aliases and qualifiers, layout, keyword case, the order and the place of conditions (WHERE or ON),
 * and how often the same condition is written. A changed comparison, operator, column or value shows up.
 *
 * <p>A condition is {@code left op right} with the operands reduced to column names (last part, upper
 * case), literals or whole parenthesized expressions; {@code =} and {@code <>} are order-free and
 * {@code >} is written as {@code <} with the operands swapped; a subquery operand stands for itself, since its
 * own conditions are collected too. Always-true {@code n = n} is left out of both sets, so removing the legacy
 * {@code WHERE 1=1} changes nothing.
 *
 * @Author: Fred Feng
 * @Date: 11/11/2026
 * @Version 1.0.0
 */
final class LogicFingerprint {

    final Set<String> conditions = new TreeSet<>();
    final Set<String> literals = new TreeSet<>();
    private final List<ScanToken> t = new ArrayList<>();

    private LogicFingerprint(Dialect dialect, String text) {
        SqlScanner sc = new SqlScanner(text, ScannerOptions.forDialect(dialect));
        for (ScanToken tok = sc.next(); tok != null; tok = sc.next()) {
            t.add(tok);
        }
        Set<Integer> tautology = new TreeSet<>();
        for (int i = 0; i < t.size(); i++) {
            int[] op = operator(i);
            if (op == null) {
                continue;
            }
            String name = opText(i, op[0]);
            int[] left = before(i - 1);
            int[] right = name.equals("IS") || name.equals("IS NOT") ? new int[]{op[0], op[0]} : after(op[0]);
            if (left == null || right == null) {
                continue;
            }
            String l = norm(left[0], left[1]);
            String r = name.startsWith("IS") ? "NULL" : norm(right[0], right[1]);
            if (name.equals("=") && l.equals(r) && left[1] - left[0] == 0 && isNumber(left[0])) {
                tautology.add(left[0]);
                tautology.add(right[0]);
                continue;
            }
            if (name.equals("BETWEEN") || name.equals("NOT BETWEEN")) {
                int and = right[1] + 1;
                int[] upper = word(and, "AND") ? after(and + 1) : null;
                r = r + " AND " + (upper == null ? "?" : norm(upper[0], upper[1]));
            }
            conditions.add(canonical(l, name, r));
        }
        for (int i = 0; i < t.size(); i++) {
            ScanToken tok = t.get(i);
            if (!tautology.contains(i) && (tok.type() == ScanToken.Type.STRING
                    || (tok.type() == ScanToken.Type.NUMBER && !ordinal(i)))) {
                literals.add(literal(i));
            }
        }
    }

    static LogicFingerprint of(Dialect dialect, String text) {
        return new LogicFingerprint(dialect, text);
    }

    /** A number that is a position in ORDER BY / GROUP BY ({@code ORDER BY 1, 2}), not a value. */
    private boolean ordinal(int k) {
        if (!(sym(k + 1, ',') || sym(k + 1, ')') || sym(k + 1, ';') || k + 1 >= t.size() || word(k + 1, "ASC")
                || word(k + 1, "DESC") || word(k + 1, "NULLS") || word(k + 1, "LIMIT") || word(k + 1, "FETCH")
                || word(k + 1, "OFFSET") || word(k + 1, "HAVING") || word(k + 1, "UNION"))) {
            return false;
        }
        for (int j = k - 1; j >= 0; j--) {
            if (word(j, "BY")) {
                return word(j - 1, "ORDER") || word(j - 1, "GROUP");
            }
            if (sym(j, ',') || word(j, "ASC") || word(j, "DESC") || t.get(j).type() == ScanToken.Type.NUMBER) {
                continue;
            }
            return false;
        }
        return false;
    }

    // ----- operators ---------------------------------------------------------------------------------

    /** {end of the operator (exclusive)} when an operator starts at {@code i}, else null. */
    private int[] operator(int i) {
        if (sym(i, '=') && !sym(i - 1, '<') && !sym(i - 1, '>') && !sym(i - 1, '!') && !sym(i - 1, ':')
                && !sym(i + 1, '>')) {
            return new int[]{i + 1};
        }
        if (sym(i, '<') || sym(i, '>')) {
            if (sym(i - 1, '<') || sym(i - 1, '>')) {
                return null;                        // second half of <> / >= handled from the first
            }
            return new int[]{sym(i + 1, '=') || (sym(i, '<') && sym(i + 1, '>')) ? i + 2 : i + 1};
        }
        if (sym(i, '!') && sym(i + 1, '=')) {
            return new int[]{i + 2};
        }
        if (word(i, "LIKE") || word(i, "BETWEEN") || (word(i, "IN") && sym(i + 1, '('))) {
            return new int[]{i + 1};
        }
        if (word(i, "IS") && (word(i + 1, "NULL") || (word(i + 1, "NOT") && word(i + 2, "NULL")))) {
            return new int[]{word(i + 1, "NOT") ? i + 3 : i + 2};
        }
        return null;
    }

    private String opText(int i, int end) {
        if (word(i, "IS")) {
            return word(i + 1, "NOT") ? "IS NOT" : "IS";
        }
        StringBuilder sb = new StringBuilder();
        for (int k = i; k < end; k++) {
            sb.append(t.get(k).text().toUpperCase(Locale.ROOT));
        }
        String op = sb.toString().equals("!=") ? "<>" : sb.toString();
        return word(i - 1, "NOT") && (op.equals("LIKE") || op.equals("IN") || op.equals("BETWEEN")) ? "NOT " + op : op;
    }

    private static String canonical(String l, String op, String r) {
        switch (op) {
            case "=", "<>" -> {
                return l.compareTo(r) <= 0 ? l + " " + op + " " + r : r + " " + op + " " + l;
            }
            case ">" -> {
                return r + " < " + l;
            }
            case ">=" -> {
                return r + " <= " + l;
            }
            default -> {
                return l + " " + op + " " + r;
            }
        }
    }

    // ----- operands ----------------------------------------------------------------------------------

    /** Token range [from, to] of the operand ending at {@code i}, or null. */
    private int[] before(int i) {
        if (word(i, "NOT")) {
            i--;
        }
        if (i < 0) {
            return null;
        }
        if (sym(i, ')')) {
            int open = matchBack(i);
            if (open < 0) {
                return null;
            }
            int from = open;
            if (open > 0 && isName(open - 1)) {
                from = qualifiedStart(open - 1);
            }
            return new int[]{from, i};
        }
        if (isName(i)) {
            return new int[]{qualifiedStart(i), i};
        }
        if (isLiteral(i)) {
            int from = i;
            if (word(i - 1, "DATE") || word(i - 1, "TIMESTAMP") || word(i - 1, "INTERVAL")) {
                from = i - 1;
            }
            return new int[]{from, i};
        }
        return null;
    }

    /** Token range [from, to] of the operand starting at {@code i}, or null. */
    private int[] after(int i) {
        if (i >= t.size()) {
            return null;
        }
        if (sym(i, '-') || sym(i, '+')) {
            int[] r = after(i + 1);
            return r == null ? null : new int[]{i, r[1]};
        }
        if (sym(i, '(')) {
            int close = matchForward(i);
            return close < 0 ? null : new int[]{i, close};
        }
        if ((word(i, "ANY") || word(i, "SOME") || word(i, "ALL")) && sym(i + 1, '(')) {
            int close = matchForward(i + 1);
            return close < 0 ? null : new int[]{i, close};
        }
        if ((word(i, "DATE") || word(i, "TIMESTAMP") || word(i, "INTERVAL")) && isLiteral(i + 1)) {
            return new int[]{i, i + 1};
        }
        if (sym(i, '&') || sym(i, ':') || sym(i, '@')) {   // substitution and bind variables
            return isName(i + 1) ? new int[]{i, i + 1} : new int[]{i, i};
        }
        if (isLiteral(i)) {
            return new int[]{i, i};
        }
        if (isName(i)) {
            int end = i;
            while (sym(end + 1, '.') && isName(end + 2)) {
                end += 2;
            }
            if (sym(end + 1, '(')) {
                int close = matchForward(end + 1);
                return close < 0 ? null : new int[]{i, close};
            }
            return new int[]{i, end};
        }
        return null;
    }

    private int qualifiedStart(int i) {
        while (sym(i - 1, '.') && isName(i - 2)) {
            i -= 2;
        }
        return i;
    }

    /** An operand as text: qualifiers dropped, names in upper case, literals normalized. */
    private String norm(int from, int to) {
        if (sym(from, '(') && (word(from + 1, "SELECT") || word(from + 1, "WITH")) && matchForward(from) == to) {
            return "(SELECT …)";                  // a subquery: its own conditions are collected on their own
        }
        if ((word(from, "ANY") || word(from, "SOME") || word(from, "ALL")) && sym(from + 1, '(')
                && (word(from + 2, "SELECT") || word(from + 2, "WITH")) && matchForward(from + 1) == to) {
            return t.get(from).upper() + " (SELECT …)";
        }
        StringBuilder sb = new StringBuilder();
        for (int k = from; k <= to; k++) {
            if (isName(k) && sym(k + 1, '.')) {
                k++;                                // a qualifier and its dot
                continue;
            }
            ScanToken tok = t.get(k);
            String s = switch (tok.type()) {
                case STRING, NUMBER -> literal(k);
                case WORD -> tok.upper();
                case QUOTED_IDENTIFIER -> unquote(tok.text()).toUpperCase(Locale.ROOT);
                default -> tok.text();
            };
            if (!sb.isEmpty() && !s.equals(")") && !s.equals(",") && !sb.toString().endsWith("(")) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private String literal(int i) {
        ScanToken tok = t.get(i);
        if (tok.type() == ScanToken.Type.NUMBER) {
            try {
                return new BigDecimal(tok.text()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return tok.text();
            }
        }
        return tok.text();
    }

    // ----- tokens ------------------------------------------------------------------------------------

    private boolean sym(int i, char c) {
        return i >= 0 && i < t.size() && t.get(i).type() == ScanToken.Type.SYMBOL && t.get(i).text().equals(String.valueOf(c));
    }

    private boolean word(int i, String w) {
        return i >= 0 && i < t.size() && t.get(i).isWord(w);
    }

    private boolean isName(int i) {
        return i >= 0 && i < t.size() && (t.get(i).type() == ScanToken.Type.QUOTED_IDENTIFIER
                || (t.get(i).type() == ScanToken.Type.WORD && !RESERVED.contains(t.get(i).upper())));
    }

    private boolean isLiteral(int i) {
        return i >= 0 && i < t.size() && (t.get(i).type() == ScanToken.Type.STRING || t.get(i).type() == ScanToken.Type.NUMBER
                || word(i, "NULL") || word(i, "TRUE") || word(i, "FALSE"));
    }

    private boolean isNumber(int i) {
        return i >= 0 && i < t.size() && t.get(i).type() == ScanToken.Type.NUMBER;
    }

    private int matchBack(int close) {
        int depth = 0;
        for (int k = close; k >= 0; k--) {
            if (sym(k, ')')) {
                depth++;
            } else if (sym(k, '(') && --depth == 0) {
                return k;
            }
        }
        return -1;
    }

    private int matchForward(int open) {
        int depth = 0;
        for (int k = open; k < t.size(); k++) {
            if (sym(k, '(')) {
                depth++;
            } else if (sym(k, ')') && --depth == 0) {
                return k;
            }
        }
        return -1;
    }

    private static String unquote(String s) {
        return s.length() >= 2 ? s.substring(1, s.length() - 1) : s;
    }

    private static final Set<String> RESERVED = Set.of("SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "ON", "JOIN",
            "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "GROUP", "BY", "ORDER", "HAVING", "UNION", "ALL",
            "EXCEPT", "INTERSECT", "MINUS", "CASE", "WHEN", "THEN", "ELSE", "END", "AS", "IN", "IS", "LIKE",
            "BETWEEN", "EXISTS", "NULL", "TRUE", "FALSE", "SET", "VALUES", "INTO", "WITH", "DISTINCT", "RETURN");
}
