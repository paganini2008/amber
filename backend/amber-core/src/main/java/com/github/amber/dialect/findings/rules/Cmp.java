package com.github.amber.dialect.findings.rules;

import com.github.amber.dialect.split.ScanToken;

import java.util.Set;

/**
 * One simple predicate {@code lhs op rhs} read from tokens: {@code op} is a comparison ({@code = <> < <= > >=}),
 * {@code BETWEEN}, {@code IN} or {@code LIKE} (with {@code NOT} as a prefix, e.g. {@code NOT IN}).
 * Token ranges are {@code [start, end)}.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
record Cmp(int lhsStart, int lhsEnd, String op, int opStart, int rhsStart, int rhsEnd) {

    private static final Set<String> NOT_VALUES = Set.of("SELECT", "CASE", "EXISTS", "AND", "OR", "NOT");
    /** Words that are values, not columns (current date and similar). */
    static final Set<String> VALUE_WORDS = Set.of("SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "LOCALTIMESTAMP", "CURRENT_TIME", "NULL", "TRUE", "FALSE", "DATE", "TIMESTAMP", "INTERVAL", "GETDATE",
            "NOW", "USER", "CURRENT_USER", "SESSION_USER", "DAY", "MONTH", "YEAR", "HOUR", "MINUTE", "SECOND");

    boolean isComparison() {
        return op.equals("=") || op.equals("<>") || op.equals("!=") || op.equals("^=") || op.equals("<")
                || op.equals("<=") || op.equals(">") || op.equals(">=");
    }

    /** Equality or a range: what an index can serve. */
    boolean indexable() {
        return op.equals("=") || op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=")
                || op.equals("BETWEEN") || op.equals("IN") || op.equals("LIKE");
    }

    /**
     * Reads {@code [from, to)} as one predicate whose left side ends at the first operator at the range's
     * depth; null when the range is not such a predicate.
     */
    static Cmp parse(Toks k, int from, int to) {
        if (from >= to || !k.in(from)) {
            return null;
        }
        int d = k.depth[from];
        for (int i = from; i < to; i++) {
            if (k.depth[i] != d) {
                continue;
            }
            String c = k.comparison(i);
            if (c != null && i > from) {
                int rhs = i + c.length();
                return rhs < to ? new Cmp(from, i, c, i, rhs, to) : null;
            }
            if (k.wordIn(i, Set.of("BETWEEN", "IN", "LIKE")) && i > from) {
                String op = k.tok(i).upper();
                int lhsEnd = i;
                int opStart = i;
                if (k.word(i - 1, "NOT")) {
                    op = "NOT " + op;
                    lhsEnd = i - 1;
                    opStart = i - 1;
                }
                return i + 1 < to && lhsEnd > from ? new Cmp(from, lhsEnd, op, opStart, i + 1, to) : null;
            }
            if (k.wordIn(i, Set.of("IS", "AND", "OR", "EXISTS"))) {
                return null;
            }
        }
        return null;
    }

    /**
     * True when tokens [from, to) are a value that does not depend on the block's columns: literals, bind
     * variables, sigil variables, current-date words, function calls over those, or an outer column
     * {@code q.c} whose qualifier is not one of the block's FROM items.
     */
    static boolean valueOnly(Toks k, Scope scope, int from, int to) {
        return valueOnly(k, scope, from, to, null);
    }

    /**
     * As {@link #valueOnly(Toks, Scope, int, int)}; with a context, an unqualified name that no FROM item has
     * per metadata (every item a table with known columns) counts as a variable, so a value.
     */
    static boolean valueOnly(Toks k, Scope scope, int from, int to, RuleContext ctx) {
        if (from >= to) {
            return false;
        }
        for (int i = from; i < to; i++) {
            ScanToken tok = k.tok(i);
            if (tok.type() != ScanToken.Type.WORD && tok.type() != ScanToken.Type.QUOTED_IDENTIFIER) {
                continue;
            }
            if (k.wordIn(i, NOT_VALUES)) {
                return false;
            }
            if (k.sym(i + 1, '(') || k.wordIn(i, VALUE_WORDS) || tok.text().startsWith("@") || k.sym(i - 1, ':')
                    || k.wordIn(i, Set.of("AS", "NULLS", "ESCAPE"))) {
                continue;
            }
            if (k.sym(i + 1, '.') && k.isName(i + 2)) {
                if (scope.byExposed(Toks.key(tok.text())) != null) {
                    return false;                       // a column of this block
                }
                i += 2;                                 // an outer (correlated) column or a package constant
                continue;
            }
            if (ctx != null && variable(ctx, scope, Toks.unquote(tok.text()))) {
                continue;
            }
            return false;                               // an unqualified name: column or variable, unknown
        }
        return true;
    }

    /** True when every FROM item is a table with known columns and none has {@code name}. */
    static boolean variable(RuleContext ctx, Scope scope, String name) {
        if (scope.sources.isEmpty()) {
            return false;
        }
        for (Scope.Src s : scope.sources) {
            if (!s.table()) {
                return false;
            }
            var m = ctx.meta(s.name());
            if (m.isEmpty() || m.get().columns().isEmpty() || m.get().column(name).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /** True when [from, to) is one literal token (optionally signed), e.g. {@code 42}, {@code 'x'}, {@code -1}. */
    static boolean literal(Toks k, int from, int to) {
        int i = from;
        if (k.sym(i, '-') || k.sym(i, '+')) {
            i++;
        }
        if (k.word(i, "N") && k.type(i + 1, ScanToken.Type.STRING)) {
            i++;
        }
        return i == to - 1 && (k.type(i, ScanToken.Type.NUMBER) || k.type(i, ScanToken.Type.STRING));
    }
}
