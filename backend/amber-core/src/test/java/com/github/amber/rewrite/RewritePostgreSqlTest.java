package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.rewrite.RewriteTestSupport.none;
import static com.github.amber.rewrite.RewriteTestSupport.only;
import static com.github.amber.rewrite.RewriteTestSupport.suggest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rewrite rules on PostgreSQL scripts, including statements inside PL/pgSQL function bodies.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class RewritePostgreSqlTest {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String DDL = "create table events (id bigint primary key, happened_at timestamp not null, "
            + "kind text, owner_id bigint not null);\n";

    @Test
    void dayTruncationsBecomeRanges() {
        assertEquals("select id from events where happened_at >= '2024-03-01' and happened_at < '2024-03-02'",
                only(suggest(D, DDL + "select id from events where happened_at::date = '2024-03-01';"), "AMB-S008")
                        .rewrittenStatement());
        assertEquals("select id from events where happened_at >= date '2024-03-01' and happened_at < date '2024-03-02' "
                + "and kind = 'x'", only(suggest(D, DDL + "select id from events where date_trunc('day', happened_at) = "
                + "date '2024-03-01' and kind = 'x';"), "AMB-S008").rewrittenStatement());
        Rewrite between = only(suggest(D, DDL + "select id from events e where cast(e.happened_at as date) between "
                + "'2024-03-01' and '2024-03-31';"), "AMB-S008");
        assertEquals("select id from events e where e.happened_at >= '2024-03-01' and e.happened_at < '2024-04-01'",
                between.rewrittenStatement());
        assertTrue(between.safetyNote().contains("are whole days"));
        Rewrite expr = only(suggest(D, DDL + "select id from events where happened_at::date = current_date - 1;"),
                "AMB-S008");
        assertEquals("select id from events where happened_at >= current_date - 1 and happened_at < (current_date - 1) "
                + "+ interval '1 day'", expr.rewrittenStatement());
        Rewrite today = only(suggest(D, DDL + "select id from events where date(happened_at) = current_date;"), "AMB-S008");
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, today.safety());
        Rewrite cast = only(suggest(D, DDL + "select id from events where happened_at::date = $1::date;"), "AMB-S008");
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, cast.safety());
        assertTrue(cast.rewrittenStatement().endsWith("happened_at < $1::date + interval '1 day'"));
        none(suggest(D, DDL + "select id from events where upper(kind) = 'X';"), "AMB-S008");
        none(suggest(D, DDL + "select id from events where kind::date = '2024-01-01';"), "AMB-S008");
        none(suggest(D, DDL + "select id from events where date_trunc('month', happened_at) = '2024-01-01';"), "AMB-S008");
    }

    @Test
    void notInSelfReferenceAndOrderSensitiveAggregates() {
        Rewrite r = only(suggest(D, DDL + "select id from events where kind not in (select kind from events where id > 10);"),
                "AMB-S004");
        assertEquals("select id from events where not exists (select 1 from events sq1 where id > 10 and sq1.kind = "
                + "events.kind)", r.rewrittenStatement());
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, r.safety());
        none(suggest(D, DDL + "select string_agg(kind, ',') from (select kind from events order by kind) s;"), "AMB-S012");
        none(suggest(D, DDL + "select array_agg(kind) from (select kind from events order by kind) s;"), "AMB-S012");
        none(suggest(D, DDL + "select kind from (select kind from events order by kind limit 5) s;"), "AMB-S012");
        Rewrite o = only(suggest(D, DDL + "select count(*) from (select kind from events order by kind) s;"), "AMB-S012");
        assertEquals("select count(*) from (select kind from events) s", o.rewrittenStatement());
    }

    @Test
    void distinctOnIsLeftAlone() {
        none(suggest(D, DDL + "select distinct on (kind) kind, id from events group by kind, id;"), "AMB-S011");
        only(suggest(D, DDL + "select distinct kind, count(*) from events group by kind;"), "AMB-S011");
    }

    @Test
    void leftJoinAndUnion() {
        only(suggest(D, DDL + "select e.id from events e left join owners o on o.id = e.owner_id where o.active = true;"),
                "AMB-S002");
        Rewrite u = only(suggest(D, DDL + "select 'a' as src, id from events where kind = 'a' union "
                + "select 'b' as src, id from events;"), "AMB-S009");
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, u.safety());
        Rewrite exists = only(suggest(D, DDL + "select 1 where exists (select id from events union select owner_id from events);"),
                "AMB-S009");
        assertEquals(Safety.EQUIVALENT, exists.safety());
    }

    @Test
    void selectStarIntoViewAndCteForRepeatedSubquery() {
        Rewrite v = only(suggest(D, DDL + "create view v_events as select * from events;"), "AMB-S010");
        assertEquals("create view v_events as select id, happened_at, kind, owner_id from events", v.rewrittenStatement());
        String sql = DDL + "create table recent as select e.id from events e where e.owner_id in "
                + "(select o.id from owners o where o.active and o.region = 'n') and e.id > "
                + "(select min(x.id) from owners x where x.active and x.region = 'n') and e.kind in "
                + "(select o.id from owners o where o.active and o.region = 'n');";
        Rewrite r = only(suggest(D, sql), "AMB-D002");
        assertTrue(r.rewrittenStatement().startsWith("create table recent as with shared_q1 as (select o.id from owners o "
                + "where o.active and o.region = 'n')\nselect e.id"), r.rewrittenStatement());
    }

    @Test
    void rewritesInsideAFunctionBody() {
        String sql = RewriteTestSupport.resource("pg_function.sql");
        List<Rewrite> list = suggest(D, sql);
        Rewrite s004 = only(list, "AMB-S004");
        assertEquals("delete from events where not exists (select 1 from events e2 where e2.kind = 'keep' and e2.id = "
                + "events.id)", s004.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, s004.safety());
        List<Rewrite> s008s = RewriteTestSupport.of(list, "AMB-S008");
        assertEquals(2, s008s.size());
        Rewrite s008 = s008s.getFirst();
        assertEquals("update events set kind = 'old' where happened_at < date '2020-01-01'", s008.rewrittenStatement());
        // SELECT … INTO STRICT only parses inside the function: verified within the whole rewritten script
        Rewrite strict = s008s.get(1);
        assertEquals("select count(*) into strict v from events where happened_at >= date '2024-01-01' and happened_at "
                + "< date '2024-01-02'", strict.rewrittenStatement());
        assertEquals("STRUCTURE_PRESERVED", strict.check());
        // the edits land inside the dollar-quoted body of the original script
        String applied = RewriteSuggester.apply(sql, s008.edits());
        assertTrue(applied.contains("$$\ndeclare") && applied.contains("happened_at < date '2020-01-01';"));
    }
}
