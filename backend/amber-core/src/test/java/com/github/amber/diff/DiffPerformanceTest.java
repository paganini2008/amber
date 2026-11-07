package com.github.amber.diff;

import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large synthetic scripts (over 5,000 lines and 1,000 statements) must diff well under two seconds,
 * parsing of both versions included.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class DiffPerformanceTest {

    /** A legacy-style script: blocks of temp-table staging, inserts, updates and deletes. */
    static String script(int blocks, int changedEvery) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks; i++) {
            String amount = changedEvery > 0 && i % changedEvery == 0 ? "net_amount" : "amount";
            sb.append("-- block ").append(i).append('\n');
            sb.append("create temp table tmp_").append(i).append(" as\n")
                    .append("select o.customer_id, o.").append(amount).append(" as amount, o.order_date\n")
                    .append("  from sales.orders_").append(i % 50).append(" o\n")
                    .append(" where o.status = 'S").append(i).append("';\n");
            sb.append("insert into rpt.summary_").append(i % 40).append(" (customer_id, total)\n")
                    .append("select t.customer_id, sum(t.amount)\n")
                    .append("  from tmp_").append(i).append(" t\n")
                    .append("  join crm.customer c on c.id = t.customer_id\n")
                    .append(" group by t.customer_id;\n");
            sb.append("update rpt.summary_").append(i % 40).append("\n   set flag = ").append(i)
                    .append("\n where total > ").append(i).append(";\n");
            sb.append("delete from stage.log_").append(i % 30).append(" where batch_id = ").append(i).append(";\n");
            sb.append("drop table tmp_").append(i).append(";\n");
        }
        return sb.toString();
    }

    @Test
    void bigScriptDiffsFast() {
        String before = script(350, 0);
        String reordered = script(350, 37);
        // move one block's delete to the end, remove another block's update
        String after = reordered.replace("delete from stage.log_5 where batch_id = 5;\n", "")
                .replace("update rpt.summary_9\n   set flag = 9\n where total > 9;\n", "")
                + "delete from stage.log_5 where batch_id = 5;\n";
        assertTrue(before.lines().count() > 5000, "lines: " + before.lines().count());
        // warm up the parsers on a smaller script
        ScriptDiff.compare(Dialect.POSTGRESQL, script(40, 0), script(40, 7), Catalog.of(List.of()));
        long t0 = System.nanoTime();
        DiffResult r = ScriptDiff.compare(Dialect.POSTGRESQL, before, after, Catalog.of(List.of()));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("Diff of " + before.lines().count() + " lines / " + r.before().stepCount()
                + " statements took " + ms + " ms (engine " + r.elapsedMs() + " ms)");
        assertTrue(r.before().stepCount() >= 1000, "statements: " + r.before().stepCount());
        assertEquals(10, r.summary().statements().modified(), ScriptDiffTest.explain(r));
        assertEquals(1, r.summary().statements().moved());
        assertEquals(1, r.summary().statements().removed());
        assertEquals(DiffResult.Risk.HIGH, r.summary().risk());
        assertTrue(ms < 2000, "took " + ms + " ms");
    }

    @Test
    void completelyDifferentScriptsStayBounded() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            a.append("select c").append(i).append(" from t").append(i).append(";\n");
            b.append("select d").append(i).append(" from u").append(i).append(";\n");
        }
        long t0 = System.nanoTime();
        DiffResult r = ScriptDiff.compare(Dialect.SQLITE, a.toString(), b.toString(), null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("Disjoint diff of 1500 statements took " + ms + " ms");
        assertEquals(1500, r.summary().statements().modified() + r.summary().statements().added());
        assertTrue(ms < 5000, "took " + ms + " ms");
    }
}
