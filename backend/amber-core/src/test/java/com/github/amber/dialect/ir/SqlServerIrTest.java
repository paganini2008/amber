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
 * IR of a complex, mixed T-SQL batch script.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class SqlServerIrTest {

    private static Script script;

    @BeforeAll
    static void build() throws IOException {
        script = IrAssertions.build(DialectFrontends.get(Dialect.SQLSERVER), "/sql/sqlserver/load_complex.sql");
    }

    @Test
    void batchesAndStatementsAppearInOrder() {
        assertEquals(List.of(
                StatementKind.CLIENT_COMMAND, StatementKind.SESSION,
                StatementKind.ASSIGNMENT, StatementKind.IF, StatementKind.CREATE_TABLE, StatementKind.DECLARE,
                StatementKind.INSERT, StatementKind.INSERT, StatementKind.UPDATE, StatementKind.DELETE,
                StatementKind.SELECT_INTO, StatementKind.MERGE,
                StatementKind.CREATE_PROCEDURE, StatementKind.CREATE_VIEW,
                StatementKind.CALL, StatementKind.DROP), kinds(script.statements()));
    }

    @Test
    void tempTablesAndTableVariablesAreRecognized() {
        Statement create = first(script.statements(), StatementKind.CREATE_TABLE);
        assertEquals(List.of("CREATE TEMP_TABLE #SHIPMENTS"), writes(create));

        Statement declare = first(script.statements(), StatementKind.DECLARE);
        assertEquals(List.of("CREATE TABLE_VARIABLE @LATE"), writes(declare));

        Statement insertLate = IrAssertions.all(script.statements(), StatementKind.INSERT).get(1);
        assertEquals(List.of("INSERT TABLE_VARIABLE @LATE"), writes(insertLate));
        assertEquals(List.of("OPS.TRACKING"), reads(insertLate));
    }

    @Test
    void insertWithCtes() {
        Statement insert = first(script.statements(), StatementKind.INSERT);
        assertEquals(List.of("recent", "zones"), insert.ctes());
        assertEquals(List.of("INSERT TEMP_TABLE #SHIPMENTS"), writes(insert));
        assertEquals(List.of("OPS.SHIPMENT", "REF.DELIVERY_ZONE"), reads(insert));
    }

    @Test
    void aliasTargetsResolveToTheRealTable() {
        Statement update = first(script.statements(), StatementKind.UPDATE);
        assertEquals(List.of("UPDATE TEMP_TABLE #SHIPMENTS"), writes(update));
        assertEquals(List.of("REF.CARRIER"), reads(update));
        assertEquals("Update #shipments using ref.carrier", update.summary());

        Statement delete = first(script.statements(), StatementKind.DELETE);
        assertEquals(List.of("DELETE TEMP_TABLE #SHIPMENTS"), writes(delete));
        assertEquals(List.of(), reads(delete));
    }

    @Test
    void selectIntoCreatesATempTable() {
        Statement selectInto = first(script.statements(), StatementKind.SELECT_INTO);
        assertEquals(List.of("CREATE TEMP_TABLE #CARRIER_KPI"), writes(selectInto));
        assertEquals(List.of("#SHIPMENTS", "@LATE"), reads(selectInto));

        Statement merge = first(script.statements(), StatementKind.MERGE);
        assertEquals(List.of("MERGE TABLE DW.CARRIER_KPI"), writes(merge));
        assertEquals(List.of("#CARRIER_KPI"), reads(merge));
    }

    @Test
    void procedureStructure() {
        Statement proc = first(script.statements(), StatementKind.CREATE_PROCEDURE);
        assertEquals("DW.USP_REBUILD_ZONE_STATS", proc.name().key());
        Statement body = first(proc.children(), StatementKind.BLOCK);
        Statement tryBlock = first(body.children(), StatementKind.TRY);
        List<StatementKind> inTry = kinds(tryBlock.children());
        assertTrue(inTry.containsAll(List.of(StatementKind.TRANSACTION, StatementKind.CURSOR, StatementKind.LOOP,
                StatementKind.DYNAMIC_SQL, StatementKind.CALL)), inTry::toString);

        Statement loop = first(tryBlock.children(), StatementKind.LOOP);
        Statement ifStmt = first(first(loop.children(), StatementKind.BLOCK).children(), StatementKind.IF);
        assertEquals(List.of("DW.ZONE_STATS"), reads(ifStmt));
        assertEquals(List.of(StatementKind.UPDATE, StatementKind.INSERT), kinds(ifStmt.children()));

        Statement cursor = first(tryBlock.children(), StatementKind.CURSOR);
        assertEquals(List.of("DW.CARRIER_KPI"), reads(cursor));

        Statement logCall = tryBlock.children().stream().filter(s -> s.kind() == StatementKind.CALL).toList().get(tryBlock.children().stream().filter(s -> s.kind() == StatementKind.CALL).toList().size() - 1);
        assertEquals(List.of("EXECUTE ROUTINE DW.USP_LOG_ERROR"), writes(logCall));
    }

    @Test
    void dropListsEveryTable() {
        Statement drop = script.statements().get(script.statements().size() - 1);
        assertEquals(List.of("DROP TEMP_TABLE #SHIPMENTS", "DROP TEMP_TABLE #CARRIER_KPI"), writes(drop));
    }

    @Test
    void lifecycles() {
        List<ObjectLifecycle> lifecycles = Lifecycles.of(script);
        ObjectLifecycle shipments = lifecycle(lifecycles, "#SHIPMENTS");
        assertEquals(ObjectKind.TEMP_TABLE, shipments.kind());
        assertEquals(List.of(Access.DROP, Access.CREATE, Access.INSERT, Access.UPDATE, Access.DELETE, Access.READ,
                Access.DROP), accesses(shipments));
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, shipments.role());
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, lifecycle(lifecycles, "#CARRIER_KPI").role());
        assertEquals(ObjectLifecycle.Role.SOURCE, lifecycle(lifecycles, "OPS.SHIPMENT").role());
        assertEquals(ObjectKind.VIEW, lifecycle(lifecycles, "DW.V_CARRIER_SCORECARD").kind());
    }
}
