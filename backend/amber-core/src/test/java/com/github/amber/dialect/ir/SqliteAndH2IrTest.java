package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.DialectFrontends;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.github.amber.dialect.ir.IrAssertions.accesses;
import static com.github.amber.dialect.ir.IrAssertions.first;
import static com.github.amber.dialect.ir.IrAssertions.kinds;
import static com.github.amber.dialect.ir.IrAssertions.lifecycle;
import static com.github.amber.dialect.ir.IrAssertions.reads;
import static com.github.amber.dialect.ir.IrAssertions.writes;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IR of SQLite and H2 scripts.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class SqliteAndH2IrTest {

    @Test
    void sqlite() throws IOException {
        Script script = IrAssertions.build(DialectFrontends.get(Dialect.SQLITE), "/sql/sqlite/notes_complex.sql");
        assertEquals(List.of(StatementKind.CLIENT_COMMAND, StatementKind.TRANSACTION, StatementKind.CREATE_TABLE_AS,
                StatementKind.CREATE_INDEX, StatementKind.UPDATE, StatementKind.INSERT, StatementKind.DELETE,
                StatementKind.CREATE_TRIGGER, StatementKind.CREATE_VIEW, StatementKind.TRANSACTION, StatementKind.DROP),
                kinds(script.statements()));

        Statement ctas = first(script.statements(), StatementKind.CREATE_TABLE_AS);
        assertEquals(List.of("CREATE TEMP_TABLE TMP_ACTIVE"), writes(ctas));
        assertEquals(List.of("MAIN.NOTE", "MAIN.APP_USER"), reads(ctas));
        assertEquals(List.of("recent"), ctas.ctes());

        Statement delete = first(script.statements(), StatementKind.DELETE);
        assertEquals(List.of("DELETE TABLE MAIN.NOTE_DRAFT"), writes(delete));
        assertEquals(List.of("TMP_ACTIVE"), reads(delete));

        Statement trigger = first(script.statements(), StatementKind.CREATE_TRIGGER);
        assertEquals("note_audit", trigger.name().toString());
        assertEquals(List.of("NOTE"), reads(trigger));
        assertEquals(List.of("INSERT TABLE MAIN.NOTE_HISTORY"), writes(trigger.children().getFirst()));

        ObjectLifecycle tmp = lifecycle(Lifecycles.of(script), "TMP_ACTIVE");
        assertEquals(ObjectKind.TEMP_TABLE, tmp.kind());
        assertEquals(List.of(Access.CREATE, Access.ALTER, Access.READ, Access.READ, Access.READ, Access.DROP), accesses(tmp));
    }

    @Test
    void h2() throws IOException {
        Script script = IrAssertions.build(DialectFrontends.get(Dialect.H2), "/sql/h2/inventory_complex.sql");
        assertEquals(List.of(StatementKind.CREATE_TABLE, StatementKind.INSERT, StatementKind.CREATE_INDEX,
                StatementKind.MERGE, StatementKind.UPDATE, StatementKind.CREATE_TABLE_AS, StatementKind.CREATE_SEQUENCE,
                StatementKind.DROP), kinds(script.statements()));

        Statement insert = first(script.statements(), StatementKind.INSERT);
        assertEquals(List.of("INSERT TEMP_TABLE TMP_STOCK"), writes(insert));
        assertEquals(List.of("INV.MOVEMENT", "INV.PRODUCT"), reads(insert));
        assertEquals(List.of("moves"), insert.ctes());

        assertEquals(List.of("ALTER TEMP_TABLE TMP_STOCK"), writes(first(script.statements(), StatementKind.CREATE_INDEX)));
        Statement merge = first(script.statements(), StatementKind.MERGE);
        assertEquals(List.of("MERGE TABLE INV.STOCK"), writes(merge));
        assertEquals(List.of("TMP_STOCK"), reads(merge));
        Statement update = first(script.statements(), StatementKind.UPDATE);
        assertEquals(List.of("UPDATE TABLE INV.PRODUCT"), writes(update));
        assertEquals(List.of("TMP_STOCK"), reads(update));
        assertEquals(List.of("CREATE SEQUENCE INV.SNAPSHOT_SEQ"), writes(first(script.statements(), StatementKind.CREATE_SEQUENCE)));

        ObjectLifecycle tmp = lifecycle(Lifecycles.of(script), "TMP_STOCK");
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, tmp.role());
        assertEquals(List.of("DROP TEMP_TABLE TMP_STOCK"), writes(script.statements().getLast()));
    }
}
