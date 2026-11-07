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
import static com.github.amber.dialect.ir.IrAssertions.all;
import static com.github.amber.dialect.ir.IrAssertions.first;
import static com.github.amber.dialect.ir.IrAssertions.kinds;
import static com.github.amber.dialect.ir.IrAssertions.lifecycle;
import static com.github.amber.dialect.ir.IrAssertions.reads;
import static com.github.amber.dialect.ir.IrAssertions.writes;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IR of a complex PostgreSQL refresh script.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class PostgreSqlIrTest {

    private static Script script;

    @BeforeAll
    static void build() throws IOException {
        script = IrAssertions.build(DialectFrontends.get(Dialect.POSTGRESQL), "/sql/postgresql/refresh_complex.sql");
    }

    @Test
    void outline() {
        assertEquals(List.of(
                StatementKind.CLIENT_COMMAND, StatementKind.SESSION, StatementKind.TRANSACTION,
                StatementKind.CREATE_TABLE_AS, StatementKind.CREATE_INDEX, StatementKind.INSERT, StatementKind.UPDATE,
                StatementKind.SELECT_INTO, StatementKind.INSERT, StatementKind.TRANSACTION,
                StatementKind.CREATE_MATERIALIZED_VIEW, StatementKind.CREATE_FUNCTION, StatementKind.CALL,
                StatementKind.TRUNCATE, StatementKind.DROP, StatementKind.DROP), kinds(script.statements()));
    }

    @Test
    void tempTablesAndCtas() {
        Statement ctas = first(script.statements(), StatementKind.CREATE_TABLE_AS);
        assertEquals(List.of("CREATE TEMP_TABLE TMP_INVOICE_LINE"), writes(ctas));
        assertEquals(List.of("BILLING.INVOICE_LINE", "BILLING.INVOICE"), reads(ctas));

        Statement selectInto = first(script.statements(), StatementKind.SELECT_INTO);
        assertEquals(List.of("CREATE TEMP_TABLE TMP_ACCOUNT_REVENUE"), writes(selectInto));
        assertEquals(List.of("TMP_INVOICE_LINE"), reads(selectInto));
    }

    @Test
    void dataModifyingCteKeepsBothTargets() {
        Statement insert = first(script.statements(), StatementKind.INSERT);
        assertEquals(List.of("voided"), insert.ctes());
        assertEquals(List.of("DELETE TEMP_TABLE TMP_INVOICE_LINE", "INSERT TABLE AUDIT.VOIDED_INVOICE"), writes(insert));
        assertEquals(List.of("BILLING.INVOICE_VOID"), reads(insert));
    }

    @Test
    void upsertAndCalls() {
        Statement upsert = all(script.statements(), StatementKind.INSERT).get(1);
        assertEquals(List.of("INSERT TABLE REPORTING.ACCOUNT_REVENUE"), writes(upsert));
        assertEquals(List.of("TMP_ACCOUNT_REVENUE"), reads(upsert));

        Statement call = first(script.statements(), StatementKind.CALL);
        assertEquals(List.of("EXECUTE ROUTINE REPORTING.ARCHIVE_OLD_REVENUE"), writes(call));

        Statement function = first(script.statements(), StatementKind.CREATE_FUNCTION);
        assertEquals("REPORTING.REFRESH_PLAN_REVENUE", function.name().key());
    }

    @Test
    void dropKinds() {
        List<Statement> drops = all(script.statements(), StatementKind.DROP);
        assertEquals(List.of("DROP TEMP_TABLE TMP_ACCOUNT_REVENUE", "DROP TABLE STAGING.OLD_SNAPSHOT"), writes(drops.get(0)));
        assertEquals(List.of("DROP VIEW REPORTING.V_LEGACY_REVENUE"), writes(drops.get(1)));
    }

    @Test
    void lifecycles() {
        List<ObjectLifecycle> lifecycles = Lifecycles.of(script);
        ObjectLifecycle lines = lifecycle(lifecycles, "TMP_INVOICE_LINE");
        assertEquals(ObjectKind.TEMP_TABLE, lines.kind());
        assertEquals(List.of(Access.CREATE, Access.ALTER, Access.DELETE, Access.UPDATE, Access.READ), accesses(lines));
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, lines.role());
        assertEquals(ObjectLifecycle.Role.TARGET, lifecycle(lifecycles, "AUDIT.VOIDED_INVOICE").role());
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, lifecycle(lifecycles, "REPORTING.ACCOUNT_REVENUE").role());
        assertEquals(ObjectLifecycle.Role.SOURCE, lifecycle(lifecycles, "BILLING.INVOICE").role());
    }
}
