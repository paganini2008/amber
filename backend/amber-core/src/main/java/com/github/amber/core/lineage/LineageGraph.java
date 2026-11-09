package com.github.amber.core.lineage;

import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Column lineage of a whole script: the union of every statement's edges. Because a table written by
 * one statement is the same node when a later statement reads it, paths run across statements and
 * through temp tables. {@link #upstream} answers "where does this value come from", {@link
 * #downstream} answers "what does this column affect" (impact analysis).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class LineageGraph {

    /** An edge with the statement that created it. */
    public record Edge(ColumnEdge edge, Statement statement) {
    }

    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, ColumnNode> nodes = new LinkedHashMap<>();
    private final Map<String, List<Edge>> into = new LinkedHashMap<>();
    private final Map<String, List<Edge>> outOf = new LinkedHashMap<>();

    public static LineageGraph of(Script script) {
        LineageGraph g = new LineageGraph();
        // Object kinds are final only after the whole script is read (a table created as TEMPORARY in
        // one statement is a temp table in all of them); lineage nodes take their kind from there.
        Map<String, ColumnNode.OwnerKind> kinds = new LinkedHashMap<>();
        for (Statement st : script.flatten()) {
            for (var ref : st.refs()) {
                ColumnNode.OwnerKind k = switch (ref.kind()) {
                    case TEMP_TABLE -> ColumnNode.OwnerKind.TEMP_TABLE;
                    case TABLE_VARIABLE -> ColumnNode.OwnerKind.TABLE_VARIABLE;
                    case VIEW -> ColumnNode.OwnerKind.VIEW;
                    case MATERIALIZED_VIEW -> ColumnNode.OwnerKind.MATERIALIZED_VIEW;
                    default -> null;
                };
                if (k != null) {
                    kinds.putIfAbsent(ref.name().key(), k);
                }
            }
        }
        for (Statement st : script.flatten()) {
            if (st.lineage() != null) {
                for (ColumnEdge e : st.lineage().edges()) {
                    g.add(new Edge(new ColumnEdge(kinded(e.from(), kinds), kinded(e.to(), kinds), e.transform(),
                            e.role(), e.confidence(), e.range()), st));
                }
            }
        }
        return g;
    }

    private static ColumnNode kinded(ColumnNode n, Map<String, ColumnNode.OwnerKind> kinds) {
        ColumnNode.OwnerKind k = n.ownerKind() == ColumnNode.OwnerKind.TABLE ? kinds.get(n.ownerKey()) : null;
        return k == null ? n : new ColumnNode(k, n.owner(), n.ownerKey(), n.column());
    }

    private void add(Edge e) {
        edges.add(e);
        nodes.putIfAbsent(e.edge().from().key(), e.edge().from());
        nodes.putIfAbsent(e.edge().to().key(), e.edge().to());
        into.computeIfAbsent(e.edge().to().key(), k -> new ArrayList<>()).add(e);
        outOf.computeIfAbsent(e.edge().from().key(), k -> new ArrayList<>()).add(e);
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<ColumnNode> nodes() {
        return new ArrayList<>(nodes.values());
    }

    public ColumnNode node(String key) {
        return nodes.get(key);
    }

    /**
     * Every edge on a path into {@code target}, following direct edges back to source tables. With
     * {@code includeIndirect}, filter/join/group edges into each visited owner's rows are added too.
     */
    public List<Edge> upstream(ColumnNode target, boolean includeIndirect) {
        return walk(target, true, includeIndirect);
    }

    /** Every edge on a path out of {@code source}: the columns and rows it affects. */
    public List<Edge> downstream(ColumnNode source, boolean includeIndirect) {
        return walk(source, false, includeIndirect);
    }

    private List<Edge> walk(ColumnNode start, boolean up, boolean includeIndirect) {
        Set<Edge> result = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start.key());
        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (!seen.add(key)) {
                continue;
            }
            for (Edge e : (up ? into : outOf).getOrDefault(key, List.of())) {
                if (!e.edge().isDirect() && !includeIndirect) {
                    continue;
                }
                result.add(e);
                queue.add((up ? e.edge().from() : e.edge().to()).key());
            }
            if (up && includeIndirect) {
                ColumnNode n = nodes.get(key);
                if (n != null && !n.isRows()) {
                    queue.add(n.ownerKey() + "." + ColumnNode.ROWS.toUpperCase(Locale.ROOT));
                }
            }
            if (!up && includeIndirect) {
                ColumnNode n = nodes.get(key);
                if (n != null && n.isRows()) {
                    for (ColumnNode other : nodes.values()) {
                        if (other.ownerKey().equals(n.ownerKey()) && !other.isRows()) {
                            queue.add(other.key());
                        }
                    }
                }
            }
        }
        List<Edge> ordered = new ArrayList<>();
        for (Edge e : edges) {
            if (result.contains(e)) {
                ordered.add(e);
            }
        }
        return ordered;
    }
}
