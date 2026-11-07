package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.diagnostic.Severity;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.github.amber.dialect.ir.IrAssertions.first;
import static com.github.amber.dialect.ir.IrAssertions.kinds;
import static com.github.amber.dialect.ir.IrAssertions.lifecycle;
import static com.github.amber.dialect.ir.IrAssertions.reads;
import static com.github.amber.dialect.ir.IrAssertions.writes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PL/pgSQL and SQL function bodies and DO blocks.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class PlPgSqlIrTest {

    private static Script script;

    @BeforeAll
    static void build() throws IOException {
        script = IrAssertions.build(DialectFrontends.get(Dialect.POSTGRESQL), "/sql/postgresql/plpgsql_complex.sql");
    }

    private static Statement body(int index) {
        Statement fn = script.statements().get(index);
        return first(fn.children(), StatementKind.BLOCK);
    }

    @Test
    void functionBodyStructure() {
        Statement fn = script.statements().getFirst();
        assertEquals(StatementKind.CREATE_FUNCTION, fn.kind());
        Statement block = body(0);
        assertEquals(List.of(StatementKind.CURSOR, StatementKind.CREATE_TABLE, StatementKind.LOOP, StatementKind.SELECT,
                StatementKind.UPDATE, StatementKind.SELECT, StatementKind.DYNAMIC_SQL, StatementKind.RETURN,
                StatementKind.EXCEPTION_HANDLER), kinds(block.children()));

        Statement cursor = block.children().getFirst();
        assertEquals(List.of("SHOP.MEMBER", "SHOP.PURCHASE"), reads(cursor));

        Statement loop = first(block.children(), StatementKind.LOOP);
        Statement ifStmt = first(loop.children(), StatementKind.IF);
        assertEquals(List.of(StatementKind.INSERT, StatementKind.INSERT, StatementKind.OTHER), kinds(ifStmt.children()));
        assertEquals(List.of("INSERT TEMP_TABLE TMP_AWARD"), writes(ifStmt.children().getFirst()));
        assertTrue(kinds(loop.children()).contains(StatementKind.ASSIGNMENT));

        Statement handler = first(block.children(), StatementKind.EXCEPTION_HANDLER);
        assertEquals(List.of("INSERT TABLE LOYALTY.ERROR_LOG"), writes(handler.children().getFirst()));
    }

    @Test
    void selectIntoVariableIsNotATableAndPerformIsAQuery() {
        Statement block = body(0);
        Statement selectInto = block.children().get(3);
        assertEquals(StatementKind.SELECT, selectInto.kind());
        assertEquals(List.of(), writes(selectInto));
        assertEquals(List.of("TMP_AWARD"), reads(selectInto));

        Statement perform = block.children().get(5);
        assertEquals(StatementKind.SELECT, perform.kind());
        assertEquals(List.of(), writes(perform));
    }

    @Test
    void returnQueryAndProcedures() {
        Statement ret = first(body(1).children(), StatementKind.SELECT);
        assertEquals(List.of("LOYALTY.BALANCE"), reads(ret));
        assertEquals("Return query from loyalty.balance", ret.summary());

        Statement loop = first(body(2).children(), StatementKind.LOOP);
        assertEquals(List.of(StatementKind.DELETE, StatementKind.OTHER, StatementKind.OTHER, StatementKind.TRANSACTION),
                kinds(loop.children()));
        assertEquals(List.of("DELETE TABLE LOYALTY.LEDGER"), writes(loop.children().getFirst()));
    }

    @Test
    void sqlFunctionAndDoBlock() {
        Statement sqlFn = script.statements().get(3);
        assertEquals(List.of("SHOP.MEMBER"), reads(first(sqlFn.children(), StatementKind.SELECT)));

        Statement doBlock = script.statements().get(4);
        Statement inner = first(doBlock.children(), StatementKind.BLOCK);
        Statement ifStmt = first(inner.children(), StatementKind.IF);
        assertTrue(kinds(ifStmt.children()).contains(StatementKind.OTHER));
        Statement insert = first(inner.children(), StatementKind.INSERT);
        assertEquals(List.of("INSERT TABLE LOYALTY.RUN_LOG"), writes(insert));
    }

    @Test
    void bodyObjectsJoinTheScriptLifecycle() {
        List<ObjectLifecycle> lifecycles = Lifecycles.of(script);
        ObjectLifecycle award = lifecycle(lifecycles, "TMP_AWARD");
        assertEquals(ObjectKind.TEMP_TABLE, award.kind());
        assertEquals(Access.CREATE, award.events().getFirst().access());
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, award.role());
    }

    @Test
    void brokenBodyStatementIsAWarningNotAFailure() {
        DialectFrontend pg = DialectFrontends.get(Dialect.POSTGRESQL);
        ScriptParse parse = pg.parse(SourceText.of("""
                DO $$
                BEGIN
                  UPDATE t SET WHERE;
                  DELETE FROM u;
                END;
                $$;
                """));
        Script s = ScriptIrBuilder.build(pg, parse);
        Statement block = first(s.statements().getFirst().children(), StatementKind.BLOCK);
        assertEquals(Confidence.PARTIAL, block.children().getFirst().confidence());
        assertEquals(List.of("DELETE TABLE U"), writes(block.children().get(1)));
        assertTrue(s.diagnostics().stream().anyMatch(d -> d.severity() == Severity.WARNING
                && d.range().start().line() == 3), s.diagnostics()::toString);
    }
}
