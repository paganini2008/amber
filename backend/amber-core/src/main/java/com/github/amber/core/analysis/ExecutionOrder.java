package com.github.amber.core.analysis;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.QueryBlock;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.TextRange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The logical order in which a statement is processed, built from its query structure: CTEs first,
 * then for each query block FROM (with its derived tables) → WHERE (with its subqueries) → GROUP BY →
 * HAVING → SELECT (with scalar subqueries) → DISTINCT → ORDER BY → LIMIT, set operations after their
 * branches, and the write of an INSERT / UPDATE / MERGE / CREATE … AS last. This is the order the SQL
 * standard defines for reading a query, not the database's execution plan; nothing is run.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class ExecutionOrder {

    /**
     * One step: {@code block} is the {@link QueryBlock#id()} it belongs to (-1 for the final write),
     * {@code depth} the nesting level, {@code phase} e.g. {@code FROM}, {@code UNION ALL}, {@code CTE},
     * {@code WRITE}; {@code detail} a short description; {@code range} the source to highlight.
     */
    public record Step(int step, int block, int depth, String phase, String detail, TextRange range) {
    }

    private final List<Step> steps = new ArrayList<>();

    private ExecutionOrder() {
    }

    /** Numbers the statement's structure boxes and returns its steps; empty when it has no query. */
    public static List<Step> of(Statement st) {
        if (st.structure().isEmpty()) {
            return List.of();
        }
        int[] next = {0};
        st.structure().forEach(b -> number(b, next));
        ExecutionOrder order = new ExecutionOrder();
        List<QueryBlock> top = new ArrayList<>(st.structure());
        // CTEs are ready before the main query; filter subqueries of an UPDATE/DELETE run before SET ones.
        top.sort(Comparator.comparingInt(ExecutionOrder::topRank));
        for (QueryBlock b : top) {
            order.block(b, 0);
        }
        ObjectRef target = writeTarget(st);
        if (target != null) {
            order.add(-1, 0, "WRITE", Messages.t(writeKey(st.kind(), target.access()), target.name()), st.range());
        }
        return List.copyOf(order.steps);
    }

    private static void number(QueryBlock b, int[] next) {
        b.setId(next[0]++);
        b.children().forEach(c -> number(c, next));
    }

    private static int topRank(QueryBlock b) {
        if (b.kind() == QueryBlock.Kind.CTE) {
            return 0;
        }
        String role = b.role() == null ? "" : b.role();
        return role.startsWith("WHERE") ? 1 : role.startsWith("SET") ? 2 : 3;
    }

    private void block(QueryBlock b, int depth) {
        switch (b.kind()) {
            case SET -> {
                for (QueryBlock c : b.children()) {
                    block(c, depth + 1);
                }
                add(b.id(), depth, b.name(), Messages.t("summary.order.combine", b.children().size()), b.range());
                for (QueryBlock.Phase p : b.phases()) {
                    add(b.id(), depth, p.name(), null, p.range());
                }
            }
            case CTE -> {
                query(b, depth);
                add(b.id(), depth, "CTE", Messages.t("summary.order.ready", b.name()), b.range());
            }
            default -> query(b, depth);
        }
    }

    private void query(QueryBlock b, int depth) {
        Map<String, List<QueryBlock>> byPhase = new LinkedHashMap<>();
        for (QueryBlock c : b.children()) {
            byPhase.computeIfAbsent(phaseOf(c), k -> new ArrayList<>()).add(c);
        }
        List<String> present = b.phases().stream().map(QueryBlock.Phase::name).toList();
        // Children whose phase the block does not show (e.g. a query without FROM) run first.
        byPhase.forEach((phase, kids) -> {
            if (!present.contains(phase)) {
                kids.forEach(k -> block(k, depth + 1));
            }
        });
        if (b.kind() == QueryBlock.Kind.SET || b.phases().isEmpty() && !b.children().isEmpty()) {
            return;
        }
        for (QueryBlock.Phase p : b.phases()) {
            for (QueryBlock k : byPhase.getOrDefault(p.name(), List.of())) {
                block(k, depth + 1);
            }
            add(b.id(), depth, p.name(), detail(b, p.name()), p.range());
            byPhase.remove(p.name());
        }
    }

    /** The phase of the parent block during which a nested box is evaluated. */
    private static String phaseOf(QueryBlock c) {
        if (c.kind() == QueryBlock.Kind.DERIVED) {
            return "FROM";
        }
        String role = c.role() == null ? "" : c.role().toUpperCase(Locale.ROOT);
        if (role.startsWith("WHERE")) {
            return "WHERE";
        }
        if (role.startsWith("HAVING")) {
            return "HAVING";
        }
        if (role.startsWith("ON") || role.startsWith("FROM")) {
            return "FROM";
        }
        if (role.startsWith("QUALIFY")) {
            return "QUALIFY";
        }
        return "SELECT";
    }

    private static String detail(QueryBlock b, String phase) {
        return switch (phase) {
            case "FROM" -> b.sources().isEmpty() ? null : String.join(", ", b.sources().stream()
                    .map(s -> s.alias() == null ? s.name() : s.name() + " " + s.alias()).toList());
            case "SELECT" -> b.outputs().isEmpty() ? null
                    : Messages.t(b.outputs().size() == 1 ? "summary.order.columns.one" : "summary.order.columns.other",
                            b.outputs().size());
            default -> null;
        };
    }

    private void add(int block, int depth, String phase, String detail, TextRange range) {
        steps.add(new Step(steps.size() + 1, block, depth, phase, detail, range));
    }

    private static ObjectRef writeTarget(Statement st) {
        for (ObjectRef r : st.refs()) {
            if (r.access() == Access.INSERT || r.access() == Access.UPDATE || r.access() == Access.DELETE
                    || r.access() == Access.MERGE || (r.access() == Access.CREATE && st.kind() != StatementKind.SELECT)) {
                return r;
            }
        }
        return null;
    }

    /** Key of the sentence of the final write step ({@code {0}} = the target). */
    private static String writeKey(StatementKind kind, Access access) {
        return switch (access) {
            case INSERT, UPDATE, DELETE, MERGE -> "summary.order.write." + access.name();
            default -> kind == StatementKind.CREATE_VIEW || kind == StatementKind.CREATE_MATERIALIZED_VIEW
                    ? "summary.order.write.view" : "summary.order.write.create";
        };
    }
}
