package com.github.amber.dialect.findings.rules;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.findings.Finding.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rules that compare how the script uses columns with the indexes known from metadata. Both only report
 * when the table's metadata is known and names at least one key or index (a definition without any
 * says nothing about indexes); tables the script creates itself are skipped.
 *
 * <ul>
 *   <li>AMB-P001 possible missing index: a column used in join conditions or in equality / range filters
 *       that no index (primary key, unique key or index the script creates) starts with. Uses are counted
 *       across the script and reported once per table and column. Small tables (row estimate under
 *       {@value #SMALL_TABLE}) and columns that follow a used leading column in a composite index are skipped;
 *       for a join, a side that is indexed is enough.</li>
 *   <li>AMB-P006 OR across indexed columns: {@code a = 1 OR b = 2} on different indexed columns of one table,
 *       unless another AND condition of the same WHERE already uses an index of that table.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class IndexRules {

    static final long SMALL_TABLE = 1000;

    /** Aggregated uses of one table column. */
    private static final class Uses {
        final String table;
        final String column;
        final Statement first;
        final TextRange range;
        final Set<Statement> statements = new LinkedHashSet<>();
        final long rows;
        int joins;
        int filters;

        Uses(String table, String column, Statement first, TextRange range, long rows) {
            this.table = table;
            this.column = column;
            this.first = first;
            this.range = range;
            this.rows = rows;
        }
    }

    /** A column of a FROM item used in one block. */
    private record Use(Scope.Src src, TableMeta meta, String column, int start, int end, boolean join) {
    }

    private final RuleContext ctx;
    private final Map<String, Uses> uses = new LinkedHashMap<>();

    IndexRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void statement(Statement st, Toks k) {
        boolean p001 = !ctx.ignored(st, "AMB-P001");
        List<Scope> scopes = new ArrayList<>(Scope.blocks(k));
        Scope dml = Scope.dml(k);
        if (dml != null) {
            scopes.add(dml);
        }
        for (Scope s : scopes) {
            if (s.opaque || s.sources.isEmpty()) {
                continue;
            }
            if (p001) {
                missingIndex(st, k, s);
            }
            orAcrossColumns(st, k, s);
        }
    }

    // ----- AMB-P001 -----------------------------------------------------------------------------------

    private void missingIndex(Statement st, Toks k, Scope s) {
        List<Use> filters = new ArrayList<>();
        List<Use[]> joins = new ArrayList<>();
        List<Use> all = new ArrayList<>();
        for (int[] r : s.predicateRanges()) {
            List<int[]> terms = s.conjuncts(r[0], r[1]);
            if (terms == null) {
                continue;                               // OR at the top: no single index decides
            }
            for (int[] t : terms) {
                Cmp c = Cmp.parse(k, t[0], t[1]);
                if (c == null || !c.indexable() || (c.op().equals("LIKE") && !prefixPattern(k, c))) {
                    continue;
                }
                Use left = use(k, s, c.lhsStart(), c.lhsEnd(), r[2] == 1);
                if (left == null) {
                    continue;
                }
                if (c.op().equals("=")) {
                    Use right = use(k, s, c.rhsStart(), c.rhsEnd(), true);
                    if (right != null && right.src() != left.src()) {
                        Use l = new Use(left.src(), left.meta(), left.column(), left.start(), left.end(), true);
                        joins.add(new Use[]{l, right});
                        all.add(l);
                        all.add(right);
                        continue;
                    }
                }
                if (k.hasSelect(c.rhsStart(), c.rhsEnd()) || !Cmp.valueOnly(k, s, c.rhsStart(), c.rhsEnd(), ctx)) {
                    continue;
                }
                Use f = new Use(left.src(), left.meta(), left.column(), left.start(), left.end(), false);
                filters.add(f);
                all.add(f);
            }
        }
        for (Use u : filters) {
            if (u.meta() != null && !indexed(u, all)) {
                record(st, k, u);
            }
        }
        for (Use[] j : joins) {
            if (j[0].meta() == null || j[1].meta() == null || indexed(j[0], all) || indexed(j[1], all)) {
                continue;                               // one side indexed (or unknown) is enough for a join
            }
            record(st, k, j[0]);
            record(st, k, j[1]);
        }
    }

    private Use use(Toks k, Scope s, int from, int to, boolean join) {
        int end = s.refEnd(from);
        if (end < 0 || end != to - 1) {
            return null;
        }
        Scope.ColRef ref = s.column(from, end, ctx);
        if (ref == null) {
            return null;
        }
        TableMeta m = ctx.meta(ref.src().name()).orElse(null);
        if (m != null && (!RuleContext.hasIndexInfo(m) || m.column(ref.column()).isEmpty()
                || (m.rowEstimate() > 0 && m.rowEstimate() < SMALL_TABLE))) {
            m = null;
        }
        return new Use(ref.src(), m, m == null ? ref.column() : m.column(ref.column()).get().name(), from, end, join);
    }

    /**
     * Indexed for this use: an index starts with the column, or the column follows a leading column the
     * same block also uses on the same table.
     */
    private boolean indexed(Use u, List<Use> all) {
        if (u.meta() == null) {
            return false;
        }
        if (ctx.indexedLeading(u.src().name(), u.meta(), u.column())) {
            return true;
        }
        Set<String> used = new java.util.HashSet<>();
        for (Use o : all) {
            if (o.src() == u.src()) {
                used.add(o.column().toUpperCase(Locale.ROOT));
            }
        }
        List<List<String>> composite = new ArrayList<>();
        u.meta().indexes().forEach(ix -> composite.add(ix.columns()));
        composite.addAll(u.meta().uniqueKeys());
        composite.add(u.meta().primaryKey());
        for (List<String> cols : composite) {
            int pos = indexOf(cols, u.column());
            if (pos > 0 && used.contains(cols.get(0).toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<String> cols, String column) {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean prefixPattern(Toks k, Cmp c) {
        if (c.rhsEnd() - c.rhsStart() != 1 || !k.type(c.rhsStart(), com.github.amber.dialect.split.ScanToken.Type.STRING)) {
            return false;
        }
        String s = k.tok(c.rhsStart()).text();
        return s.length() > 2 && s.charAt(1) != '%' && s.charAt(1) != '_';
    }

    private void record(Statement st, Toks k, Use u) {
        String display = u.meta().qualifiedName();
        String key = display.toUpperCase(Locale.ROOT) + "." + u.column().toUpperCase(Locale.ROOT);
        Uses agg = uses.computeIfAbsent(key, x -> new Uses(display, u.column(), st, ctx.range(k, u.start(), u.end()),
                u.meta().rowEstimate()));
        agg.statements.add(st);
        if (u.join()) {
            agg.joins++;
        } else {
            agg.filters++;
        }
    }

    /** Reports the aggregated AMB-P001 hints, once per table and column. */
    void finish() {
        for (Uses u : uses.values()) {
            int total = u.joins + u.filters;
            List<String> how = new ArrayList<>();
            if (u.joins > 0) {
                how.add(Messages.t(u.joins == 1 ? "rules.P001.joins.one" : "rules.P001.joins.other", u.joins));
            }
            if (u.filters > 0) {
                how.add(Messages.t(u.filters == 1 ? "rules.P001.filters.one" : "rules.P001.filters.other", u.filters));
            }
            int n = u.statements.size();
            String rows = u.rows > 0 ? Messages.t("rules.P001.rows", u.rows) : "";
            String ix = indexName(simple(u.table), u.column);
            String ddl = "CREATE INDEX " + ix + " ON " + u.table + " (" + u.column + ")";
            ctx.add("AMB-P001", n >= 3 ? Severity.LOW : Severity.INFO, Messages.t("rule.AMB-P001.title"),
                    Messages.t("rules.P001.message", u.table + "." + u.column,
                            Messages.t(total == 1 ? "rules.P001.times.one" : "rules.P001.times.other", total),
                            Messages.t(n == 1 ? "rules.P001.statements.one" : "rules.P001.statements.other", n),
                            String.join(Messages.t("rules.list.separator"), how), u.table, rows, u.column),
                    Messages.t("rules.P001.suggestion", u.column, ddl),
                    u.first, u.range, Confidence.CERTAIN, SqlCheck.ddl(ctx, ddl, Messages.t("rules.P001.note")));
        }
    }

    /** {@code ix_<table>_<column>}, lower-case, letters / digits / underscores only, at most 30 characters. */
    static String indexName(String table, String column) {
        String n = ("ix_" + table + "_" + column).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return n.length() > 30 ? n.substring(0, 30) : n;
    }

    private static String simple(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }

    // ----- AMB-P006 -----------------------------------------------------------------------------------

    private void orAcrossColumns(Statement st, Toks k, Scope s) {
        if (s.where < 0) {
            return;
        }
        List<int[]> groups = new ArrayList<>();
        List<int[]> terms = s.conjuncts(s.where + 1, s.whereEnd);
        if (terms == null) {
            groups.add(new int[]{s.where + 1, s.whereEnd});
            terms = List.of();
        } else {
            for (int[] t : terms) {
                if (k.sym(t[0], '(') && k.close(t[0]) == t[1] - 1 && !k.word(t[0] + 1, "SELECT")) {
                    groups.add(new int[]{t[0] + 1, t[1] - 1});
                }
            }
        }
        for (int[] g : groups) {
            List<int[]> parts = disjuncts(k, g[0], g[1]);
            if (parts.size() < 2) {
                continue;
            }
            Scope.Src src = null;
            TableMeta meta = null;
            Set<String> columns = new LinkedHashSet<>();
            boolean ok = true;
            for (int[] p : parts) {
                Cmp c = Cmp.parse(k, p[0], p[1]);
                Use u = c == null || !c.indexable() || k.hasSelect(p[0], p[1]) ? null
                        : use(k, s, c.lhsStart(), c.lhsEnd(), false);
                if (u == null || u.meta() == null || (src != null && u.src() != src)
                        || !Cmp.valueOnly(k, s, c.rhsStart(), c.rhsEnd(), ctx)
                        || !ctx.indexedLeading(u.src().name(), u.meta(), u.column())) {
                    ok = false;
                    break;
                }
                src = u.src();
                meta = u.meta();
                columns.add(u.column());
            }
            if (!ok || columns.size() < 2 || otherIndexedTerm(k, s, terms, g, src)) {
                continue;
            }
            ctx.add("AMB-P006", Severity.INFO, Messages.t("rule.AMB-P006.title"), Messages.t("rules.P006.message",
                    meta.qualifiedName(), String.join(", ", columns)), Messages.t("rules.P006.suggestion"), st, ctx.range(k, g[0], g[1] - 1),
                    Confidence.CERTAIN, unionAll(st, k, s, terms, g, parts));
        }
    }

    /**
     * The statement as UNION ALL branches, one per OR term; branch i excludes the rows of the earlier
     * branches with {@code CASE WHEN term THEN 0 ELSE 1 END = 1} (true also when the term is unknown), so
     * every row comes back exactly once. Only for a plain single-block SELECT without DISTINCT, grouping,
     * aggregates, ordering, row limits or INTO.
     */
    private com.github.amber.dialect.findings.Finding.SuggestedSql unionAll(Statement st, Toks k, Scope s,
                                                                            List<int[]> terms, int[] g,
                                                                            List<int[]> parts) {
        if (st.kind() != com.github.amber.core.ir.StatementKind.SELECT || s.start != 0 || Scope.blocks(k).size() != 1
                || s.limited || s.distinct >= 0 || s.groupBy >= 0 || s.having >= 0 || s.orderBy >= 0
                || s.whereEnd != s.end || (s.end < k.size() && !(k.sym(s.end, ';') && s.end == k.size() - 1))) {
            return null;
        }
        for (int i = 0; i < k.size(); i++) {
            if ((k.depth[i] == 0 && k.wordIn(i, Set.of("INTO", "FOR"))) || k.word(i, "OVER")
                    || (k.wordIn(i, Set.of("SUM", "COUNT", "MIN", "MAX", "AVG")) && k.sym(i + 1, '('))) {
                return null;
            }
        }
        String head = k.slice(0, s.where - 1);
        List<String> others = new ArrayList<>();
        for (int[] t : terms) {
            if (!(t[0] <= g[0] && g[1] <= t[1])) {
                others.add(k.slice(t[0], t[1] - 1));
            }
        }
        List<String> branches = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            List<String> conds = new ArrayList<>(others);
            conds.add(k.slice(parts.get(i)[0], parts.get(i)[1] - 1));
            for (int j = 0; j < i; j++) {
                conds.add("CASE WHEN " + k.slice(parts.get(j)[0], parts.get(j)[1] - 1) + " THEN 0 ELSE 1 END = 1");
            }
            branches.add(head + " WHERE " + String.join(" AND ", conds));
        }
        return SqlCheck.rewrite(ctx, ctx.text(st), String.join("\nUNION ALL\n", branches),
                Messages.t("rules.P006.note"));
    }

    /** Another top-level AND term (outside the OR group) that an index of the same table can serve. */
    private boolean otherIndexedTerm(Toks k, Scope s, List<int[]> terms, int[] group, Scope.Src src) {
        for (int[] t : terms) {
            if (t[0] <= group[0] && group[1] <= t[1]) {
                continue;
            }
            Cmp c = Cmp.parse(k, t[0], t[1]);
            Use u = c == null || !c.indexable() ? null : use(k, s, c.lhsStart(), c.lhsEnd(), false);
            if (u != null && u.src() == src && u.meta() != null
                    && ctx.indexedLeading(u.src().name(), u.meta(), u.column())) {
                return true;
            }
        }
        return false;
    }

    /** Splits [from, to) on OR at its depth; a single part when there is no OR. */
    static List<int[]> disjuncts(Toks k, int from, int to) {
        List<int[]> out = new ArrayList<>();
        if (from >= to) {
            return out;
        }
        int d = k.depth[from];
        int s = from;
        for (int i = from; i < to; i++) {
            if (k.depth[i] == d && k.word(i, "OR")) {
                out.add(new int[]{s, i});
                s = i + 1;
            } else if (k.depth[i] == d && k.word(i, "AND") && !betweenAnd(k, s, i)) {
                return List.of();                       // mixed AND / OR at one level: not a simple OR list
            }
        }
        out.add(new int[]{s, to});
        return out;
    }

    private static boolean betweenAnd(Toks k, int from, int and) {
        for (int i = and - 1; i >= from; i--) {
            if (k.depth[i] == k.depth[and] && k.word(i, "BETWEEN")) {
                return true;
            }
        }
        return false;
    }

}
