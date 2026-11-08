package com.github.amber.diff;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.ColumnNode.OwnerKind;
import com.github.amber.core.lineage.LineageGraph;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.source.TextRange;
import com.github.amber.diff.DiffResult.ColumnChange;
import com.github.amber.diff.DiffResult.ColumnChangeKind;
import com.github.amber.diff.DiffResult.IndirectChange;
import com.github.amber.diff.DiffResult.LineageChanges;
import com.github.amber.diff.DiffResult.Risk;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Column-lineage impact. For every column written to a table, view or temp table, the base source
 * columns are found by following direct edges back through CTEs, subqueries and temp tables (temp
 * tables through the statements that filled them earlier); the transforms met on the way and the
 * columns that filter, join or group the rows are collected too. The two versions' profiles are
 * then compared per written column and per written object.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class LineageComparer {

    private static final Set<OwnerKind> DATA = EnumSet.of(OwnerKind.TABLE, OwnerKind.TEMP_TABLE,
            OwnerKind.TABLE_VARIABLE, OwnerKind.VIEW, OwnerKind.MATERIALIZED_VIEW);
    /** Intermediates filled by earlier statements (procedural variables hold values between statements too). */
    private static final Set<OwnerKind> TEMP = EnumSet.of(OwnerKind.TEMP_TABLE, OwnerKind.TABLE_VARIABLE,
            OwnerKind.VARIABLE);
    private static final Set<OwnerKind> HOPS = EnumSet.of(OwnerKind.CTE, OwnerKind.SUBQUERY, OwnerKind.RESULT,
            OwnerKind.TEMP_TABLE, OwnerKind.TABLE_VARIABLE, OwnerKind.VARIABLE);
    private static final String ROWS_KEY = ColumnNode.ROWS.toUpperCase(Locale.ROOT);

    /** Everything known about one written column. */
    static final class Col {
        String owner;
        String ownerKey;
        String column;
        OwnerKind ownerKind;
        final Set<String> sources = new TreeSet<>();
        final Map<String, String> display = new HashMap<>();
        final Set<String> transforms = new TreeSet<>();
        Integer step;
        TextRange range;
    }

    /** One base column influencing the rows of a written object. */
    record Item(String role, String sourceKey, String source, int step, TextRange range) {
    }

    /** The columns that filter, join or group the rows of one written object. */
    static final class Rows {
        String owner;
        OwnerKind ownerKind;
        final Map<String, Item> items = new LinkedHashMap<>();
    }

    /** Lineage profile of one version. */
    static final class Profile {
        final Map<String, Col> columns = new LinkedHashMap<>();
        final Map<String, Rows> rows = new LinkedHashMap<>();
    }

    private LineageComparer() {
    }

    // ----- profile ---------------------------------------------------------------------------------

    static Profile profile(Version v) {
        Profile prof = new Profile();
        Map<String, List<LineageGraph.Edge>> into = new HashMap<>();
        Map<Integer, List<LineageGraph.Edge>> byStep = new TreeMap<>();
        for (LineageGraph.Edge e : v.graph.edges()) {
            into.computeIfAbsent(e.edge().to().key(), k -> new ArrayList<>()).add(e);
            byStep.computeIfAbsent(v.ids.get(e.statement()), k -> new ArrayList<>()).add(e);
        }
        Walker w = new Walker(v, into);
        for (int step = 0; step < v.steps.size(); step++) {
            Statement st = v.steps.get(step);
            Map<String, ColumnNode> targets = new LinkedHashMap<>();
            Map<String, ColumnNode> owners = new LinkedHashMap<>();
            if (st.lineage() != null) {
                for (ColumnNode o : st.lineage().outputs()) {
                    ColumnNode k = kinded(v, o);
                    if (DATA.contains(k.ownerKind()) && !k.isRows()) {
                        targets.putIfAbsent(k.key(), k);
                    }
                }
            }
            for (LineageGraph.Edge e : byStep.getOrDefault(step, List.of())) {
                ColumnNode to = e.edge().to();
                if (DATA.contains(to.ownerKind())) {
                    owners.putIfAbsent(to.ownerKey(), to);
                    if (!to.isRows()) {
                        targets.putIfAbsent(to.key(), to);
                    }
                }
            }
            for (ColumnNode t : targets.values()) {
                owners.putIfAbsent(t.ownerKey(), t);
            }
            for (ColumnNode o : owners.values()) {
                Set<Visit> visited = new LinkedHashSet<>();
                visited.add(new Visit(o.ownerKey(), step, false));
                for (ColumnNode t : targets.values()) {
                    if (!t.ownerKey().equals(o.ownerKey())) {
                        continue;
                    }
                    Col c = prof.columns.computeIfAbsent(t.key(), k -> new Col());
                    if (c.owner == null) {
                        c.owner = t.owner();
                        c.ownerKey = t.ownerKey();
                        c.column = t.column();
                        c.ownerKind = t.ownerKind();
                        c.step = step;
                        c.range = firstRange(into.getOrDefault(t.key(), List.of()), st);
                    }
                    w.resolve(t, step, true, new HashSet<>(), c.sources, c.display, c.transforms, visited);
                    if (c.transforms.size() > 1) {
                        c.transforms.remove("PASS_THROUGH");     // hops through CTEs and temp tables copy values
                    }
                }
                Rows r = prof.rows.computeIfAbsent(o.ownerKey(), k -> new Rows());
                r.owner = r.owner == null ? o.owner() : r.owner;
                r.ownerKind = r.ownerKind == null ? o.ownerKind() : r.ownerKind;
                w.rows(visited, r);
            }
        }
        return prof;
    }

    private static ColumnNode kinded(Version v, ColumnNode n) {
        ColumnNode g = v.graph.node(n.key());
        return g == null ? n : g;
    }

    private static TextRange firstRange(List<LineageGraph.Edge> edges, Statement st) {
        for (LineageGraph.Edge e : edges) {
            if (e.statement() == st && e.edge().isDirect() && e.edge().range() != null) {
                return e.edge().range();
            }
        }
        return st.range();
    }

    /** An owner whose row influences count: in statement {@code step}, or (temp tables) earlier. */
    private record Visit(String ownerKey, int step, boolean earlier) {
    }

    /** Walks the graph of one version. */
    private static final class Walker {
        private final Version v;
        private final Map<String, List<LineageGraph.Edge>> into;

        Walker(Version v, Map<String, List<LineageGraph.Edge>> into) {
            this.v = v;
            this.into = into;
        }

        void resolve(ColumnNode node, int step, boolean top, Set<String> seen, Set<String> sources,
                     Map<String, String> display, Set<String> transforms, Set<Visit> visited) {
            if (!seen.add(node.key() + "@" + step)) {
                return;
            }
            if (!top) {
                visited.add(new Visit(node.ownerKey(), step, TEMP.contains(node.ownerKind())));
            }
            for (LineageGraph.Edge e : into.getOrDefault(node.key(), List.of())) {
                ColumnEdge edge = e.edge();
                int s = v.ids.get(e.statement());
                boolean allowed = s == step || (!top && TEMP.contains(node.ownerKind()) && s < step);
                if (!edge.isDirect() || !allowed) {
                    continue;
                }
                if (edge.transform() != null) {
                    transforms.add(edge.transform().name());
                }
                ColumnNode f = edge.from();
                if (HOPS.contains(f.ownerKind())) {
                    resolve(f, s, false, seen, sources, display, transforms, visited);
                } else {
                    String key = baseKey(f);
                    sources.add(key);
                    display.putIfAbsent(key, f.owner() + "." + f.column());
                }
            }
        }

        /** Adds the indirect influences on every visited owner to {@code rows}. */
        void rows(Set<Visit> visited, Rows rows) {
            for (Visit vis : List.copyOf(visited)) {
                String ownerKey = vis.ownerKey();
                int step = vis.step();
                boolean earlier = vis.earlier();
                for (LineageGraph.Edge e : into.getOrDefault(ownerKey + "." + ROWS_KEY, List.of())) {
                    ColumnEdge edge = e.edge();
                    int s = v.ids.get(e.statement());
                    if (edge.isDirect() || !(s == step || (earlier && s < step))) {
                        continue;
                    }
                    Set<String> bases = new TreeSet<>();
                    Map<String, String> display = new HashMap<>();
                    ColumnNode f = edge.from();
                    if (HOPS.contains(f.ownerKind())) {
                        resolve(f, s, false, new HashSet<>(), bases, display, new TreeSet<>(), new LinkedHashSet<>());
                    }
                    if (bases.isEmpty()) {
                        bases.add(baseKey(f));
                        display.put(baseKey(f), f.owner() + "." + f.column());
                    }
                    for (String b : bases) {
                        String role = edge.role().name();
                        rows.items.putIfAbsent(role + "|" + b, new Item(role, b, display.get(b), s, edge.range() == null
                                ? v.steps.get(s).range() : edge.range()));
                    }
                }
            }
        }
    }

    private static String baseKey(ColumnNode n) {
        return n.ownerKind() == OwnerKind.UNKNOWN ? "?." + n.column().toUpperCase(Locale.ROOT) : n.key();
    }

    // ----- comparison ------------------------------------------------------------------------------

    static LineageChanges compare(DiffContext ctx) {
        Profile pb = profile(ctx.before);
        Profile pa = profile(ctx.after);
        List<ColumnChange> columns = new ArrayList<>();
        Map<String, List<String>> addedByOwner = new LinkedHashMap<>();
        Map<String, List<String>> removedByOwner = new LinkedHashMap<>();
        Map<String, Col[]> owners = new LinkedHashMap<>();
        for (Map.Entry<String, Col> e : pb.columns.entrySet()) {
            Col b = e.getValue();
            Col a = pa.columns.get(e.getKey());
            if (a == null) {
                columns.add(column(ColumnChangeKind.REMOVED, b, null, Messages.t("diff.column.removed", b.owner + "." + b.column)));
                removedByOwner.computeIfAbsent(b.owner, k -> new ArrayList<>()).add(b.column);
                owners.putIfAbsent(b.owner, new Col[]{b, null});
                continue;
            }
            if (!a.sources.equals(b.sources)) {
                ColumnChange c = column(ColumnChangeKind.SOURCES_CHANGED, b, a, sourcesMessage(b, a));
                columns.add(c);
                ctx.reason(TEMP.contains(a.ownerKind) ? Risk.MEDIUM : Risk.HIGH, "COLUMN_SOURCE_CHANGED",
                        c.message(), b.step, a.step, b.range, a.range);
            }
            if (!a.transforms.equals(b.transforms)) {
                String msg = Messages.t("diff.column.derived", a.owner + "." + a.column, String.join(", ", b.transforms),
                        String.join(", ", a.transforms));
                columns.add(column(ColumnChangeKind.TRANSFORM_CHANGED, b, a, msg));
                ctx.reason(Risk.MEDIUM, "TRANSFORM_CHANGED", msg, b.step, a.step, b.range, a.range);
            }
        }
        for (Map.Entry<String, Col> e : pa.columns.entrySet()) {
            if (!pb.columns.containsKey(e.getKey())) {
                Col a = e.getValue();
                String target = a.owner + "." + a.column;
                columns.add(column(ColumnChangeKind.ADDED, null, a, a.sources.isEmpty()
                        ? Messages.t("diff.column.added", target)
                        : Messages.t("diff.column.addedFrom", target, String.join(", ", displays(a, a.sources)))));
                addedByOwner.computeIfAbsent(a.owner, k -> new ArrayList<>()).add(a.column);
                owners.putIfAbsent(a.owner, new Col[]{null, a});
            }
        }
        columnSetReasons(ctx, addedByOwner, owners, true);
        columnSetReasons(ctx, removedByOwner, owners, false);
        return new LineageChanges(columns, indirect(ctx, pb, pa));
    }

    private static void columnSetReasons(DiffContext ctx, Map<String, List<String>> byOwner, Map<String, Col[]> owners,
                                         boolean added) {
        byOwner.forEach((owner, cols) -> {
            Col[] c = owners.get(owner);
            Col any = c[0] != null ? c[0] : c[1];
            Risk level = TEMP.contains(any.ownerKind) ? Risk.LOW : Risk.MEDIUM;
            String msg = Messages.t(added ? "diff.columns.added" : "diff.columns.removed", owner, String.join(", ", cols));
            ctx.reason(level, added ? "COLUMNS_ADDED" : "COLUMNS_REMOVED", msg, c[0] == null ? null : c[0].step,
                    c[1] == null ? null : c[1].step, c[0] == null ? null : c[0].range, c[1] == null ? null : c[1].range);
        });
    }

    private static List<IndirectChange> indirect(DiffContext ctx, Profile pb, Profile pa) {
        List<IndirectChange> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(pb.rows.keySet());
        keys.addAll(pa.rows.keySet());
        for (String ownerKey : keys) {
            Rows rb = pb.rows.get(ownerKey);
            Rows ra = pa.rows.get(ownerKey);
            Rows any = ra != null ? ra : rb;
            Map<String, Item> ib = rb == null ? Map.of() : rb.items;
            Map<String, Item> ia = ra == null ? Map.of() : ra.items;
            boolean temp = TEMP.contains(any.ownerKind);
            for (Item i : ia.values()) {
                if (!ib.containsKey(i.role() + "|" + i.sourceKey())) {
                    String msg = Messages.t("diff.rows.now." + roleKey(i.role()), any.owner, i.source());
                    out.add(new IndirectChange(any.owner, ownerKey, i.role(), "ADDED", i.source(), msg, null, i.step(),
                            null, i.range()));
                    ctx.reason(temp ? Risk.LOW : Risk.MEDIUM, i.role() + "_ADDED", msg, null, i.step(), null, i.range());
                }
            }
            for (Item i : ib.values()) {
                if (!ia.containsKey(i.role() + "|" + i.sourceKey())) {
                    String msg = Messages.t("diff.rows.noLonger." + roleKey(i.role()), any.owner, i.source());
                    out.add(new IndirectChange(any.owner, ownerKey, i.role(), "REMOVED", i.source(), msg, i.step(), null,
                            i.range(), null));
                    ctx.reason(temp ? Risk.LOW : Risk.MEDIUM, i.role() + "_REMOVED", msg, i.step(), null, i.range(), null);
                }
            }
        }
        return out;
    }

    /** Message key suffix of a row role: FILTER, JOIN, or GROUP for anything else. */
    private static String roleKey(String role) {
        return switch (role) {
            case "FILTER", "JOIN" -> role;
            default -> "GROUP";
        };
    }

    private static String sourcesMessage(Col b, Col a) {
        Set<String> added = new TreeSet<>(a.sources);
        added.removeAll(b.sources);
        Set<String> removed = new TreeSet<>(b.sources);
        removed.removeAll(a.sources);
        String target = a.owner + "." + a.column;
        if (!added.isEmpty() && !removed.isEmpty()) {
            return Messages.t("diff.column.sourcesReplaced", target, String.join(", ", displays(a, added)),
                    String.join(", ", displays(b, removed)));
        }
        if (!added.isEmpty()) {
            return Messages.t("diff.column.sourcesAdded", target, String.join(", ", displays(a, added)));
        }
        return Messages.t("diff.column.sourcesRemoved", target, String.join(", ", displays(b, removed)));
    }

    private static List<String> displays(Col c, Set<String> keys) {
        return keys.stream().map(k -> c.display.getOrDefault(k, k)).toList();
    }

    private static ColumnChange column(ColumnChangeKind kind, Col b, Col a, String msg) {
        Col any = a != null ? a : b;
        List<String> bs = b == null ? List.of() : displays(b, b.sources);
        List<String> as = a == null ? List.of() : displays(a, a.sources);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        if (a != null && b != null) {
            a.sources.stream().filter(s -> !b.sources.contains(s)).forEach(s -> added.add(a.display.getOrDefault(s, s)));
            b.sources.stream().filter(s -> !a.sources.contains(s)).forEach(s -> removed.add(b.display.getOrDefault(s, s)));
        }
        return new ColumnChange(kind, any.owner, any.ownerKey, any.column, any.ownerKind.name(), bs, as, added, removed,
                b == null ? List.of() : List.copyOf(b.transforms), a == null ? List.of() : List.copyOf(a.transforms),
                msg, b == null ? null : b.step, a == null ? null : a.step, b == null ? null : b.range,
                a == null ? null : a.range);
    }
}
