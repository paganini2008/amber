package com.github.amber.dialect.split;

import com.github.amber.core.source.TextRange;

/**
 * One piece of a script as cut by a {@link ScriptSplitter}: a statement, a PL block, a batch, a
 * client command or inline data. The range excludes separators that belong to the client tool
 * ({@code /}, {@code GO}) but includes {@code ;}.
 *
 * @param index      0-based position in the script
 * @param terminator a client delimiter that ends the unit and is included in its range (MySQL
 *                   {@code $$} after {@code DELIMITER $$}, or {@code \G}); {@code null} otherwise
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record StatementUnit(int index, UnitKind kind, TextRange range, String terminator) {

    public StatementUnit(int index, UnitKind kind, TextRange range) {
        this(index, kind, range, null);
    }
}
