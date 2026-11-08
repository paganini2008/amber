package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.IndirectRole;
import com.github.amber.core.lineage.LineageGraph;
import com.github.amber.core.lineage.Transform;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Column lineage of the complex Oracle script, per statement and across the script.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class OracleLineageTest {

    private static Script script;
    private static LineageGraph graph;

    @BeforeAll
    static void build() throws IOException {
        DialectFrontend oracle = DialectFrontends.get(Dialect.ORACLE);
        String sql = new String(OracleLineageTest.class.getResourceAsStream("/sql/oracle/etl_complex.sql").readAllBytes(),
                StandardCharsets.UTF_8);
        script = ScriptIrBuilder.build(oracle, oracle.parse(SourceText.of(sql)));
        graph = LineageGraph.of(script);
    }

    private static Statement top(StatementKind kind) {
        return script.statements().stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
    }

    /** "from -> to TRANSFORM" lines for direct edges into {@code toPrefix}. */
    private static Set<String> direct(Statement st, String toPrefix) {
        return st.lineage().edges().stream().filter(ColumnEdge::isDirect)
                .filter(e -> e.to().toString().startsWith(toPrefix))
                .map(e -> e.from() + " -> " + e.to() + " " + e.transform()).collect(Collectors.toSet());
    }

    @Test
    void insertThroughCtes() {
        Statement insert = top(StatementKind.INSERT);
        assertEquals(List.of("order_id", "cust_id", "region", "amount", "status"),
                insert.lineage().outputs().stream().map(ColumnNode::column).toList());
        assertEquals(Set.of(
                "paid_orders.order_id -> tmp_order.order_id PASS_THROUGH",
                "paid_orders.cust_id -> tmp_order.cust_id PASS_THROUGH",
                "cust_lvl.region -> tmp_order.region PASS_THROUGH",
                "paid_orders.amount -> tmp_order.amount PASS_THROUGH"), direct(insert, "tmp_order."));
        assertEquals(Set.of(
                "crm.customers.region -> cust_lvl.rnk WINDOW",
                "crm.customers.credit_score -> cust_lvl.rnk WINDOW",
                "crm.customers.cust_id -> cust_lvl.cust_id PASS_THROUGH",
                "crm.customers.region -> cust_lvl.region PASS_THROUGH"), direct(insert, "cust_lvl."));
        assertTrue(insert.lineage().edges().stream().anyMatch(e -> !e.isDirect() && e.role() == IndirectRole.JOIN
                && e.to().toString().equals("tmp_order.*rows*")));
        assertFalse(insert.lineage().edges().stream().anyMatch(e -> e.from().column().startsWith("&")),
                "substitution variables are not columns");
    }

    @Test
    void mergeThroughDerivedTable() {
        Statement merge = top(StatementKind.MERGE);
        assertEquals(List.of("amount", "region", "order_id", "cust_id"),
                merge.lineage().outputs().stream().map(ColumnNode::column).toList());
        assertTrue(direct(merge, "dw.order_fact.").contains("s.amount -> dw.order_fact.amount PASS_THROUGH"));
        assertTrue(direct(merge, "s.").contains("tmp_order.amount -> s.amount PASS_THROUGH"));
    }

    @Test
    void scalarSubqueryInUpdate() {
        Statement update = top(StatementKind.UPDATE);
        Set<String> edges = direct(update, "tmp_order.amount");
        assertTrue(edges.contains("sales.refunds.refund_amt -> tmp_order.amount AGGREGATE"), edges::toString);
        assertFalse(edges.stream().anyMatch(e -> e.startsWith("sales.refunds.order_id")), edges::toString);
    }

    @Test
    void starExpandsFromTheScriptsOwnView() {
        Statement ctas = top(StatementKind.CREATE_TABLE_AS);
        assertEquals(List.of("cust_id", "display_name", "bonus"),
                ctas.lineage().outputs().stream().map(ColumnNode::column).toList());
    }

    @Test
    void traceAcrossStatements() {
        ColumnNode snapshotBonus = graph.nodes().stream()
                .filter(n -> n.toString().equals("dw.bonus_snapshot.bonus")).findFirst().orElseThrow();
        Set<String> path = graph.upstream(snapshotBonus, false).stream()
                .map(e -> e.edge().from() + " -> " + e.edge().to()).collect(Collectors.toSet());
        assertTrue(path.containsAll(Set.of("dw.v_bonus_report.bonus -> dw.bonus_snapshot.bonus",
                "dw.bonus.bonus -> dw.v_bonus_report.bonus",
                "dw.bonus.bonus -> dw.bonus.bonus",
                // FOR r IN c_fact LOOP … r.total: the cursor's column, back to the orders
                "result.total -> dw.bonus.bonus",
                "dw.order_fact.amount -> result.total",
                "sales.orders.amount -> paid_orders.amount")), path::toString);

        ColumnNode ordersAmount = graph.nodes().stream()
                .filter(n -> n.toString().equals("sales.orders.amount")).findFirst().orElseThrow();
        Set<String> impact = graph.downstream(ordersAmount, false).stream()
                .map(e -> e.edge().to().toString()).collect(Collectors.toSet());
        assertTrue(impact.containsAll(Set.of("paid_orders.amount", "tmp_order.amount", "s.amount",
                "dw.order_fact.amount")), impact::toString);
        assertTrue(graph.downstream(ordersAmount, true).size() >= graph.downstream(ordersAmount, false).size());
        assertEquals(Transform.AGGREGATE, top(StatementKind.UPDATE).lineage().edges().stream()
                .filter(e -> e.isDirect() && e.from().column().equals("refund_amt")).findFirst().orElseThrow().transform());
    }

    @Test
    void qualifiedFunctionCallsAreNotColumns() {
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        Script s = ScriptIrBuilder.build(f, f.parse(SourceText.of(
                "insert into t (a, b) select dw.f_total(x.d), pkg.util.fmt(x.e) from x;")));
        var edges = com.github.amber.core.lineage.LineageGraph.of(s).edges().stream()
                .map(e -> e.edge().from() + " -> " + e.edge().to()).toList();
        assertTrue(edges.stream().noneMatch(e -> e.startsWith("x.dw") || e.startsWith("x.pkg") || e.contains(".dw ")), edges::toString);
        assertTrue(edges.contains("x.d -> t.a") && edges.contains("x.e -> t.b"), edges::toString);
    }
}
