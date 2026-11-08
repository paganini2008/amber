package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Severity;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Variable lineage beyond assignments and {@code SELECT … INTO}: {@code RETURNING … INTO}, dynamic
 * {@code INTO}, {@code RETURN expr}, call arguments and OUT parameters of script routines, cursor FOR-loop
 * records, collection elements, Db2 {@code VALUES … INTO}, MySQL {@code INSERT … SET}, T-SQL
 * {@code UPDATE … SET @v = …} and PL/pgSQL nested block scopes.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class VariableLineageGapsTest {

    private static Script script(Dialect dialect, String sql) {
        DialectFrontend f = DialectFrontends.get(dialect);
        Script s = ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
        assertFalse(s.diagnostics().stream().anyMatch(d -> d.severity() == Severity.ERROR), () -> s.diagnostics().toString());
        assertFalse(s.diagnostics().stream().anyMatch(d -> d.code().equals("lineage")), () -> s.diagnostics().toString());
        return s;
    }

    private static String node(ColumnNode n) {
        boolean variable = n.ownerKind() == ColumnNode.OwnerKind.VARIABLE || n.ownerKind() == ColumnNode.OwnerKind.PARAMETER;
        String column = variable && n.isRows()
                ? n.ownerKey().substring(n.ownerKey().lastIndexOf(':') + 1) + "." + n.column() : n.column();
        return n.ownerKind() + ":" + n.owner() + "." + column;
    }

    private static String edge(ColumnEdge e) {
        return node(e.from()) + " -> " + node(e.to()) + " " + (e.isDirect() ? e.transform() : "~" + e.role());
    }

    private static Set<String> edges(Script s) {
        Set<String> out = new LinkedHashSet<>();
        for (Statement st : s.flatten()) {
            if (st.lineage() != null) {
                st.lineage().edges().forEach(e -> out.add(edge(e)));
            }
        }
        return out;
    }

    private static void has(Set<String> edges, String... expected) {
        for (String e : expected) {
            assertTrue(edges.contains(e), () -> "missing " + e + " in\n" + String.join("\n", edges));
        }
    }

    private static void hasNot(Set<String> edges, String... unexpected) {
        for (String e : unexpected) {
            assertFalse(edges.contains(e), () -> "unexpected " + e + " in\n" + String.join("\n", edges));
        }
    }

    private static List<String> outputs(Script s, StatementKind kind) {
        return s.flatten().stream().filter(st -> st.kind() == kind && st.lineage() != null)
                .flatMap(st -> st.lineage().outputs().stream()).map(ColumnNode::toString).collect(Collectors.toList());
    }

    private static void noPlaceholders(Script s) {
        for (Statement st : s.flatten()) {
            if (st.lineage() != null) {
                for (ColumnEdge e : st.lineage().edges()) {
                    assertFalse(e.from().ownerKey().startsWith("call:") || e.to().ownerKey().startsWith("call:"), e::toString);
                }
                st.lineage().outputs().forEach(o -> assertFalse(o.ownerKey().startsWith("call:"), o::toString));
            }
        }
    }

    // ----- Oracle -------------------------------------------------------------------------------------

    private static final String ORACLE = """
            create table dw.audit (cnt number, top_amount number, run_day date, note varchar2(30));
            create table stage.items (item_id number, amount number);

            create or replace function dw.f_net(p_amt in number, p_rate number default 0) return number is
              v_r number;
            begin
              v_r := p_amt * (1 - p_rate);
              return v_r + 1;
            end;
            /
            create or replace procedure dw.p_out(p_in in number, p_res out number, p_acc in out number) is
            begin
              p_res := p_in * 2;
              p_acc := p_acc + p_in;
              return;
            end;
            /
            create or replace procedure dw.p_main(p_day date) is
              v_id number;
              v_x number;
              v_y number;
              v_cnt number;
              v_sql varchar2(200);
              type t_tab is table of number index by pls_integer;
              v_tab t_tab;
              cursor c_items is select item_id, amount from stage.items;
            begin
              insert into dw.audit (cnt, note) values (1, 'a') returning cnt into v_id;
              update dw.audit set cnt = cnt + 1 where run_day = p_day returning top_amount, cnt into v_x, v_y;
              delete from dw.audit where run_day = p_day returning note into :b_note;
              execute immediate v_sql into v_cnt using p_day;
              v_x := dw.f_net(v_id, p_rate => v_y);
              dw.p_out(v_x, v_y, p_acc => v_cnt);
              ext.p_unknown(v_x, v_y);
              v_y := upper(v_x) || ext.f_unknown(v_id);
              for r in (select item_id, amount from stage.items where amount > 0) loop
                insert into dw.audit (cnt, top_amount) values (r.item_id, r.amount);
                insert into dw.audit (note) select r.note from dw.audit r;
              end loop;
              for r2 in c_items loop
                v_tab(r2.item_id) := r2.amount;
              end loop;
              for i in 1 .. 10 loop
                v_tab(i) := i;
              end loop;
              insert into dw.audit (top_amount, cnt) values (v_tab(1), v_tab.count);
              select dw.f_net(amount) into v_x from stage.items where item_id = v_tab(2);
            end;
            /
            """;

    @Test
    void oracleReturningIntoAndDynamicInto() {
        Script s = script(Dialect.ORACLE, ORACLE);
        Set<String> e = edges(s);
        has(e, "TABLE:dw.audit.cnt -> VARIABLE:dw.p_main.v_id PASS_THROUGH",          // INSERT … RETURNING … INTO
                "TABLE:dw.audit.top_amount -> VARIABLE:dw.p_main.v_x PASS_THROUGH",    // UPDATE … RETURNING a, b INTO x, y
                "TABLE:dw.audit.cnt -> VARIABLE:dw.p_main.v_y PASS_THROUGH",
                "TABLE:dw.audit.cnt -> TABLE:dw.audit.cnt EXPRESSION");               // the UPDATE itself is kept
        assertFalse(e.stream().anyMatch(x -> x.contains("b_note")), "bind variables are no variables");
        // EXECUTE IMMEDIATE … INTO v: v is written, but nothing is known about where its value comes from.
        assertEquals(List.of("dw.p_main.v_cnt"), outputs(s, StatementKind.DYNAMIC_SQL));
        assertTrue(s.flatten().stream().filter(st -> st.kind() == StatementKind.DYNAMIC_SQL)
                .allMatch(st -> st.lineage().edges().isEmpty()), "no edge into a dynamic INTO target");
    }

    @Test
    void oracleReturnValueAndCalls() {
        Script s = script(Dialect.ORACLE, ORACLE);
        Set<String> e = edges(s);
        noPlaceholders(s);
        has(e, "VARIABLE:dw.f_net.v_r -> VARIABLE:dw.f_net.return EXPRESSION",                 // RETURN expr
                "VARIABLE:dw.p_main.v_id -> PARAMETER:dw.f_net.p_amt PASS_THROUGH",            // positional argument
                "VARIABLE:dw.p_main.v_y -> PARAMETER:dw.f_net.p_rate PASS_THROUGH",            // p_rate => v_y
                "VARIABLE:dw.f_net.return -> VARIABLE:dw.p_main.v_x EXPRESSION",               // the call's value
                "VARIABLE:dw.p_main.v_x -> PARAMETER:dw.p_out.p_in PASS_THROUGH",
                "PARAMETER:dw.p_out.p_res -> VARIABLE:dw.p_main.v_y PASS_THROUGH",             // OUT back to caller
                "VARIABLE:dw.p_main.v_cnt -> PARAMETER:dw.p_out.p_acc PASS_THROUGH",           // IN OUT both ways
                "PARAMETER:dw.p_out.p_acc -> VARIABLE:dw.p_main.v_cnt PASS_THROUGH",
                "TABLE:stage.items.amount -> PARAMETER:dw.f_net.p_amt PASS_THROUGH",           // call inside a query
                "VARIABLE:dw.f_net.return -> VARIABLE:dw.p_main.v_x EXPRESSION");
        hasNot(e, "VARIABLE:dw.p_main.v_y -> PARAMETER:dw.p_out.p_res PASS_THROUGH",           // OUT takes no argument
                "PARAMETER:dw.p_out.p_in -> VARIABLE:dw.p_main.v_x PASS_THROUGH");             // IN is not written back
        assertFalse(e.stream().anyMatch(x -> x.contains("p_unknown") || x.contains("f_unknown")),
                "routines not defined in the script are not followed");
        assertFalse(e.stream().anyMatch(x -> x.contains("dw.p_out.return")), "procedures have no result");
        assertTrue(outputs(s, StatementKind.CALL).containsAll(List.of("dw.p_main.v_y", "dw.p_main.v_cnt")),
                () -> outputs(s, StatementKind.CALL).toString());

        // Across the call: the audit row's top_amount reaches the function's parameter and the result.
        LineageGraph g = LineageGraph.of(s);
        ColumnNode ret = g.nodes().stream().filter(n -> n.toString().equals("dw.f_net.return")).findFirst().orElseThrow();
        Set<String> up = g.upstream(ret, false).stream().map(x -> edge(x.edge())).collect(Collectors.toSet());
        has(up, "PARAMETER:dw.f_net.p_amt -> VARIABLE:dw.f_net.v_r EXPRESSION",
                "TABLE:stage.items.amount -> PARAMETER:dw.f_net.p_amt PASS_THROUGH");
    }

    @Test
    void oracleCursorLoopRecordsAndCollections() {
        Set<String> e = edges(script(Dialect.ORACLE, ORACLE));
        has(e, "RESULT:result.item_id -> TABLE:dw.audit.cnt PASS_THROUGH",                   // FOR r IN (SELECT …)
                "RESULT:result.amount -> TABLE:dw.audit.top_amount PASS_THROUGH",
                "TABLE:stage.items.item_id -> RESULT:result.item_id PASS_THROUGH",
                "TABLE:dw.audit.note -> TABLE:dw.audit.note PASS_THROUGH",                   // table alias r wins
                "RESULT:result.amount -> VARIABLE:dw.p_main.v_tab PASS_THROUGH",             // FOR r2 IN c_items
                "VARIABLE:dw.p_main.v_tab -> TABLE:dw.audit.top_amount EXPRESSION",          // v_tab(1)
                "VARIABLE:dw.p_main.v_tab -> VARIABLE:dw.p_main.V_X.*rows* ~FILTER");        // … where id = v_tab(2)
        hasNot(e, "RESULT:result.note -> TABLE:dw.audit.note PASS_THROUGH");
        assertFalse(e.stream().anyMatch(x -> x.contains("dw.p_main.r ") || x.contains("dw.p_main.r2")),
                "loop records are no variables");
    }

    // ----- SQL Server -----------------------------------------------------------------------------------

    private static final String TSQL = """
            create table dw.audit (cnt int, top_amount money, run_day date)
            GO
            create function dbo.f_net(@amt money, @rate money = 0) returns money
            as
            begin
              declare @r money = @amt * (1 - @rate);
              return @r + 1;
            end
            GO
            create procedure dbo.p_out @in int, @res int output
            as
            begin
              set @res = @in * 2;
              return;
            end
            GO
            create procedure dbo.p_main @day date
            as
            begin
              declare @x int, @y int, @z money, @w int;
              exec dbo.p_out @x, @res = @y output;
              exec dbo.p_out @in = @z, @res = @w;
              exec ext.p_unknown @x, @y output;
              set @z = dbo.f_net(@x, 1);
              update dw.audit set @x = cnt, top_amount = @z where run_day = @day;
              update dw.audit set @y = cnt = cnt + 1;
            end
            GO
            """;

    @Test
    void sqlServerReturnCallsAndUpdateSetVariable() {
        Script s = script(Dialect.SQLSERVER, TSQL);
        Set<String> e = edges(s);
        noPlaceholders(s);
        has(e, "VARIABLE:dbo.f_net.@r -> VARIABLE:dbo.f_net.return EXPRESSION",                // RETURN in a scalar function
                "VARIABLE:dbo.p_main.@x -> PARAMETER:dbo.p_out.@in PASS_THROUGH",
                "VARIABLE:dbo.p_main.@y -> PARAMETER:dbo.p_out.@res PASS_THROUGH",             // OUTPUT: in and back
                "PARAMETER:dbo.p_out.@res -> VARIABLE:dbo.p_main.@y PASS_THROUGH",
                "VARIABLE:dbo.p_main.@z -> PARAMETER:dbo.p_out.@in PASS_THROUGH",              // @in = @z
                "VARIABLE:dbo.p_main.@x -> PARAMETER:dbo.f_net.@amt PASS_THROUGH",
                "VARIABLE:dbo.f_net.return -> VARIABLE:dbo.p_main.@z EXPRESSION",
                "TABLE:dw.audit.cnt -> VARIABLE:dbo.p_main.@x PASS_THROUGH",                   // UPDATE … SET @x = cnt
                "PARAMETER:dbo.p_main.@day -> VARIABLE:dbo.p_main.@X.*rows* ~FILTER",
                "VARIABLE:dbo.p_main.@z -> TABLE:dw.audit.top_amount PASS_THROUGH",
                "TABLE:dw.audit.cnt -> TABLE:dw.audit.cnt EXPRESSION",                         // SET @y = cnt = cnt + 1
                "TABLE:dw.audit.cnt -> VARIABLE:dbo.p_main.@y EXPRESSION");
        // Without OUTPUT at the call the argument is still passed in, but the value written back is ignored
        // only by T-SQL itself; Amber links the parameter back only for a plain variable argument.
        hasNot(e, "PARAMETER:dbo.p_out.@in -> VARIABLE:dbo.p_main.@x PASS_THROUGH");
        assertFalse(e.stream().anyMatch(x -> x.contains("p_unknown")), e::toString);
        assertFalse(e.stream().anyMatch(x -> x.contains("RESULT:result.@")), e::toString);
    }

    // ----- PostgreSQL ----------------------------------------------------------------------------------

    private static final String PG = """
            create table dw.audit (cnt int, top_amount numeric, run_day date, note text);
            create function dw.f_net(p_amt numeric, p_rate numeric default 0) returns numeric language plpgsql as $$
            declare
              v_r numeric;
            begin
              v_r := p_amt * (1 - p_rate);
              return v_r + 1;
            end;
            $$;
            create function dw.f_rows(p_min int) returns setof dw.audit language plpgsql as $$
            begin
              return query select cnt, top_amount from dw.audit where cnt > p_min;
              return next null;
              return;
            end;
            $$;
            create procedure dw.p_main(p_day date, inout p_acc int) language plpgsql as $$
            declare
              v_id int;
              v_x numeric;
              r record;
              c cursor for select cnt, note from dw.audit;
            begin
              insert into dw.audit (cnt, note) values (1, 'a') returning cnt into v_id;
              update dw.audit set cnt = cnt + 1 where run_day = p_day returning top_amount into strict v_x;
              execute 'select count(*) from dw.audit where note = ''into x''' into v_id using p_day;
              v_x := dw.f_net(v_id, p_rate => v_x);
              for r in select cnt, top_amount from dw.audit where cnt > 0 loop
                insert into dw.audit (top_amount) values (r.top_amount);
              end loop;
              for r in c loop
                insert into dw.audit (note) values (r.note);
              end loop;
              declare
                v_x int := 5;
              begin
                insert into dw.audit (cnt) values (v_x);
              end;
              insert into dw.audit (top_amount) values (v_x);
              call dw.p_main(p_day, v_id);
            end;
            $$;
            insert into dw.audit (cnt) values (2) returning cnt as new_cnt, note;
            """;

    @Test
    void postgresReturningReturnAndDynamicInto() {
        Script s = script(Dialect.POSTGRESQL, PG);
        Set<String> e = edges(s);
        noPlaceholders(s);
        has(e, "TABLE:dw.audit.cnt -> VARIABLE:dw.p_main.v_id PASS_THROUGH",                   // RETURNING … INTO
                "TABLE:dw.audit.top_amount -> VARIABLE:dw.p_main.v_x PASS_THROUGH",            // RETURNING … INTO STRICT
                "VARIABLE:dw.f_net.v_r -> VARIABLE:dw.f_net.return EXPRESSION",                // RETURN expr
                "TABLE:dw.audit.top_amount -> VARIABLE:dw.f_rows.return PASS_THROUGH",         // RETURN QUERY
                "PARAMETER:dw.f_rows.p_min -> VARIABLE:dw.f_rows.RETURN.*rows* ~FILTER",
                "TABLE:dw.audit.cnt -> RESULT:result.new_cnt PASS_THROUGH",                    // plain RETURNING: a result
                "TABLE:dw.audit.note -> RESULT:result.note PASS_THROUGH");
        assertTrue(outputs(s, StatementKind.INSERT).contains("dw.audit.note"), "the INSERT still writes its columns");
        assertEquals(List.of("dw.p_main.v_id"), outputs(s, StatementKind.DYNAMIC_SQL));
    }

    @Test
    void postgresCallsLoopsAndNestedBlocks() {
        Script s = script(Dialect.POSTGRESQL, PG);
        Set<String> e = edges(s);
        has(e, "VARIABLE:dw.p_main.v_id -> PARAMETER:dw.f_net.p_amt PASS_THROUGH",
                "VARIABLE:dw.p_main.v_x -> PARAMETER:dw.f_net.p_rate PASS_THROUGH",
                "VARIABLE:dw.f_net.return -> VARIABLE:dw.p_main.v_x EXPRESSION",
                "VARIABLE:dw.p_main.v_id -> PARAMETER:dw.p_main.p_acc PASS_THROUGH",           // CALL: INOUT both ways
                "PARAMETER:dw.p_main.p_acc -> VARIABLE:dw.p_main.v_id PASS_THROUGH",
                "TABLE:dw.audit.top_amount -> RESULT:result.top_amount PASS_THROUGH",          // FOR r IN SELECT …
                "RESULT:result.top_amount -> TABLE:dw.audit.top_amount PASS_THROUGH",
                "RESULT:result.note -> TABLE:dw.audit.note PASS_THROUGH",                      // FOR r IN cursor
                "VARIABLE:block@L34.v_x -> TABLE:dw.audit.cnt PASS_THROUGH",                   // inner v_x shadows
                "VARIABLE:dw.p_main.v_x -> TABLE:dw.audit.top_amount PASS_THROUGH");
        hasNot(e, "VARIABLE:dw.p_main.v_x -> TABLE:dw.audit.cnt PASS_THROUGH",
                "VARIABLE:dw.p_main.r -> TABLE:dw.audit.top_amount PASS_THROUGH");
    }

    // ----- MySQL, MariaDB, Db2 --------------------------------------------------------------------------

    private static final String MYSQL = """
            create function f_twice(p_v int) returns int deterministic
            begin
              return p_v * 2;
            end;
            create procedure p_set(in p_a int, out p_b int)
            begin
              declare v_n int default 0;
              insert into dw.audit set cnt = p_a, note = 'x', top_amount = f_twice(v_n);
              set p_b = f_twice(p_a);
            end;
            create procedure p_caller()
            begin
              declare v_k int;
              call p_set(3, v_k);
              insert into dw.audit (cnt) values (v_k);
            end;
            """;

    private static void mysqlLike(Script s) {
        Set<String> e = edges(s);
        noPlaceholders(s);
        has(e, "PARAMETER:f_twice.p_v -> VARIABLE:f_twice.return EXPRESSION",
                "PARAMETER:p_set.p_a -> TABLE:dw.audit.cnt PASS_THROUGH",                     // INSERT … SET
                "VARIABLE:p_set.v_n -> PARAMETER:f_twice.p_v PASS_THROUGH",
                "VARIABLE:f_twice.return -> TABLE:dw.audit.top_amount EXPRESSION",
                "VARIABLE:f_twice.return -> PARAMETER:p_set.p_b EXPRESSION",
                "PARAMETER:p_set.p_b -> VARIABLE:p_caller.v_k PASS_THROUGH");                 // OUT parameter
        hasNot(e, "VARIABLE:p_caller.v_k -> PARAMETER:p_set.p_b PASS_THROUGH");
    }

    @Test
    void mysqlInsertSetReturnAndCall() {
        mysqlLike(script(Dialect.MYSQL, MYSQL));
    }

    @Test
    void mariaDbInsertSetReturnAndCall() {
        mysqlLike(script(Dialect.MARIADB, MYSQL));
    }

    @Test
    void db2ValuesIntoAndReturn() {
        Script s = script(Dialect.DB2, """
                create function dw.f_inc(p_x integer) returns integer
                language sql
                begin
                  return p_x + 1;
                end
                @
                create procedure dw.p_v(in p_a integer)
                language sql
                begin
                  declare v_x integer;
                  declare v_y integer;
                  values p_a + 1 into v_x;
                  values (p_a, v_x * 2) into v_x, v_y;
                  set v_y = dw.f_inc(v_x);
                  insert into dw.audit (cnt) values (v_x);
                end
                @
                """);
        Set<String> e = edges(s);
        noPlaceholders(s);
        has(e, "PARAMETER:dw.p_v.p_a -> VARIABLE:dw.p_v.v_x EXPRESSION",                    // VALUES expr INTO v
                "PARAMETER:dw.p_v.p_a -> VARIABLE:dw.p_v.v_x PASS_THROUGH",                  // VALUES (a, b) INTO x, y
                "VARIABLE:dw.p_v.v_x -> VARIABLE:dw.p_v.v_y EXPRESSION",
                "PARAMETER:dw.f_inc.p_x -> VARIABLE:dw.f_inc.return EXPRESSION",
                "VARIABLE:dw.p_v.v_x -> PARAMETER:dw.f_inc.p_x PASS_THROUGH",
                "VARIABLE:dw.p_v.v_x -> TABLE:dw.audit.cnt PASS_THROUGH");
        hasNot(e, "PARAMETER:dw.p_v.p_a -> VARIABLE:dw.p_v.v_y PASS_THROUGH");
    }

    // ----- negative cases --------------------------------------------------------------------------------

    @Test
    void ambiguousAndUnknownRoutinesAreNotFollowed() {
        Script s = script(Dialect.ORACLE, """
                create or replace function a.f_x(p number) return number is begin return p; end;
                /
                create or replace function b.f_x(q number) return number is begin return q; end;
                /
                declare
                  v number := 1;
                  u number := 2;
                  w number;
                begin
                  w := f_x(u);
                  w := a.f_x(v, 2, 3);
                  w := nvl(v, 0);
                end;
                /
                """);
        Set<String> e = edges(s);
        noPlaceholders(s);
        has(e, "VARIABLE:block@L5.v -> PARAMETER:a.f_x.p PASS_THROUGH",                     // qualified: found
                "VARIABLE:a.f_x.return -> VARIABLE:block@L5.w EXPRESSION",
                "VARIABLE:block@L5.v -> VARIABLE:block@L5.w EXPRESSION");
        assertFalse(e.stream().anyMatch(x -> x.startsWith("VARIABLE:block@L5.u -> PARAMETER")
                || x.startsWith("VARIABLE:b.f_x.return")), "f_x alone is ambiguous: " + e);
        assertEquals(1, e.stream().filter(x -> x.contains("-> PARAMETER:a.f_x")).count(), "extra arguments match no parameter");
    }

    @Test
    void routineHeadersParseModesAndNames() {
        Script s = script(Dialect.POSTGRESQL, """
                create function dw.f_un(int, p_b int, out p_c int) language sql as $$ select $1 + p_b $$;
                """);
        Statement f = s.statements().get(0);
        List<CallLineage.Param> params = CallLineage.parameters(Dialect.POSTGRESQL, s.source(), f);
        assertEquals(List.of(new CallLineage.Param("$1", CallLineage.Mode.IN), new CallLineage.Param("p_b", CallLineage.Mode.IN),
                new CallLineage.Param("p_c", CallLineage.Mode.OUT)), params);
        Script t = script(Dialect.SQLSERVER, """
                create procedure dbo.p @a int = 1, @b varchar(10) output, @c int out
                as
                select 1
                """);
        assertEquals(List.of(new CallLineage.Param("@a", CallLineage.Mode.IN), new CallLineage.Param("@b", CallLineage.Mode.INOUT),
                new CallLineage.Param("@c", CallLineage.Mode.INOUT)),
                CallLineage.parameters(Dialect.SQLSERVER, t.source(), t.statements().get(0)));
        Script o = script(Dialect.ORACLE, """
                create or replace procedure p (a in out nocopy number, b out date, c varchar2 default 'x,y') is
                begin null; end;
                /
                """);
        assertEquals(List.of(new CallLineage.Param("a", CallLineage.Mode.INOUT), new CallLineage.Param("b", CallLineage.Mode.OUT),
                new CallLineage.Param("c", CallLineage.Mode.IN)),
                CallLineage.parameters(Dialect.ORACLE, o.source(), o.statements().get(0)));
    }
}
