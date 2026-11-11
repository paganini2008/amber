package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.dialect.split.ScanToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AMB-P003: a correlated aggregate subquery in the select list,
 * {@code (SELECT agg(t.x) FROM tab t WHERE t.k = o.k [AND t.f = 'lit' …]) AS name}, becomes a join to a
 * grouped derived table computed once:
 * {@code … d.agg_value AS name … FROM … LEFT JOIN (SELECT t.k, agg(t.x) AS agg_value FROM tab t WHERE t.f = 'lit'
 * GROUP BY t.k) d ON d.k = o.k}. COUNT becomes {@code COALESCE(d.agg_value, 0)} (a scalar COUNT of no rows is 0,
 * the LEFT JOIN gives NULL).
 *
 * <p>Why it is equivalent: an aggregate without GROUP BY always returns exactly one row; grouping by every
 * correlated inner column gives one row per key, and the outer row matches at most one group (equality on
 * all of the group's keys), so no outer row is repeated or lost, and a row without a group gets the same
 * NULL (or 0 for COUNT). Only this exact shape is rewritten: one table in the subquery, only AND-ed
 * conditions, correlation by plain equalities between an inner column and a column of the outer FROM, every
 * other column qualified with the inner alias, no DISTINCT / GROUP BY / ORDER BY / row limit inside, and an
 * outer block without DISTINCT, grouping, aggregates, row limits, comma joins or Oracle {@code (+)}. The
 * select item must have an alias, so the output column keeps its name.
 *
 * <p>Safety: grouping uses the inner column's values while the subquery compared them with the outer
 * column; when the two types differ, two inner keys that convert to the same outer value (e.g. '01' and
 * '1') would match one outer row twice. With metadata showing the same type on both sides the rewrite is
 * {@link Safety#EQUIVALENT_WITH_METADATA}; otherwise it is reported with that edge case.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class CorrelatedSubqueryRule implements RewriteRule {

    private static final Set<String> AGGREGATES = Set.of("SUM", "MIN", "MAX", "AVG", "COUNT");
    private static final Set<String> ALL_AGGREGATES = Set.of("SUM", "MIN", "MAX", "AVG", "COUNT", "LISTAGG",
            "STRING_AGG", "GROUP_CONCAT", "ARRAY_AGG", "STDDEV", "VARIANCE", "MEDIAN");
    private static final Set<String> SAFE_WORDS = Set.of("DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "AND",
            "OR", "NOT", "IS", "NULL", "IN", "BETWEEN", "LIKE", "ESCAPE", "TRUE", "FALSE", "AS");

    @Override
    public String id() {
        return "AMB-P003";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        Sql sql = ctx.sql();
        List<Candidate> out = new ArrayList<>();
        for (Block outer : sql.blocks()) {
            if (outer.limited || outer.distinct >= 0 || outer.groupBy >= 0 || outer.having >= 0 || outer.exotic
                    || outer.connectBy >= 0 || outer.startWith >= 0 || outer.forClause >= 0 || outer.into >= 0
                    || outer.from < 0 || outerJoinMarks(sql, outer)) {
                continue;
            }
            List<Source> sources = outer.sources();
            if (sources.isEmpty() || sources.stream().anyMatch(s -> s.kind() == Source.Kind.OTHER
                    || s.join().equals(",") || s.exposed(sql) == null)) {
                continue;
            }
            List<int[]> items = outer.items();
            if (aggregateOutside(sql, outer, items)) {
                continue;
            }
            for (int[] item : items) {
                Candidate c = rewrite(ctx, sql, outer, sources, item);
                if (c != null) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private Candidate rewrite(RewriteContext ctx, Sql sql, Block outer, List<Source> outerSources, int[] item) {
        int[] e = outer.itemExpression(item);
        String alias = outer.itemAlias(item);
        int open = e[0];
        int close = sql.close(open);
        if (alias == null || !sql.sym(open, '(') || !sql.word(open + 1, "SELECT") || close != e[1] - 1) {
            return null;
        }
        Block inner = sql.blocks().stream().filter(b -> b.start == open + 1).findFirst().orElse(null);
        if (inner == null || inner.end != close || inner.from < 0 || inner.where < 0 || inner.groupBy >= 0
                || inner.having >= 0 || inner.orderBy >= 0 || inner.limited || inner.exotic || inner.distinct >= 0
                || inner.connectBy >= 0 || inner.startWith >= 0 || inner.into >= 0 || inner.forClause >= 0
                || inner.top >= 0) {
            return null;
        }
        List<Source> innerSources = inner.sources();
        if (innerSources.size() != 1 || innerSources.get(0).kind() != Source.Kind.TABLE) {
            return null;
        }
        Source table = innerSources.get(0);
        String ia = table.exposed(sql);
        Set<String> outerNames = new HashSet<>();
        for (Source s : outerSources) {
            outerNames.add(s.exposed(sql));
        }
        if (outerNames.contains(ia)) {
            return null;
        }
        // the aggregate
        List<int[]> innerItems = inner.items();
        if (innerItems.size() != 1) {
            return null;
        }
        int[] agg = inner.itemExpression(innerItems.get(0));
        if (!sql.wordIn(agg[0], AGGREGATES) || !sql.sym(agg[0] + 1, '(') || sql.close(agg[0] + 1) != agg[1] - 1
                || !onlyInner(sql, agg[0] + 2, agg[1] - 1, ia, true)) {
            return null;
        }
        boolean count = sql.word(agg[0], "COUNT");
        // the conditions
        List<int[]> terms = Predicates.conjuncts(sql, inner.where + 1, inner.whereEnd(), inner.depth);
        if (terms == null) {
            return null;
        }
        Map<String, String[]> keys = new LinkedHashMap<>();       // inner column key → {inner text, outer text}
        List<String> filters = new ArrayList<>();
        List<String> typesSame = new ArrayList<>();
        boolean allTypesKnown = true;
        for (int[] t : terms) {
            int[] eq = correlation(sql, t, ia, outerNames);
            if (eq != null) {
                String col = Sql.key(sql.tok(eq[1]).text());
                if (keys.containsKey(col)) {
                    return null;
                }
                keys.put(col, new String[]{sql.slice(eq[0], eq[1]), sql.slice(eq[2], eq[3]), sql.tok(eq[1]).text()});
                Boolean same = sameType(ctx, table, sql.tok(eq[1]).text(), outerSources, sql, eq[2], eq[3]);
                if (same == null) {
                    allTypesKnown = false;
                } else if (!same) {
                    return null;                    // different types: grouping may split one outer key
                } else {
                    typesSame.add(col);
                }
            } else if (onlyInner(sql, t[0], t[1], ia, false)) {
                filters.add(sql.slice(t[0], t[1] - 1));
            } else {
                return null;
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        String value = fresh(keys.keySet(), "AGG_VALUE");
        String d = NotInRule.freshAlias(sql);
        var ref = sql.tok(0);
        String kSelect = RewriteContext.kw("SELECT", ref);
        String kFrom = RewriteContext.kw("FROM", ref);
        String kWhere = RewriteContext.kw("WHERE", ref);
        String kGroup = RewriteContext.kw("GROUP", ref) + " " + RewriteContext.kw("BY", ref);
        String kAnd = RewriteContext.kw("AND", ref);
        String kAs = RewriteContext.kw("AS", ref);
        String valueName = ref.text().equals(ref.text().toLowerCase(Locale.ROOT)) ? value.toLowerCase(Locale.ROOT) : value;
        List<String> innerCols = new ArrayList<>();
        List<String> on = new ArrayList<>();
        for (String[] k : keys.values()) {
            innerCols.add(k[0]);
            on.add(d + "." + k[2] + " = " + k[1]);
        }
        String tableText = sql.slice(table.nameStart(), table.aliasIndex() >= 0 ? table.aliasIndex() : table.nameEnd());
        String derived = "(" + kSelect + " " + String.join(", ", innerCols) + ", " + sql.slice(agg[0], agg[1] - 1)
                + " " + kAs + " " + valueName + " " + kFrom + " " + tableText
                + (filters.isEmpty() ? "" : " " + kWhere + " " + String.join(" " + kAnd + " ", filters))
                + " " + kGroup + " " + String.join(", ", innerCols) + ")";
        String join = " " + RewriteContext.kw("LEFT", ref) + " " + RewriteContext.kw("JOIN", ref) + " " + derived + " "
                + d + " " + RewriteContext.kw("ON", ref) + " " + String.join(" " + kAnd + " ", on);
        String replacement = count ? RewriteContext.kw("COALESCE", ref) + "(" + d + "." + valueName + ", 0)"
                : d + "." + valueName;
        Source last = outerSources.get(outerSources.size() - 1);
        List<Edit> edits = List.of(ctx.replace(open, close, replacement), ctx.insert(sql.tok(last.end() - 1).end(), join));
        String explanation = Messages.t(count ? "rewrite.correlated.explanation.count" : "rewrite.correlated.explanation",
                sql.tok(item[1] - 1).text(), sql.slice(agg[0], agg[1] - 1));
        if (allTypesKnown) {
            return new Candidate(id(), Messages.t("rewrite.correlated.title"), explanation,
                    edits, Safety.EQUIVALENT_WITH_METADATA,
                    Messages.t("rewrite.correlated.safety.metadata", String.join(", ", typesSame)),
                    List.of("Grouping columns", "Source tables", "Lineage of", "Conditions", "Literal values"));
        }
        return new Candidate(id(), Messages.t("rewrite.correlated.title"), explanation, edits,
                Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, Messages.t("rewrite.correlated.safety.types"));
    }

    /** {@code ia.col = q.col} or {@code q.col = ia.col} as {innerStart, innerEnd, outerStart, outerEnd}; else null. */
    private static int[] correlation(Sql sql, int[] t, String ia, Set<String> outerNames) {
        if (t[1] - t[0] != 7 || !sql.sym(t[0] + 3, '=')) {
            return null;
        }
        int[] a = qualified(sql, t[0]);
        int[] b = qualified(sql, t[0] + 4);
        if (a == null || b == null) {
            return null;
        }
        String qa = Sql.key(sql.tok(a[0]).text());
        String qb = Sql.key(sql.tok(b[0]).text());
        if (qa.equals(ia) && outerNames.contains(qb)) {
            return new int[]{a[0], a[1], b[0], b[1]};
        }
        if (qb.equals(ia) && outerNames.contains(qa)) {
            return new int[]{b[0], b[1], a[0], a[1]};
        }
        return null;
    }

    private static int[] qualified(Sql sql, int i) {
        return sql.isName(i) && sql.sym(i + 1, '.') && sql.isName(i + 2) && !sql.sym(i + 3, '.')
                && !sql.sym(i + 3, '(') ? new int[]{i, i + 2} : null;
    }

    /**
     * True when tokens [from, to) only use columns qualified with the inner alias, plus literals, bind or
     * sigil variables, keywords and function calls; never a subquery. {@code star} allows COUNT(*).
     */
    private static boolean onlyInner(Sql sql, int from, int to, String ia, boolean star) {
        for (int i = from; i < to; i++) {
            ScanToken tok = sql.tok(i);
            if (sql.word(i, "SELECT")) {
                return false;
            }
            if (tok.isSymbol('*') && !(star && to - from == 1)) {
                if (!(sql.sym(i - 1, ')') || sql.tok(i - 1).type() == ScanToken.Type.NUMBER || sql.isName(i - 1))) {
                    return false;
                }
            }
            if (!sql.isName(i) || sql.wordIn(i, SAFE_WORDS) || sql.sym(i + 1, '(') || tok.text().startsWith("@")
                    || sql.sym(i - 1, ':')) {
                continue;
            }
            if (sql.sym(i + 1, '.') && sql.isName(i + 2)) {
                if (!Sql.key(tok.text()).equals(ia) || sql.sym(i + 3, '.')) {
                    return false;
                }
                i += 2;
                continue;
            }
            return false;                           // unqualified: could be an outer column
        }
        return true;
    }

    /** Same declared base type on both sides per metadata; null when unknown. */
    private static Boolean sameType(RewriteContext ctx, Source inner, String innerCol, List<Source> outerSources,
                                    Sql sql, int oq, int oc) {
        String q = Sql.key(sql.tok(oq).text());
        Source outer = outerSources.stream().filter(s -> q.equals(s.exposed(sql))).findFirst().orElse(null);
        if (outer == null || outer.kind() != Source.Kind.TABLE) {
            return null;
        }
        String a = ctx.type(inner, innerCol);
        String b = ctx.type(outer, sql.tok(oc).text());
        if (a == null || b == null) {
            return null;
        }
        return base(a).equals(base(b));
    }

    /**
     * Integer types convert to each other without merging two values (an INT key and a BIGINT key compare
     * like the numbers they hold), so they count as one; anything else must have the same base type.
     */
    private static final Set<String> INTEGERS = Set.of("INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT",
            "MEDIUMINT", "INT2", "INT4", "INT8");

    private static String base(String type) {
        int p = type.indexOf('(');
        String b = (p < 0 ? type : type.substring(0, p)).toUpperCase(Locale.ROOT)
                .replace(" IDENTITY", "").replace(" UNSIGNED", "").replace(" AUTO_INCREMENT", "").strip();
        return INTEGERS.contains(b) ? "INTEGER" : b;
    }

    private static boolean outerJoinMarks(Sql sql, Block b) {
        for (int i = b.start; i + 2 < b.end; i++) {
            if (sql.sym(i, '(') && sql.sym(i + 1, '+') && sql.sym(i + 2, ')')) {
                return true;
            }
        }
        return false;
    }

    /** An aggregate call at the outer block's own level (outside subqueries) in the select list. */
    private static boolean aggregateOutside(Sql sql, Block outer, List<int[]> items) {
        for (int[] it : items) {
            for (int i = it[0]; i < it[1]; i++) {
                if (sql.wordIn(i, ALL_AGGREGATES) && sql.sym(i + 1, '(') && sql.blockAt(i) == outer) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String fresh(Set<String> taken, String base) {
        String n = base;
        for (int i = 2; taken.contains(n); i++) {
            n = base + i;
        }
        return n;
    }
}
