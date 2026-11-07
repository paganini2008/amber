package com.github.amber.core.lineage;

import com.github.amber.core.ir.Confidence;
import com.github.amber.core.source.TextRange;

/**
 * Data flowing from one column to another. Direct edges carry the value ({@link #transform}); indirect
 * edges ({@link #role}) point at the target's {@link ColumnNode#ROWS} node.
 *
 * @param range the expression or clause that creates the edge
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record ColumnEdge(ColumnNode from, ColumnNode to, Transform transform, IndirectRole role,
                         Confidence confidence, TextRange range) {

    public static ColumnEdge direct(ColumnNode from, ColumnNode to, Transform t, Confidence c, TextRange range) {
        return new ColumnEdge(from, to, t, null, c, range);
    }

    public static ColumnEdge indirect(ColumnNode from, ColumnNode to, IndirectRole role, Confidence c, TextRange range) {
        return new ColumnEdge(from, to, null, role, c, range);
    }

    public boolean isDirect() {
        return role == null;
    }
}
