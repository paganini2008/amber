package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.refs.References;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls of procedures and functions defined in the script carry the objects their bodies touch.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class CallLinkerTest {

    private static Script script(Dialect d, String sql) {
        DialectFrontend f = DialectFrontends.get(d);
        return ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
    }

    /** "object ACCESS via routine" for the refs of the statement starting on {@code line}. */
    private static Set<String> viaRefs(Script s, int line) {
        Statement st = Lifecycles.steps(s).stream().filter(x -> x.range().start().line() == line && x.children().isEmpty())
                .findFirst().orElseThrow();
        return st.refs().stream().filter(ObjectRef::indirect)
                .map(r -> r.name() + " " + r.access() + " via " + r.via()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static final String ORACLE = """
            create or replace procedure dw.p_stage is
            begin
              insert into dw.stage select * from src.raw;
            end;
            /
            create or replace procedure dw.p_load(p_day date) is
            begin
              dw.p_stage;
              insert into dw.daily (day, total) select day, sum(amount) from dw.stage group by day;
              p_load_again(p_day);
            end;
            /
            create or replace function dw.f_total(p_day date) return number is
              v number;
            begin
              select total into v from dw.daily where day = p_day;
              return v;
            end;
            /
            begin
              dw.p_load(sysdate);
            end;
            /
            select dw.f_total(sysdate) from dual;
            """;

    @Test
    void callsCarryTheBodyEffectsTransitively() {
        Script s = script(Dialect.ORACLE, ORACLE);
        assertEquals(new TreeSet<>(Set.of("dw.daily INSERT via dw.p_load", "dw.stage INSERT via dw.p_load",
                "dw.stage READ via dw.p_load", "src.raw READ via dw.p_load")), viaRefs(s, 21),
                "dw.p_load calls dw.p_stage: its effects come along");
        assertEquals(Set.of("dw.daily READ via dw.f_total"), viaRefs(s, 24), "function call inside a query");

        ObjectLifecycle daily = Lifecycles.of(s).stream().filter(l -> l.name().toString().equals("dw.daily")).findFirst().orElseThrow();
        assertTrue(daily.events().stream().anyMatch(e -> e.via() != null && e.via().toString().equals("dw.p_load")
                && e.range().start().line() == 21), daily::toString);

        var refs = References.ofObject(s, "src.raw");
        assertTrue(refs.occurrences().stream().anyMatch(o -> "dw.p_load".equals(o.via()) && o.range().start().line() == 21),
                refs::toString);
        assertTrue(Findings.of(s).stream().noneMatch(f -> f.rule().equals("AMB-S005")), "via refs are not repeated scans");
    }

    @Test
    void tsqlExecPostgresSelectAndMySqlCall() {
        Script ms = script(Dialect.SQLSERVER, """
                create procedure dbo.usp_fill as
                begin
                  create table #work (id int);
                  insert into #work select id from dbo.src;
                  insert into dbo.dst select id from #work;
                end;
                go
                exec dbo.usp_fill;
                go
                """);
        assertEquals(Set.of("dbo.dst INSERT via dbo.usp_fill", "dbo.src READ via dbo.usp_fill"), viaRefs(ms, 8),
                "the procedure's own temp table stays inside it");

        Script pg = script(Dialect.POSTGRESQL, """
                create function rpt.refresh() returns void language sql as $$ insert into rpt.t select * from rpt.s $$;
                select rpt.refresh();
                """);
        assertTrue(Lifecycles.steps(pg).stream().allMatch(st -> st.refs().stream().noneMatch(r -> r.indirect())
                || st.range().start().line() == 2), "only the call carries via refs");

        Script my = script(Dialect.MYSQL, """
                delimiter $$
                create procedure shop.p_vip()
                begin
                  delete from shop.vip;
                  insert into shop.vip select id from shop.customers;
                end$$
                delimiter ;
                call shop.p_vip();
                """);
        assertEquals(Set.of("shop.customers READ via shop.p_vip", "shop.vip DELETE via shop.p_vip",
                "shop.vip INSERT via shop.p_vip"), viaRefs(my, 8));
    }

    @Test
    void recursionAmbiguityAndUnknownRoutines() {
        Script rec = script(Dialect.ORACLE, """
                create or replace procedure p_a is
                begin
                  insert into t_a select 1 from dual;
                  p_b;
                end;
                /
                create or replace procedure p_b is
                begin
                  delete from t_b;
                  p_a;
                end;
                /
                begin
                  p_a;
                  other_pkg.unknown_proc;
                end;
                /
                """);
        Set<String> a = viaRefs(rec, 14);
        assertTrue(a.contains("t_a INSERT via p_a") && a.contains("t_b DELETE via p_a"), a::toString);
        assertTrue(viaRefs(rec, 15).isEmpty(), "routines outside the script stay unknown");

        Script twins = script(Dialect.ORACLE, """
                create or replace procedure s1.p_x is begin delete from s1.t; end;
                /
                create or replace procedure s2.p_x is begin delete from s2.t; end;
                /
                begin
                  p_x;
                  s2.p_x;
                end;
                /
                """);
        assertTrue(viaRefs(twins, 6).isEmpty(), "an ambiguous simple name is not guessed");
        assertEquals(Set.of("s2.t DELETE via s2.p_x"), viaRefs(twins, 7));
        assertEquals(List.of(), Findings.of(twins).stream().filter(f -> f.rule().equals("AMB-S005")).toList());
    }
}
