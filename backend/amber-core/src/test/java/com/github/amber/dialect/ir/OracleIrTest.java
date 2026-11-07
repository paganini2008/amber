package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR of a complex, mixed Oracle batch script.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class OracleIrTest {

    private static Script script;

    @BeforeAll
    static void build() throws IOException {
        DialectFrontend oracle = DialectFrontends.get(Dialect.ORACLE);
        ScriptParse parse = oracle.parse(new SourceText("etl_complex.sql", resource("/sql/oracle/etl_complex.sql")));
        assertEquals(ScriptStatus.SUCCESS, parse.status(), () -> parse.diagnostics().toString());
        script = ScriptIrBuilder.build(oracle, parse);
    }

    @Test
    void outlineFollowsTheScript() {
        List<StatementKind> kinds = script.statements().stream().map(Statement::kind).toList();
        assertEquals(List.of(
                StatementKind.CLIENT_COMMAND, StatementKind.CLIENT_COMMAND, StatementKind.CLIENT_COMMAND,
                StatementKind.CLIENT_COMMAND, StatementKind.CLIENT_COMMAND,
                StatementKind.DROP, StatementKind.CREATE_TABLE, StatementKind.CREATE_INDEX,
                StatementKind.INSERT, StatementKind.UPDATE, StatementKind.MERGE, StatementKind.TRANSACTION,
                StatementKind.CREATE_PROCEDURE, StatementKind.CREATE_PACKAGE, StatementKind.CREATE_PACKAGE_BODY,
                StatementKind.BLOCK, StatementKind.CREATE_VIEW, StatementKind.CREATE_TABLE_AS,
                StatementKind.DELETE, StatementKind.TRUNCATE, StatementKind.DROP, StatementKind.TRANSACTION,
                StatementKind.CLIENT_COMMAND), kinds);
    }

    @Test
    void insertWithCtesHasTargetSourcesAndCtes() {
        Statement insert = top(StatementKind.INSERT);
        assertEquals(List.of("paid_orders", "cust_lvl"), insert.ctes());
        assertEquals(Map.of("TMP_ORDER", Access.INSERT), accessesOf(insert, Access.INSERT));
        assertEquals(List.of("SALES.ORDERS", "CRM.CUSTOMERS", "CRM.VIP_LIST"), sourcesOf(insert));
        assertTrue(insert.refs().stream().anyMatch(r -> r.kind() == ObjectKind.CTE && r.name().key().equals("PAID_ORDERS")));
        assertEquals(ObjectKind.TEMP_TABLE, insert.refs().stream()
                .filter(r -> r.access() == Access.INSERT).findFirst().orElseThrow().kind());
        assertEquals("Insert into tmp_order from sales.orders, crm.customers, crm.vip_list via 2 CTEs", insert.summary());
    }

    @Test
    void updateAndMergeSeparateTargetsFromSources() {
        Statement update = top(StatementKind.UPDATE);
        assertEquals(Map.of("TMP_ORDER", Access.UPDATE), accessesOf(update, Access.UPDATE));
        assertEquals(List.of("SALES.REFUNDS"), sourcesOf(update));

        Statement merge = top(StatementKind.MERGE);
        assertEquals(Map.of("DW.ORDER_FACT", Access.MERGE), accessesOf(merge, Access.MERGE));
        assertEquals(List.of("TMP_ORDER"), sourcesOf(merge));
        assertEquals("Merge into dw.order_fact from tmp_order", merge.summary());
    }

    @Test
    void procedureKeepsItsControlFlowAndStatements() {
        Statement proc = top(StatementKind.CREATE_PROCEDURE);
        assertEquals("DW.P_CALC_BONUS", proc.name().key());

        List<StatementKind> nested = proc.children().stream().map(Statement::kind).toList();
        assertTrue(nested.containsAll(List.of(StatementKind.CURSOR, StatementKind.LOOP, StatementKind.DYNAMIC_SQL,
                StatementKind.EXCEPTION_HANDLER)), nested::toString);

        Statement loop = child(proc, StatementKind.LOOP);
        Statement ifStmt = child(loop, StatementKind.IF);
        List<StatementKind> branches = ifStmt.children().stream().map(Statement::kind).toList();
        assertEquals(List.of(StatementKind.INSERT, StatementKind.UPDATE), branches);

        Statement cursor = child(proc, StatementKind.CURSOR);
        assertEquals(List.of("DW.ORDER_FACT"), sourcesOf(cursor));

        Statement handler = child(proc, StatementKind.EXCEPTION_HANDLER);
        assertTrue(handler.children().stream().anyMatch(s -> s.kind() == StatementKind.CALL
                && s.refs().stream().anyMatch(r -> r.access() == Access.EXECUTE
                && r.name().key().equals("DW.LOG_PKG.LOG_ERROR"))), handler.children()::toString);
    }

    @Test
    void packageBodyListsItsRoutines() {
        Statement body = top(StatementKind.CREATE_PACKAGE_BODY);
        assertEquals("DW.LOG_PKG", body.name().key());
        Statement routine = child(body, StatementKind.ROUTINE);
        assertEquals("LOG_ERROR", routine.name().key());
        assertEquals(Map.of("DW.ETL_LOG", Access.INSERT), accessesOf(child(routine, StatementKind.INSERT), Access.INSERT));
    }

    @Test
    void lifecycleOfTheTempTable() {
        List<ObjectLifecycle> lifecycles = Lifecycles.of(script);
        ObjectLifecycle tmp = lifecycles.stream().filter(l -> l.name().key().equals("TMP_ORDER")).findFirst().orElseThrow();
        assertEquals(ObjectKind.TEMP_TABLE, tmp.kind());
        assertEquals(List.of(Access.DROP, Access.CREATE, Access.ALTER, Access.INSERT, Access.UPDATE, Access.READ,
                Access.READ, Access.DROP), tmp.events().stream().map(ObjectLifecycle.Event::access).toList());
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, tmp.role());

        assertEquals(ObjectLifecycle.Role.SOURCE, role(lifecycles, "SALES.ORDERS"));
        assertEquals(ObjectLifecycle.Role.TARGET, role(lifecycles, "DW.BONUS_SNAPSHOT"));
        assertEquals(ObjectLifecycle.Role.INTERMEDIATE, role(lifecycles, "DW.ORDER_FACT"));
        assertEquals(ObjectKind.VIEW, lifecycles.stream()
                .filter(l -> l.name().key().equals("DW.V_BONUS_REPORT")).findFirst().orElseThrow().kind());
    }

    @Test
    void everyStatementHasASummary() {
        script.flatten().forEach(s -> assertTrue(!s.summary().isBlank(), s::toString));
        assertEquals("Create temp table tmp_order", top(StatementKind.CREATE_TABLE).summary());
        assertEquals("Create table dw.bonus_snapshot from dw.v_bonus_report", top(StatementKind.CREATE_TABLE_AS).summary());
    }

    // ----- helpers ------------------------------------------------------------------------------

    private static Statement top(StatementKind kind) {
        return script.statements().stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
    }

    private static Statement child(Statement parent, StatementKind kind) {
        Statement found = parent.children().stream().filter(s -> s.kind() == kind).findFirst().orElse(null);
        assertNotNull(found, () -> kind + " not in " + parent.children());
        return found;
    }

    private static Map<String, Access> accessesOf(Statement st, Access access) {
        return st.refs().stream().filter(r -> r.access() == access)
                .collect(Collectors.toMap(r -> r.name().key(), ObjectRef::access, (a, b) -> a));
    }

    private static List<String> sourcesOf(Statement st) {
        return st.refs().stream()
                .filter(r -> r.access() == Access.READ && r.kind() != ObjectKind.CTE)
                .map(r -> r.name().key()).distinct().toList();
    }

    private static ObjectLifecycle.Role role(List<ObjectLifecycle> lifecycles, String key) {
        return lifecycles.stream().filter(l -> l.name().key().equals(key)).findFirst().orElseThrow().role();
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = OracleIrTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
