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
import static com.github.amber.dialect.ir.IrAssertions.reads;
import static com.github.amber.dialect.ir.IrAssertions.writes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR of a MariaDB script (same job as the MySQL test plus a sequence and a top-level CTE).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class MariaDbIrTest {

    private static Script script;

    @BeforeAll
    static void build() throws IOException {
        script = IrAssertions.build(DialectFrontends.get(Dialect.MARIADB), "/sql/mariadb/stats_complex.sql");
    }

    @Test
    void outline() {
        assertEquals(List.of(StatementKind.CREATE_SEQUENCE, StatementKind.DROP, StatementKind.CREATE_TABLE_AS,
                StatementKind.UPDATE, StatementKind.DELETE, StatementKind.INSERT, StatementKind.CLIENT_COMMAND,
                StatementKind.CREATE_PROCEDURE, StatementKind.CLIENT_COMMAND, StatementKind.CALL, StatementKind.DROP,
                StatementKind.SELECT), kinds(script.statements()));
    }

    @Test
    void targetsAndSources() {
        assertEquals(List.of("CREATE SEQUENCE RETAIL.BATCH_SEQ"), writes(script.statements().get(0)));
        Statement ctas = first(script.statements(), StatementKind.CREATE_TABLE_AS);
        assertEquals(List.of("CREATE TEMP_TABLE TMP_SALES"), writes(ctas));
        assertEquals(List.of("RETAIL.SALE"), reads(ctas));
        assertEquals(List.of("UPDATE TABLE RETAIL.STORE"), writes(first(script.statements(), StatementKind.UPDATE)));
        assertEquals(List.of("DELETE TABLE RETAIL.SALE_STAGE"), writes(first(script.statements(), StatementKind.DELETE)));

        Statement cte = script.statements().get(script.statements().size() - 1);
        assertEquals(List.of("ranked"), cte.ctes());
        assertEquals(List.of("RETAIL.STORE"), reads(cte));
    }

    @Test
    void procedureAndLifecycle() {
        Statement proc = first(script.statements(), StatementKind.CREATE_PROCEDURE);
        assertEquals("RETAIL.FLAG_SLOW_STORES", proc.name().key());
        Statement body = first(proc.children(), StatementKind.BLOCK);
        assertTrue(kinds(body.children()).containsAll(List.of(StatementKind.CURSOR, StatementKind.EXCEPTION_HANDLER,
                StatementKind.LOOP)), () -> kinds(body.children()).toString());

        ObjectLifecycle tmp = IrAssertions.lifecycle(Lifecycles.of(script), "TMP_SALES");
        assertEquals(ObjectKind.TEMP_TABLE, tmp.kind());
        assertEquals(Access.CREATE, accesses(tmp).get(1));
    }
}
