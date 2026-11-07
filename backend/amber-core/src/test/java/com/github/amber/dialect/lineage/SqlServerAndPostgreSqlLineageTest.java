package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.LineageGraph;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Column lineage of the T-SQL and PostgreSQL test scripts.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class SqlServerAndPostgreSqlLineageTest {

    private static Script build(Dialect dialect, String resource) throws IOException {
        DialectFrontend f = DialectFrontends.get(dialect);
        String sql = new String(SqlServerAndPostgreSqlLineageTest.class.getResourceAsStream(resource).readAllBytes(),
                StandardCharsets.UTF_8);
        return ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
    }

    private static Set<String> direct(Statement st) {
        return st.lineage().edges().stream().filter(ColumnEdge::isDirect)
                .map(e -> e.from() + " -> " + e.to() + " " + e.transform()).collect(Collectors.toSet());
    }

    private static Statement nth(Script s, StatementKind kind, int n) {
        return s.flatten().stream().filter(st -> st.kind() == kind).toList().get(n);
    }

    @Test
    void sqlServer() throws IOException {
        Script s = build(Dialect.SQLSERVER, "/sql/sqlserver/load_complex.sql");
        Set<String> insert = direct(nth(s, StatementKind.INSERT, 0));
        assertTrue(insert.containsAll(Set.of("recent.shipment_id -> #shipments.shipment_id PASS_THROUGH",
                "zones.zone -> #shipments.zone PASS_THROUGH",
                "ops.shipment.shipment_id -> recent.shipment_id PASS_THROUGH")), insert::toString);

        assertTrue(direct(nth(s, StatementKind.UPDATE, 0)).contains(
                "ref.carrier.default_weight_kg -> #shipments.weight_kg EXPRESSION"));
        assertTrue(direct(nth(s, StatementKind.SELECT_INTO, 0)).contains(
                "@late.days_late -> #carrier_kpi.days_late AGGREGATE"));
        assertEquals(3, direct(nth(s, StatementKind.MERGE, 0)).size(), "MERGE UPDATE and INSERT edges are deduplicated");
        assertTrue(direct(nth(s, StatementKind.CREATE_VIEW, 0)).contains(
                "ref.carrier.carrier_name -> dw.v_carrier_scorecard.carrier_name PASS_THROUGH"));

        LineageGraph g = LineageGraph.of(s);
        ColumnNode kpiDaysLate = g.nodes().stream().filter(n -> n.toString().equals("dw.carrier_kpi.days_late"))
                .findFirst().orElseThrow();
        Set<String> sources = g.upstream(kpiDaysLate, false).stream().map(e -> e.edge().from().toString())
                .collect(Collectors.toSet());
        assertTrue(sources.containsAll(Set.of("#carrier_kpi.days_late", "@late.days_late", "ops.tracking.delivered_on",
                "ops.tracking.promised_on")), sources::toString);
    }

    @Test
    void postgreSql() throws IOException {
        Script s = build(Dialect.POSTGRESQL, "/sql/postgresql/refresh_complex.sql");
        assertTrue(direct(nth(s, StatementKind.INSERT, 0)).contains(
                "voided.invoice_id -> audit.voided_invoice.invoice_id PASS_THROUGH"));
        assertEquals(Set.of("tmp_invoice_line.amount_cents -> tmp_invoice_line.amount_cents EXPRESSION",
                "billing.discount.discount_cents -> tmp_invoice_line.amount_cents EXPRESSION"),
                direct(nth(s, StatementKind.UPDATE, 0)));

        LineageGraph g = LineageGraph.of(s);
        ColumnNode mv = g.nodes().stream().filter(n -> n.toString().equals("reporting.mv_plan_revenue.revenue_cents"))
                .findFirst().orElseThrow();
        Set<String> sources = g.upstream(mv, false).stream().map(e -> e.edge().from().toString())
                .collect(Collectors.toSet());
        assertTrue(sources.containsAll(Set.of("reporting.account_revenue.revenue_cents",
                "tmp_account_revenue.revenue_cents", "tmp_invoice_line.amount_cents", "billing.invoice_line.amount_cents",
                "billing.discount.discount_cents")), sources::toString);

        Script fn = build(Dialect.POSTGRESQL, "/sql/postgresql/plpgsql_complex.sql");
        Statement update = fn.flatten().stream().filter(st -> st.kind() == StatementKind.UPDATE).findFirst().orElseThrow();
        assertTrue(direct(update).contains("tmp_award.points -> loyalty.balance.points EXPRESSION"));
    }

    private static Set<String> edges(Dialect d, String sql) {
        DialectFrontend f = DialectFrontends.get(d);
        Script s = ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
        return LineageGraph.of(s).edges().stream().map(LineageGraph.Edge::edge).filter(ColumnEdge::isDirect)
                .map(e -> e.from() + " -> " + e.to() + " " + e.transform()).collect(Collectors.toSet());
    }

    @Test
    void aliasesAtTheEdgeOfTheSelectItem() {
        Set<String> pg = edges(Dialect.POSTGRESQL, """
                create temp table t as select o.amount * 1.1 as gross, o.id from shop.orders o;
                with pc as (select p.id, sum(p.gross) as total from t p group by p.id) select total from pc;
                """);
        assertTrue(pg.containsAll(Set.of("shop.orders.amount -> t.gross EXPRESSION",
                "t.gross -> pc.total AGGREGATE", "pc.total -> result.total PASS_THROUGH")), pg::toString);

        Set<String> tsql = edges(Dialect.SQLSERVER, "select gross = o.amount * 1.1, o.id as order_id into #t from shop.orders o;");
        assertTrue(tsql.containsAll(Set.of("shop.orders.amount -> #t.gross EXPRESSION",
                "shop.orders.id -> #t.order_id PASS_THROUGH")), tsql::toString);
    }
}
