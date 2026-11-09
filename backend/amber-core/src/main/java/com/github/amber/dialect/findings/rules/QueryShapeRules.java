package com.github.amber.dialect.findings.rules;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.findings.Finding.Severity;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.split.ScanToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rules about the shape of one query block:
 *
 * <ul>
 *   <li>AMB-P003 a scalar subquery in the select list that refers to a column of the outer query
 *       ({@code q.col} with {@code q} a FROM item of the outer block, not of the subquery) — it is evaluated
 *       per outer row. Not reported when the outer query limits its rows (TOP, LIMIT, FETCH, ROWNUM).</li>
 *   <li>AMB-P007 arithmetic or concatenation on a column before a comparison in WHERE / ON
 *       ({@code col + 1 = 5}, {@code a.x || a.y = 'ab'}), and NVL / COALESCE / ISNULL / IFNULL on a column in an
 *       ON condition (in WHERE that is AMB-S008). Certain when metadata shows an index led by the column.
 *       Unqualified names inside routines are only taken as columns when metadata confirms them.</li>
 *   <li>AMB-P009 {@code SELECT DISTINCT} over joins whose select list is only columns of one table and covers
 *       a unique key of it (metadata): the rows of that table are distinct already, so DISTINCT only removes
 *       the duplicates the joins create.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class QueryShapeRules {

    private static final Set<String> NULL_FUNCTIONS = Set.of("NVL", "COALESCE", "ISNULL", "IFNULL");
    private static final Set<String> INNER = Set.of("", ",", "JOIN", "INNER JOIN");
    private static final Set<String> BOUNDARY = Set.of("WHERE", "AND", "OR", "ON", "NOT", "HAVING");

    private final RuleContext ctx;
    private final Set<Statement> inRoutine;

    QueryShapeRules(RuleContext ctx, Set<Statement> inRoutine) {
        this.ctx = ctx;
        this.inRoutine = inRoutine;
    }

    void statement(Statement st, Toks k) {
        List<Scope> blocks = Scope.blocks(k);
        for (Scope b : blocks) {
            if (b.opaque) {
                continue;
            }
            correlated(st, k, b);
            distinctJoin(st, k, b);
        }
        List<Scope> scopes = new ArrayList<>(blocks);
        Scope dml = Scope.dml(k);
        if (dml != null) {
            scopes.add(dml);
        }
        for (Scope s : scopes) {
            if (!s.opaque && !s.sources.isEmpty()) {
                for (int[] r : s.predicateRanges()) {
                    calculation(st, k, s, r[0], r[1], r[2] == 1);
                }
            }
        }
    }

    // ----- AMB-P003 -----------------------------------------------------------------------------------

    private void correlated(Statement st, Toks k, Scope b) {
        if (b.limited || b.sources.isEmpty() || b.listStart < 0) {
            return;
        }
        int i = b.listStart;
        while (i < b.listEnd) {
            if (k.sym(i, '(') && k.word(i + 1, "SELECT") && k.close(i) > i) {
                int close = k.close(i);
                String outer = outerReference(k, b, i, close);
                if (outer != null) {
                    String apply = switch (ctx.dialect) {
                        case SQLSERVER -> "OUTER APPLY";
                        case ORACLE -> Messages.t("rules.P003.apply.oracle");
                        case POSTGRESQL, MYSQL, DB2 -> "LEFT JOIN LATERAL";
                        default -> Messages.t("rules.P003.apply.other");
                    };
                    ctx.add("AMB-P003", Severity.LOW, Messages.t("rule.AMB-P003.title"),
                            Messages.t("rules.P003.message", outer), Messages.t("rules.P003.suggestion", apply), st,
                            ctx.range(k, i, close),
                            Confidence.INFERRED);
                }
                i = close + 1;
            } else {
                i++;
            }
        }
    }

    /** The first {@code q.col} inside (open, close) whose {@code q} is the outer block's, not the subquery's. */
    static String outerReference(Toks k, Scope outer, int open, int close) {
        Set<String> inner = new HashSet<>();
        Set<Integer> nameTokens = new HashSet<>();
        for (Scope s : Scope.blocks(k)) {
            if (s.start > open && s.start < close) {
                if (s.opaque && s.sources.isEmpty() && s.from >= 0) {
                    return null;
                }
                for (Scope.Src src : s.sources) {
                    if (src.exposed() != null) {
                        inner.add(src.exposed());
                    }
                    for (int j = src.nameStart(); j <= src.nameEnd(); j++) {
                        nameTokens.add(j);
                    }
                }
            }
        }
        for (int j = open + 1; j + 2 < close; j++) {
            if (nameTokens.contains(j) || !k.isName(j) || !k.sym(j + 1, '.') || !k.isPlainName(j + 2)
                    || k.sym(j - 1, '.') || k.sym(j + 3, '.')) {
                continue;
            }
            String q = Toks.key(k.tok(j).text());
            if (!inner.contains(q) && outer.byExposed(q) != null) {
                return k.slice(j, j + 2);
            }
        }
        return null;
    }

    // ----- AMB-P007 -----------------------------------------------------------------------------------

    private void calculation(Statement st, Toks k, Scope s, int from, int to, boolean on) {
        if (from >= to) {
            return;
        }
        int d = k.depth[from];
        for (int i = from; i < to; i++) {
            if (k.depth[i] < d || !(i == from || k.wordIn(i - 1, BOUNDARY) || k.sym(i - 1, '('))) {
                continue;
            }
            if (on && k.wordIn(i, NULL_FUNCTIONS) && k.sym(i + 1, '(')) {
                int close = k.close(i + 1);
                int ce = s.refEnd(i + 2);
                if (close > 0 && ce >= 0 && k.sym(ce + 1, ',') && comparisonAfter(k, close + 1)) {
                    Scope.ColRef ref = column(st, k, s, i + 2, ce);
                    if (ref != null) {
                        String col = k.slice(i + 2, ce);
                        report(st, k, s, ref, i, close, "rules.P007.nullFn.message",
                                new Object[] {k.tok(i).upper(), col}, Messages.t("rules.P007.nullFn.suggestion", col),
                                null);
                    }
                }
                continue;
            }
            int ce = s.refEnd(i);
            if (ce < 0) {
                continue;
            }
            String op = operator(k, ce + 1);
            if (op == null) {
                continue;
            }
            int cmp = findComparison(k, ce + 1, to);
            if (cmp < 0) {
                continue;
            }
            Scope.ColRef ref = column(st, k, s, i, ce);
            if (ref == null) {
                continue;
            }
            String col = k.slice(i, ce);
            String left = k.slice(i, cmp - 1);
            String c = k.comparison(cmp);
            String hint = "";
            Finding.SuggestedSql sql = null;
            if ((op.equals("+") || op.equals("-")) && cmp == ce + 3 && k.type(ce + 2, ScanToken.Type.NUMBER)
                    && c != null && !c.equals("<>") && !c.equals("!=")) {
                int v = cmp + c.length();
                if ((k.type(v, ScanToken.Type.NUMBER) || k.type(v, ScanToken.Type.STRING) || k.isName(v))
                        && !k.sym(v + 1, '(') && !k.sym(v + 1, '.')
                        && (v + 1 == to || (k.in(v + 1) && (k.wordIn(v + 1, BOUNDARY) || k.sym(v + 1, ')')
                        || k.wordIn(v + 1, Set.of("GROUP", "ORDER", "THEN")) || k.sym(v + 1, ';'))))) {
                    String moved = col + " " + c + " " + k.slice(v, v) + " " + (op.equals("+") ? "-" : "+") + " "
                            + k.tok(ce + 2).text();
                    hint = moved;
                    sql = SqlCheck.rewrite(ctx, ctx.text(st), ctx.replaced(st, k.tok(i).start(), k.tok(v).end(), moved),
                            Messages.t("rules.P007.note"));
                }
            }
            boolean concat = op.equals("||");
            report(st, k, s, ref, i, cmp - 1, concat ? "rules.P007.concat.message" : "rules.P007.arithmetic.message",
                    new Object[] {col, left.replaceAll("\\s+", " ")}, concat
                    ? Messages.t("rules.P007.concat.suggestion")
                    : hint.isEmpty() ? Messages.t("rules.P007.arithmetic.suggestion")
                    : Messages.t("rules.P007.arithmetic.suggestion.example", hint), sql);
            i = cmp;
        }
    }

    private Scope.ColRef column(Statement st, Toks k, Scope s, int from, int end) {
        Scope.ColRef ref = s.column(from, end, ctx);
        if (ref == null) {
            return null;
        }
        if (from == end && inRoutine.contains(st)) {
            TableMeta m = ctx.meta(ref.src().name()).orElse(null);
            if (m == null || m.column(ref.column()).isEmpty()) {
                return null;                            // could be a variable of the routine
            }
        }
        return ref;
    }

    /** {@code messageKey} names the message; its {@code .indexed} variant is used when the column has a known index. */
    private void report(Statement st, Toks k, Scope s, Scope.ColRef ref, int from, int to, String messageKey,
                        Object[] args, String suggestion, Finding.SuggestedSql sql) {
        TableMeta m = ctx.meta(ref.src().name()).orElse(null);
        boolean indexed = m != null && m.column(ref.column()).isPresent()
                && ctx.indexedLeading(ref.src().name(), m, ref.column());
        ctx.add("AMB-P007", Severity.LOW, Messages.t("rule.AMB-P007.title"), indexed
                ? Messages.t(messageKey + ".indexed", args) + Messages.t("rules.P007.indexedNote", m.qualifiedName(),
                ref.column()) : Messages.t(messageKey, args), suggestion, st, ctx.range(k, from, to),
                indexed ? Confidence.CERTAIN : Confidence.INFERRED, sql);
    }

    /** An arithmetic or concatenation operator at {@code i}. */
    private String operator(Toks k, int i) {
        if (k.sym(i, '|') && k.sym(i + 1, '|') && k.tok(i + 1).start() == k.tok(i).end()) {
            return ctx.dialect == Dialect.MYSQL || ctx.dialect == Dialect.MARIADB ? null : "||";
        }
        if (k.sym(i, '+') || k.sym(i, '-') || k.sym(i, '*') || k.sym(i, '/')) {
            if (k.sym(i, '*') && k.sym(i - 1, '.')) {
                return null;
            }
            if (k.sym(i, '-') && k.sym(i + 1, '>') && k.tok(i + 1).start() == k.tok(i).end()) {
                return null;                            // JSON operator -> / ->>
            }
            return k.tok(i).text();
        }
        return null;
    }

    /**
     * The comparison operator ending a simple expression that starts at {@code i}: only operands
     * (literals, names, operators, parentheses without a query) may come first. -1 when there is none.
     */
    private static int findComparison(Toks k, int i, int to) {
        int d = k.depth[i];
        for (int j = i; j < to && k.depth[j] >= d; j++) {
            if (k.depth[j] == d && k.comparison(j) != null) {
                return j > i ? j : -1;
            }
            if (k.word(j, "SELECT") || (k.depth[j] == d && k.wordIn(j, Set.of("AND", "OR", "THEN", "IS", "IN",
                    "LIKE", "BETWEEN", "WHEN", "ELSE", "END", "NOT", "CASE")))) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean comparisonAfter(Toks k, int i) {
        return k.comparison(i) != null;
    }

    // ----- AMB-P009 -----------------------------------------------------------------------------------

    private void distinctJoin(Statement st, Toks k, Scope b) {
        if (b.distinct < 0 || !k.word(b.distinct, "DISTINCT") || b.groupBy >= 0 || b.having >= 0
                || b.sources.size() < 2 || b.oldOuterJoin) {
            return;
        }
        for (Scope.Src s : b.sources) {
            if (!s.table() || !INNER.contains(s.join())) {
                return;                                 // outer joins keep rows EXISTS would drop
            }
        }
        Scope.Src owner = null;
        List<String> columns = new ArrayList<>();
        boolean star = false;
        for (int[] item : b.items()) {
            int a = item[0];
            int e = item[1];
            if (e - a >= 3 && k.word(e - 2, "AS")) {
                e -= 2;
            } else if (e - a >= 2 && k.isPlainName(e - 1) && !k.sym(e - 2, '.')) {
                e -= 1;
            }
            Scope.Src src;
            if (e - a == 3 && k.isName(a) && k.sym(a + 1, '.') && k.sym(a + 2, '*')) {
                src = b.byExposed(Toks.key(k.tok(a).text()));
                star = true;
            } else {
                int end = b.refEnd(a);
                if (end != e - 1) {
                    return;
                }
                Scope.ColRef ref = b.column(a, end, ctx);
                if (ref == null) {
                    return;
                }
                src = ref.src();
                columns.add(ref.column());
            }
            if (src == null || (owner != null && owner != src)) {
                return;
            }
            owner = src;
        }
        if (owner == null) {
            return;
        }
        TableMeta m = ctx.meta(owner.name()).orElse(null);
        if (m == null || !(star ? RuleContext.hasIndexInfo(m) && hasUniqueKey(m) : m.isUnique(columns))) {
            return;
        }
        Set<String> others = new LinkedHashSet<>();
        for (Scope.Src s : b.sources) {
            if (s != owner) {
                others.add(s.name());
            }
        }
        ctx.add("AMB-P009", Severity.LOW, Messages.t("rule.AMB-P009.title"), Messages.t("rules.P009.message",
                m.qualifiedName(), String.join(", ", others)), Messages.t("rules.P009.suggestion"), st,
                ctx.range(k, b.start, b.distinct), Confidence.CERTAIN);
    }

    private static boolean hasUniqueKey(TableMeta m) {
        return !m.primaryKey().isEmpty() || !m.uniqueKeys().isEmpty()
                || m.indexes().stream().anyMatch(TableMeta.IndexMeta::unique);
    }

}
