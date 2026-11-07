package com.github.amber.dialect.refs;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find references and go to definition for objects and columns.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class ReferencesTest {

    private static final String SQL = """
            create table tmp_paid (customer_id int, total numeric);
            insert into tmp_paid (customer_id, total)
            select o.customer_id, sum(o.amount) from sales.orders o where o.status = 'PAID' group by o.customer_id;
            update crm.customer c set tier = 'GOLD' where c.id in (select customer_id from tmp_paid where total > 1000);
            with big as (select customer_id from tmp_paid where total > 5000)
            select b.customer_id, x.amount from big b join sales.orders x on x.customer_id = b.customer_id;
            drop table tmp_paid;
            """;

    private static Script script(Dialect d, String sql) {
        DialectFrontend f = DialectFrontends.get(d);
        return ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
    }

    private static List<Integer> lines(References.Result r) {
        return r.occurrences().stream().map(o -> o.range().start().line()).toList();
    }

    @Test
    void tableUsesAndDefinition() {
        var r = References.ofObject(script(Dialect.POSTGRESQL, SQL), "TMP_PAID");
        assertEquals(List.of(1, 2, 4, 5, 7), lines(r));
        assertNotNull(r.definition());
        assertEquals(1, r.definition().range().start().line());
        assertEquals(List.of("CREATE", "INSERT", "READ", "READ", "DROP"), r.occurrences().stream().map(References.Occurrence::access).toList());
        assertTrue(r.occurrences().get(1).line().startsWith("2: insert into tmp_paid"), r.occurrences().get(1).line());
    }

    @Test
    void externalTableHasNoDefinition() {
        var r = References.ofObject(script(Dialect.POSTGRESQL, SQL), "orders");
        assertNull(r.definition());
        assertEquals(List.of(3, 6), lines(r));
    }

    @Test
    void cteGoesToItsDefinition() {
        var r = References.ofObject(script(Dialect.POSTGRESQL, SQL), "big");
        assertEquals("CTE", r.kind());
        assertEquals(5, r.definition().range().start().line());
        assertTrue(r.definition().line().contains("with big as"));
    }

    @Test
    void columnUsesFollowAliasesAndSkipOtherTables() {
        var r = References.ofColumn(script(Dialect.POSTGRESQL, SQL), "sales.orders", "customer_id");
        // line 3: o.customer_id twice; line 6: x.customer_id but not b.customer_id (that is the CTE's column)
        assertEquals(List.of(3, 3, 6), lines(r), r::toString);
        assertTrue(r.occurrences().stream().allMatch(o -> o.access().equals("READ")));
        var written = References.ofColumn(script(Dialect.POSTGRESQL, SQL), "tmp_paid", "total");
        assertEquals("CREATE", written.occurrences().getFirst().access());
        assertNotNull(written.definition());
        assertTrue(written.occurrences().stream().anyMatch(o -> o.access().equals("WRITE") && o.range().start().line() == 2));
        assertTrue(written.occurrences().stream().anyMatch(o -> o.access().equals("READ") && o.range().start().line() == 4));
    }

    @Test
    void worksAcrossDialectsAndRejectsBadInput() {
        String tsql = "select * into #t from dbo.items;\nupdate #t set qty = 0;\nselect qty from #t;\n";
        var r = References.ofObject(script(Dialect.SQLSERVER, tsql), "#t");
        assertEquals(List.of(1, 2, 3), lines(r));
        String ora = "create or replace procedure p_load as begin null; end;\n/\nbegin p_load; end;\n/\n";
        var p = References.ofObject(script(Dialect.ORACLE, ora), "p_load");
        assertNotNull(p.definition());
        assertEquals(1, p.definition().range().start().line());
        assertThrows(IllegalArgumentException.class, () -> References.ofColumn(script(Dialect.H2, "select 1;"), "t", " "));
    }
}
