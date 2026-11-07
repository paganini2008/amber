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
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR of a MySQL script with temporary tables, multi-table DML and a stored procedure.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class MySqlIrTest {

    private static Script script;

    @BeforeAll
    static void build() throws IOException {
        script = IrAssertions.build(DialectFrontends.get(Dialect.MYSQL), "/sql/mysql/stats_complex.sql");
    }

    @Test
    void outline() {
        assertEquals(List.of(StatementKind.DROP, StatementKind.CREATE_TABLE_AS, StatementKind.UPDATE, StatementKind.DELETE,
                StatementKind.INSERT, StatementKind.CLIENT_COMMAND, StatementKind.CREATE_PROCEDURE,
                StatementKind.CLIENT_COMMAND, StatementKind.CALL, StatementKind.DROP), kinds(script.statements()));
        assertTrue(script.statements().get(1).range().start().line() == 3);
    }

    @Test
    void multiTableDmlTargetsOnlyTheFirstTable() {
        Statement ctas = first(script.statements(), StatementKind.CREATE_TABLE_AS);
        assertEquals(List.of("CREATE TEMP_TABLE TMP_SALES"), writes(ctas));
        assertEquals(List.of("RETAIL.SALE"), reads(ctas));

        Statement update = first(script.statements(), StatementKind.UPDATE);
        assertEquals(List.of("UPDATE TABLE RETAIL.STORE"), writes(update));
        assertEquals(List.of("TMP_SALES"), reads(update));

        Statement delete = first(script.statements(), StatementKind.DELETE);
        assertEquals(List.of("DELETE TABLE RETAIL.SALE_STAGE"), writes(delete));
        assertEquals(List.of("TMP_SALES"), reads(delete));
    }

    @Test
    void storedProcedure() {
        Statement proc = first(script.statements(), StatementKind.CREATE_PROCEDURE);
        assertEquals("RETAIL.FLAG_SLOW_STORES", proc.name().key());
        Statement body = first(proc.children(), StatementKind.BLOCK);
        assertEquals(List.of("RETAIL.STORE"), reads(first(body.children(), StatementKind.CURSOR)));
        Statement loop = first(body.children(), StatementKind.LOOP);
        assertEquals(List.of("INSERT TABLE RETAIL.STORE_FLAG"), writes(first(loop.children(), StatementKind.INSERT)));
        assertTrue(kinds(body.children()).contains(StatementKind.EXCEPTION_HANDLER));
    }

    @Test
    void lifecycle() {
        ObjectLifecycle tmp = IrAssertions.lifecycle(Lifecycles.of(script), "TMP_SALES");
        assertEquals(ObjectKind.TEMP_TABLE, tmp.kind());
        assertEquals(List.of(Access.DROP, Access.CREATE, Access.READ, Access.READ, Access.READ, Access.DROP), accesses(tmp));
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, tmp.role());
    }
}
