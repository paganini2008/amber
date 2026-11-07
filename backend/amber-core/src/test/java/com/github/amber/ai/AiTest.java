package com.github.amber.ai;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Masking round trip, context content and the structural check of AI suggestions.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class AiTest {

    private static final String SCRIPT = """
            insert into sales.order_total (customer_id, total)
            select o.customer_id, sum(o.amount)
              from sales.orders o
             where o.status = 'PAID'
               and o.customer_id in (select c.id from crm.customer c where c.region = 'NORTH')
             group by o.customer_id;
            select * from sales.order_total union select * from sales.order_total;
            """;

    private static Script script(Dialect dialect, String sql) {
        DialectFrontend f = DialectFrontends.get(dialect);
        return ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
    }

    @Test
    void maskingRoundTrip() {
        Masking m = new Masking(Dialect.ORACLE);
        m.object("sales");
        m.object("orders");
        m.column("amount");
        m.column("orders");                       // already an object: stays an object alias
        String masked = m.mask("select \"AMOUNT\", amount from sales.orders where note = 'secret' or note = 'secret'");
        assertEquals("select C1, C1 from T1.T2 where note = 'S1' or note = 'S1'", masked);
        assertFalse(masked.contains("secret"));
        Map<String, String> mapping = m.mapping();
        assertEquals("select amount, amount from sales.orders where note = 'secret' or note = 'secret'",
                Masking.unmask(Dialect.ORACLE, masked, mapping));
        assertEquals("select 1", Masking.unmask(Dialect.ORACLE, "select 1", Map.of()));
    }

    @Test
    void contextHoldsStructureFindingsAndMask() {
        Script s = script(Dialect.ORACLE, SCRIPT);
        List<Statement> steps = Lifecycles.steps(s);
        var findings = Findings.of(s, Catalog.of(List.of()));
        AiContextBuilder.Context c = AiContextBuilder.build(s, steps, List.of(), findings, Catalog.of(List.of()),
                6000, true);
        assertTrue(c.system().contains("JSON"));
        assertEquals(List.of(0, 1), c.statements());
        assertTrue(c.trimmed().isEmpty());
        assertFalse(c.user().toLowerCase().contains("orders"), c.user());
        assertFalse(c.user().contains("NORTH"), c.user());
        assertTrue(c.user().contains("AMB-S009"), c.user());
        assertTrue(c.mask().containsValue("orders"), c.mask()::toString);
        assertTrue(c.tokenEstimate() > 0);

        AiContextBuilder.Context plain = AiContextBuilder.build(s, steps, List.of(1), findings,
                Catalog.of(List.of()), 6000, false);
        assertTrue(plain.user().contains("order_total"), plain.user());
        assertTrue(plain.mask().isEmpty());
    }

    @Test
    void tinyBudgetTrimsStatements() {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sql.append("insert into t").append(i).append(" select a, b, c, d from s").append(i)
                    .append(" where a > ").append(i).append(";\n");
        }
        Script s = script(Dialect.POSTGRESQL, sql.toString());
        var c = AiContextBuilder.build(s, Lifecycles.steps(s), List.of(), List.of(), Catalog.of(List.of()),
                100, false);
        assertFalse(c.trimmed().isEmpty());
        assertTrue(c.statements().size() < 60);
    }

    @Test
    void checkLabelsSuggestions() {
        String original = "select o.customer_id, sum(o.amount) total from orders o "
                + "where o.customer_id in (select c.id from customer c) group by o.customer_id";
        Catalog catalog = Catalog.of(List.of());

        var same = AiCheck.check(Dialect.POSTGRESQL, original,
                "select o.customer_id, sum(o.amount) total from orders o "
                        + "where exists (select 1 from customer c where c.id = o.customer_id) group by o.customer_id",
                Map.of(), catalog);
        assertEquals(AiCheck.Label.STRUCTURE_PRESERVED, same.label(), same.differences()::toString);

        var changed = AiCheck.check(Dialect.POSTGRESQL, original,
                "select o.customer_id, max(o.amount) total, o.status from orders o group by o.customer_id, o.status",
                Map.of(), catalog);
        assertEquals(AiCheck.Label.STRUCTURE_CHANGED, changed.label());
        assertFalse(changed.differences().isEmpty());

        var invalid = AiCheck.check(Dialect.POSTGRESQL, original, "select from where", Map.of(), catalog);
        assertEquals(AiCheck.Label.INVALID_SQL, invalid.label());
        assertFalse(invalid.diagnostics().isEmpty());

        var unmasked = AiCheck.check(Dialect.POSTGRESQL, "select a from orders", "select C1 from T1",
                Map.of("T1", "orders", "C1", "a"), catalog);
        assertEquals("select a from orders", unmasked.sql());
        assertEquals(AiCheck.Label.STRUCTURE_PRESERVED, unmasked.label());
    }

    @Test
    void droppingTheSortBeforeARowLimitIsAStructureChange() {
        String original = """
                SELECT * FROM (SELECT m.member_id, f.amount FROM lib.members m JOIN lib.member_fees f ON f.member_id = m.member_id
                                ORDER BY f.amount DESC)
                 WHERE ROWNUM <= 20""";
        String rewritten = """
                SELECT * FROM (SELECT m.member_id, f.amount FROM lib.members m JOIN lib.member_fees f ON f.member_id = m.member_id)
                 WHERE ROWNUM <= 20""";
        var r = AiCheck.check(Dialect.ORACLE, original, rewritten, Map.of(), Catalog.of(List.of()));
        assertEquals(AiCheck.Label.STRUCTURE_CHANGED, r.label());
        assertTrue(r.differences().stream().anyMatch(d -> d.startsWith("Ordering and row limits")), r.differences()::toString);
        var limit = AiCheck.check(Dialect.POSTGRESQL, "select a from t order by a limit 5", "select a from t limit 5",
                Map.of(), Catalog.of(List.of()));
        assertEquals(AiCheck.Label.STRUCTURE_CHANGED, limit.label());
    }

    @Test
    void aMissingFinalSemicolonIsNotInvalid() {
        String original = "select o.id from shop.orders o where date(o.created_at) = @d;";
        var r = AiCheck.check(Dialect.MYSQL, original,
                "select o.id from shop.orders o where o.created_at >= @d and o.created_at < date_add(@d, interval 1 day)",
                Map.of(), Catalog.of(List.of()));
        assertEquals(AiCheck.Label.STRUCTURE_PRESERVED, r.label(), () -> r.diagnostics() + " " + r.differences());
    }

    @Test
    void developerNotesAreAddedAndMasked() {
        Script s = script(Dialect.ORACLE, SCRIPT);
        List<Statement> steps = Lifecycles.steps(s);
        String notes = "sales.orders gets 10 million rows a day; ORDERS.status is never null. Keep crm.customer untouched.";
        var c = AiContextBuilder.build(s, steps, List.of(), List.of(), Catalog.of(List.of()), 6000, true, notes);
        assertTrue(c.user().contains("## Notes from the developer"), c.user());
        String section = c.user().substring(c.user().indexOf("## Notes from the developer"));
        assertFalse(section.toLowerCase().contains("orders") || section.toLowerCase().contains("customer"), section);
        assertTrue(section.contains("10 million rows a day"), section);
        assertEquals(notes, Masking.unmask(Dialect.ORACLE, Masking.maskText(notes, c.mask()), c.mask())
                .replace("ORDERS.status", "orders.status").replace("orders.status", "ORDERS.status"),
                "masked notes restore to the original words");
        var plain = AiContextBuilder.build(s, steps, List.of(), List.of(), Catalog.of(List.of()), 6000, false, notes);
        assertTrue(plain.user().contains("sales.orders gets 10 million rows"));
        assertEquals("unchanged", Masking.maskText("unchanged", Map.of()));
        assertEquals("ordersx and T1", Masking.maskText("ordersx and orders", Map.of("T1", "orders")), "whole words only");
        assertEquals("a T1", Masking.maskText("a orders", Map.of("T1", "orders", "C1", "a")), "one-letter names stay: prose has them");
    }
}
