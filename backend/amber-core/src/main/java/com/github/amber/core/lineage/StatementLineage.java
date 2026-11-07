package com.github.amber.core.lineage;

import java.util.List;

/**
 * Column lineage of one statement: the columns it produces and every edge into them, including
 * edges through its CTEs and derived tables.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record StatementLineage(List<ColumnNode> outputs, List<ColumnEdge> edges) {

    public StatementLineage {
        outputs = List.copyOf(outputs);
        edges = List.copyOf(edges);
    }

    public static final StatementLineage EMPTY = new StatementLineage(List.of(), List.of());
}
