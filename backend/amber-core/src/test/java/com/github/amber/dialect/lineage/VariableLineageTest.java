package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Severity;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Variable lineage: values passing through procedural variables and parameters, per dialect.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class VariableLineageTest {

    private static Script script(Dialect dialect, String sql) {
        DialectFrontend f = DialectFrontends.get(dialect);
        Script s = ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
        assertFalse(s.diagnostics().stream().anyMatch(d -> d.severity() == Severity.ERROR), () -> s.diagnostics().toString());
        return s;
    }

    /** {@code KIND:owner.column}; a variable's rows node shows the variable: {@code VARIABLE:dw.p.V_X.*rows*}. */
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

    private static ColumnNode node(LineageGraph g, String display) {
        return g.nodes().stream().filter(n -> n.toString().equals(display)).findFirst()
                .orElseThrow(() -> new AssertionError(display + " not in " + g.nodes()));
    }

    // ----- Oracle -------------------------------------------------------------------------------------

    private static final String ORACLE = """
            create table dw.audit (cnt number, top_amount number, run_day date, note varchar2(30));
            create table stage.items (item_id number, amount number, v_shadow number);

            create or replace procedure dw.p_load(p_day in date, p_factor number default 1) is
              v_cnt number;
              v_max number;
              v_total number := p_factor * 10;
              v_shadow number;
              cursor c_items is select item_id, amount from stage.items where amount > p_factor;
              v_id number;
              v_amt number;
              a_ids number;
            begin
              select count(*), max(amount) into v_cnt, v_max from sales.orders where order_day = p_day;
              insert into dw.audit (cnt, top_amount) values (v_cnt, v_max);
              v_total := v_total + v_max;
              if v_cnt > 0 then
                v_max := v_max * p_factor;
              end if;
              update dw.audit set top_amount = v_total, note = 'x' where run_day = p_day and cnt = v_cnt;
              select v_shadow into v_amt from stage.items where item_id = v_id;
              open c_items;
              fetch c_items into v_id, v_amt;
              close c_items;
              select item_id bulk collect into a_ids from stage.items;
              insert into dw.audit (cnt, note) values (v_id, :host_value);
            end;
            /
            create or replace procedure dw.p_other is
              v_max number;
            begin
              select min(amount) into v_max from sales.refunds;
              insert into dw.audit (top_amount) values (v_max);
            end;
            /
            declare
              v_max number;
            begin
              v_max := 1;
              declare
                v_max number := 2;
              begin
                insert into dw.audit (cnt) values (v_max);
              end;
              insert into dw.audit (top_amount) values (v_max);
            end;
            /
            create or replace package body dw.pkg is
              g_last number;
              procedure set_last is
              begin
                select max(amount) into g_last from sales.orders;
              end;
              procedure use_last is
              begin
                insert into dw.audit (top_amount) values (g_last);
              end;
            end;
            /
            """;

    @Test
    void oracleSelectIntoThenInsertValues() {
        Script s = script(Dialect.ORACLE, ORACLE);
        Set<String> e = edges(s);
        has(e, "TABLE:sales.orders.amount -> VARIABLE:dw.p_load.v_max AGGREGATE",
                "TABLE:sales.orders.order_day -> VARIABLE:dw.p_load.V_MAX.*rows* ~FILTER",
                "PARAMETER:dw.p_load.p_day -> VARIABLE:dw.p_load.V_MAX.*rows* ~FILTER",
                "TABLE:sales.orders.order_day -> VARIABLE:dw.p_load.V_CNT.*rows* ~FILTER",
                "VARIABLE:dw.p_load.v_cnt -> TABLE:dw.audit.cnt PASS_THROUGH",
                "VARIABLE:dw.p_load.v_max -> TABLE:dw.audit.top_amount PASS_THROUGH");
        hasNot(e, "TABLE:sales.orders.p_day -> VARIABLE:dw.p_load.V_MAX.*rows* ~FILTER");

        // Across statements: the audit column comes from the orders amount through the variable.
        LineageGraph g = LineageGraph.of(s);
        Set<String> up = g.upstream(node(g, "dw.audit.top_amount"), false).stream()
                .map(x -> edge(x.edge())).collect(Collectors.toSet());
        has(up, "TABLE:sales.orders.amount -> VARIABLE:dw.p_load.v_max AGGREGATE",
                "VARIABLE:dw.p_load.v_max -> TABLE:dw.audit.top_amount PASS_THROUGH");
        Set<String> withFilters = g.upstream(node(g, "dw.p_load.v_max"), true).stream()
                .map(x -> edge(x.edge())).collect(Collectors.toSet());
        has(withFilters, "TABLE:sales.orders.order_day -> VARIABLE:dw.p_load.V_MAX.*rows* ~FILTER");
        assertEquals(ColumnNode.OwnerKind.PARAMETER, node(g, "dw.p_load.p_day").ownerKind());
        assertEquals(ColumnNode.OwnerKind.VARIABLE, node(g, "dw.p_load.v_max").ownerKind());
    }

    @Test
    void oracleAssignmentsDeclarationsAndFilters() {
        Set<String> e = edges(script(Dialect.ORACLE, ORACLE));
        has(e, "PARAMETER:dw.p_load.p_factor -> VARIABLE:dw.p_load.v_total EXPRESSION",     // declaration default
                "VARIABLE:dw.p_load.v_max -> VARIABLE:dw.p_load.v_total EXPRESSION",         // v_total := v_total + v_max
                "PARAMETER:dw.p_load.p_factor -> VARIABLE:dw.p_load.v_max EXPRESSION",       // inside IF
                "VARIABLE:dw.p_load.v_total -> TABLE:dw.audit.top_amount PASS_THROUGH",      // UPDATE SET col = v
                "VARIABLE:dw.p_load.v_cnt -> TABLE:dw.audit.*rows* ~FILTER",                 // WHERE cnt = v_cnt
                "PARAMETER:dw.p_load.p_day -> TABLE:dw.audit.*rows* ~FILTER",
                "PARAMETER:dw.p_load.p_factor -> RESULT:result.*rows* ~FILTER");             // cursor query
        hasNot(e, "VARIABLE:dw.p_load.v_total -> VARIABLE:dw.p_load.v_total EXPRESSION");
    }

    @Test
    void oracleColumnWinsOverSameNamedVariable() {
        Set<String> e = edges(script(Dialect.ORACLE, ORACLE));
        has(e, "TABLE:stage.items.v_shadow -> VARIABLE:dw.p_load.v_amt PASS_THROUGH",
                "VARIABLE:dw.p_load.v_id -> VARIABLE:dw.p_load.V_AMT.*rows* ~FILTER",
                "TABLE:stage.items.item_id -> VARIABLE:dw.p_load.V_AMT.*rows* ~FILTER");
        hasNot(e, "VARIABLE:dw.p_load.v_shadow -> VARIABLE:dw.p_load.v_amt PASS_THROUGH");
    }

    @Test
    void oracleCursorFetchAndBulkCollect() {
        Set<String> e = edges(script(Dialect.ORACLE, ORACLE));
        has(e, "TABLE:stage.items.item_id -> RESULT:result.item_id PASS_THROUGH",
                "RESULT:result.item_id -> VARIABLE:dw.p_load.v_id PASS_THROUGH",
                "RESULT:result.amount -> VARIABLE:dw.p_load.v_amt PASS_THROUGH",
                "TABLE:stage.items.item_id -> VARIABLE:dw.p_load.a_ids PASS_THROUGH",
                "VARIABLE:dw.p_load.v_id -> TABLE:dw.audit.cnt PASS_THROUGH");
        assertFalse(e.stream().anyMatch(x -> x.contains("host_value")), "bind variables are not columns");
    }

    @Test
    void oracleScopesKeepSameNamedVariablesApart() {
        Script s = script(Dialect.ORACLE, ORACLE);
        Set<String> e = edges(s);
        has(e, "TABLE:sales.refunds.amount -> VARIABLE:dw.p_other.v_max AGGREGATE",
                "VARIABLE:dw.p_other.v_max -> TABLE:dw.audit.top_amount PASS_THROUGH",
                "TABLE:sales.orders.amount -> VARIABLE:dw.pkg.g_last AGGREGATE",            // package variable
                "VARIABLE:dw.pkg.g_last -> TABLE:dw.audit.top_amount PASS_THROUGH");
        LineageGraph g = LineageGraph.of(s);
        assertNotEquals(node(g, "dw.p_load.v_max").key(), node(g, "dw.p_other.v_max").key());
        Set<String> otherUp = g.upstream(node(g, "dw.p_other.v_max"), false).stream()
                .map(x -> edge(x.edge())).collect(Collectors.toSet());
        assertFalse(otherUp.stream().anyMatch(x -> x.contains("sales.orders")), otherUp::toString);

        // The nested block's v_max is its own variable; the outer insert reads the outer one.
        List<ColumnNode> blockVars = g.nodes().stream()
                .filter(n -> n.ownerKind() == ColumnNode.OwnerKind.VARIABLE && n.column().equals("v_max")
                        && n.owner().startsWith("block@L")).toList();
        assertEquals(2, blockVars.stream().map(ColumnNode::ownerKey).distinct().count(), blockVars::toString);
    }

    // ----- SQL Server -----------------------------------------------------------------------------------

    private static final String TSQL = """
            create table dw.audit (cnt int, top_amount money, run_day date)
            GO
            create procedure dbo.p_load @from date, @factor int = 1
            as
            begin
              set nocount on;
              declare @cnt int, @max money = @factor * 10;
              declare @total money;
              declare c cursor for select id, amount from sales.orders where amount > @factor;
              select @cnt = count(*), @max = max(amount) from sales.orders where order_day = @from;
              insert into dw.audit (cnt, top_amount) values (@cnt, @max);
              set @total = (select sum(amount) from sales.refunds where refund_day = @from);
              set @total += @max;
              update dw.audit set top_amount = @total where run_day = @from and @@rowcount > 0;
              open c;
              fetch next from c into @cnt, @max;
              close c;
              deallocate c;
            end
            GO
            declare @x int = 5;
            select @x = id from t1;
            insert into dw.audit (cnt) values (@x);
            GO
            """;

    @Test
    void sqlServerSelectAssignmentThenInsertValues() {
        Set<String> e = edges(script(Dialect.SQLSERVER, TSQL));
        has(e, "TABLE:sales.orders.amount -> VARIABLE:dbo.p_load.@max AGGREGATE",
                "TABLE:sales.orders.order_day -> VARIABLE:dbo.p_load.@MAX.*rows* ~FILTER",
                "PARAMETER:dbo.p_load.@from -> VARIABLE:dbo.p_load.@CNT.*rows* ~FILTER",
                "VARIABLE:dbo.p_load.@cnt -> TABLE:dw.audit.cnt PASS_THROUGH",
                "VARIABLE:dbo.p_load.@max -> TABLE:dw.audit.top_amount PASS_THROUGH",
                "PARAMETER:dbo.p_load.@factor -> VARIABLE:dbo.p_load.@max EXPRESSION",          // DECLARE … = expr
                "TABLE:sales.refunds.amount -> VARIABLE:dbo.p_load.@total AGGREGATE",           // SET @v = (SELECT …)
                "PARAMETER:dbo.p_load.@from -> VARIABLE:dbo.p_load.@TOTAL.*rows* ~FILTER",
                "VARIABLE:dbo.p_load.@max -> VARIABLE:dbo.p_load.@total EXPRESSION",            // SET @v += …
                "VARIABLE:dbo.p_load.@total -> TABLE:dw.audit.top_amount PASS_THROUGH",
                "PARAMETER:dbo.p_load.@from -> TABLE:dw.audit.*rows* ~FILTER",
                "RESULT:result.id -> VARIABLE:dbo.p_load.@cnt PASS_THROUGH",                   // FETCH … INTO
                "PARAMETER:dbo.p_load.@factor -> RESULT:result.*rows* ~FILTER");
        assertFalse(e.stream().anyMatch(x -> x.toUpperCase().contains("ROWCOUNT")), "@@ functions are no variables");
        assertFalse(e.stream().anyMatch(x -> x.contains("RESULT:result.@")), "assigning SELECT has no result columns");
    }

    @Test
    void sqlServerBatchVariables() {
        Set<String> e = edges(script(Dialect.SQLSERVER, TSQL));
        has(e, "TABLE:t1.id -> VARIABLE:batch@L21.@x PASS_THROUGH",
                "VARIABLE:batch@L21.@x -> TABLE:dw.audit.cnt PASS_THROUGH");
    }

    // ----- PostgreSQL -----------------------------------------------------------------------------------

    private static final String PG = """
            create table dw.audit (cnt int, top_amount numeric, run_day date);
            create or replace function dw.f_load(p_day date, p_factor int default 1) returns int
            language plpgsql as $$
            declare
              v_cnt int;
              v_max numeric := p_factor * 10;
              v_total numeric;
              c cursor for select id, amount from sales.orders where amount > p_factor;
              v_id int;
            begin
              select count(*), max(amount) into strict v_cnt, v_max from sales.orders where order_day = p_day;
              update dw.audit set cnt = v_cnt, top_amount = v_max where run_day = p_day;
              v_total := v_max * 2 + $2;
              open c;
              fetch c into v_id, v_total;
              insert into dw.audit (cnt, top_amount) values (v_id, v_total);
              return v_cnt;
            end;
            $$;
            create function dw.f_sql(p_day date) returns bigint language sql as $$
              select count(*) from sales.orders where order_day = $1;
            $$;
            do $$
            declare
              v_n int;
            begin
              select count(*) into v_n from sales.orders where status = 'X';
              insert into dw.audit (cnt) values (v_n);
            end;
            $$;
            """;

    @Test
    void postgresSelectIntoStrictThenUpdate() {
        Set<String> e = edges(script(Dialect.POSTGRESQL, PG));
        has(e, "TABLE:sales.orders.amount -> VARIABLE:dw.f_load.v_max AGGREGATE",
                "TABLE:sales.orders.order_day -> VARIABLE:dw.f_load.V_CNT.*rows* ~FILTER",
                "PARAMETER:dw.f_load.p_day -> VARIABLE:dw.f_load.V_MAX.*rows* ~FILTER",
                "VARIABLE:dw.f_load.v_cnt -> TABLE:dw.audit.cnt PASS_THROUGH",
                "VARIABLE:dw.f_load.v_max -> TABLE:dw.audit.top_amount PASS_THROUGH",
                "PARAMETER:dw.f_load.p_day -> TABLE:dw.audit.*rows* ~FILTER");
        hasNot(e, "TABLE:dw.audit.v_cnt -> TABLE:dw.audit.cnt PASS_THROUGH");
    }

    @Test
    void postgresAssignmentsDeclarationsCursorsAndPositionalParameters() {
        Set<String> e = edges(script(Dialect.POSTGRESQL, PG));
        has(e, "PARAMETER:dw.f_load.p_factor -> VARIABLE:dw.f_load.v_max EXPRESSION",       // DECLARE … := expr
                "VARIABLE:dw.f_load.v_max -> VARIABLE:dw.f_load.v_total EXPRESSION",         // v_total := …
                "PARAMETER:dw.f_load.p_factor -> VARIABLE:dw.f_load.v_total EXPRESSION",     // $2
                "PARAMETER:dw.f_load.p_factor -> RESULT:result.*rows* ~FILTER",              // cursor query
                "RESULT:result.id -> VARIABLE:dw.f_load.v_id PASS_THROUGH",                  // FETCH … INTO
                "RESULT:result.amount -> VARIABLE:dw.f_load.v_total PASS_THROUGH",
                "VARIABLE:dw.f_load.v_id -> TABLE:dw.audit.cnt PASS_THROUGH",
                "PARAMETER:dw.f_sql.p_day -> RESULT:result.*rows* ~FILTER",                  // SQL function $1
                "TABLE:sales.orders.status -> VARIABLE:block@L23.V_N.*rows* ~FILTER",        // DO block
                "VARIABLE:block@L23.v_n -> TABLE:dw.audit.cnt PASS_THROUGH");
    }

    // ----- MySQL, MariaDB, Db2 --------------------------------------------------------------------------

    private static final String MYSQL = """
            create procedure p_load(in p_day date, out p_cnt int)
            begin
              declare v_cnt, v_max int default 0;
              declare v_id int;
              declare c cursor for select id, amount from sales.orders where order_day = p_day;
              select count(*), max(amount) into v_cnt, v_max from sales.orders where order_day = p_day;
              set v_max = v_max * 2, p_cnt = v_cnt;
              open c;
              fetch c into v_id, v_max;
              close c;
              insert into dw.audit (cnt, top_amount) values (v_cnt, v_max);
            end;
            set @a = (select max(y) from t2);
            select max(x) into @b from t1 where y = @a;
            insert into dw.audit (cnt) values (@b);
            """;

    private static void mysqlLike(Set<String> e) {
        has(e, "TABLE:sales.orders.amount -> VARIABLE:p_load.v_max AGGREGATE",
                "PARAMETER:p_load.p_day -> VARIABLE:p_load.V_CNT.*rows* ~FILTER",
                "VARIABLE:p_load.v_cnt -> PARAMETER:p_load.p_cnt PASS_THROUGH",
                "RESULT:result.id -> VARIABLE:p_load.v_id PASS_THROUGH",
                "VARIABLE:p_load.v_cnt -> TABLE:dw.audit.cnt PASS_THROUGH",
                "VARIABLE:p_load.v_max -> TABLE:dw.audit.top_amount PASS_THROUGH",
                "TABLE:t2.y -> VARIABLE:session.@a AGGREGATE",
                "TABLE:t1.x -> VARIABLE:session.@b AGGREGATE",
                "VARIABLE:session.@a -> VARIABLE:session.@B.*rows* ~FILTER",
                "VARIABLE:session.@b -> TABLE:dw.audit.cnt PASS_THROUGH");
    }

    @Test
    void mysqlSmoke() {
        mysqlLike(edges(script(Dialect.MYSQL, MYSQL)));
    }

    @Test
    void mariaDbSmoke() {
        mysqlLike(edges(script(Dialect.MARIADB, MYSQL)));
    }

    @Test
    void db2Smoke() {
        Set<String> e = edges(script(Dialect.DB2, """
                create procedure dw.p_load(in p_day date)
                language sql
                begin
                  declare v_cnt integer default 0;
                  declare v_max decimal(10,2);
                  declare v_ref decimal(10,2) default v_cnt + 1;
                  declare c cursor for select id, amount from sales.orders;
                  select count(*), max(amount) into v_cnt, v_max from sales.orders where order_day = p_day;
                  set v_ref = (select max(amount) from sales.refunds where refund_day = p_day);
                  fetch c into v_cnt, v_max;
                  insert into dw.audit (cnt, top_amount) values (v_cnt, v_max);
                end
                @
                """));
        has(e, "TABLE:sales.orders.amount -> VARIABLE:dw.p_load.v_max AGGREGATE",
                "PARAMETER:dw.p_load.p_day -> VARIABLE:dw.p_load.V_MAX.*rows* ~FILTER",
                "VARIABLE:dw.p_load.v_cnt -> VARIABLE:dw.p_load.v_ref EXPRESSION",
                "TABLE:sales.refunds.amount -> VARIABLE:dw.p_load.v_ref AGGREGATE",
                "RESULT:result.amount -> VARIABLE:dw.p_load.v_max PASS_THROUGH",
                "VARIABLE:dw.p_load.v_cnt -> TABLE:dw.audit.cnt PASS_THROUGH",
                "VARIABLE:dw.p_load.v_max -> TABLE:dw.audit.top_amount PASS_THROUGH");
    }

    // ----- edge cases ------------------------------------------------------------------------------------

    @Test
    void sqlServerEdgeCases() {
        Set<String> e = edges(script(Dialect.SQLSERVER, """
                create table dw.audit (cnt int, top_amount money)
                GO
                declare @a int = 1, @b int;
                declare c cursor for select id, amount, status from sales.orders;
                insert into dw.audit (cnt, top_amount) values (@a, 1), (@b, @a + @b + @a + @b + @a + @b + @a + @b + @a + @b);
                insert into dw.audit default values;
                fetch next from c into @a, @b;
                fetch next from c_unknown into @a;
                update dw.audit set @b = top_amount, cnt = @a;
                """));
        has(e, "VARIABLE:batch@L3.@a -> TABLE:dw.audit.cnt PASS_THROUGH",
                "VARIABLE:batch@L3.@b -> TABLE:dw.audit.cnt PASS_THROUGH",
                "VARIABLE:batch@L3.@a -> TABLE:dw.audit.top_amount EXPRESSION",
                "RESULT:result.id -> VARIABLE:batch@L3.@a PASS_THROUGH",
                "RESULT:result.amount -> VARIABLE:batch@L3.@b PASS_THROUGH");
        assertFalse(e.stream().anyMatch(x -> x.startsWith("RESULT:result.status")), "a third cursor column has no target");
    }

    @Test
    void postgresEdgeCases() {
        Set<String> e = edges(script(Dialect.POSTGRESQL, """
                create function dw.f_edge(int, p_b int) returns int language plpgsql as $$
                declare
                  r record;
                  rc refcursor;
                  v int;
                begin
                  select * into r from sales.orders where id = $1;
                  open rc for select amount from sales.refunds where id = p_b;
                  fetch rc into v;
                  fetch c_unknown into v;
                  v = r.amount + 1;
                  close rc;
                  return v;
                end;
                $$;
                """));
        has(e, "PARAMETER:dw.f_edge.$1 -> VARIABLE:dw.f_edge.R.*rows* ~FILTER",
                "TABLE:sales.refunds.amount -> RESULT:result.amount PASS_THROUGH",
                "PARAMETER:dw.f_edge.p_b -> RESULT:result.*rows* ~FILTER",
                "RESULT:result.amount -> VARIABLE:dw.f_edge.v PASS_THROUGH",
                "VARIABLE:dw.f_edge.r -> VARIABLE:dw.f_edge.v EXPRESSION");
    }

    // ----- INSERT … VALUES without variables ------------------------------------------------------------

    @Test
    void insertValuesMapsByPositionOrNotAtAll() {
        Set<String> e = edges(script(Dialect.ORACLE, """
                create table dw.t (a number, b number);
                insert into dw.t values (1, (select max(x) from s.src where y > 0));
                insert into dw.u values (1, 2);
                """));
        has(e, "TABLE:s.src.x -> TABLE:dw.t.b AGGREGATE", "TABLE:s.src.y -> TABLE:dw.t.*rows* ~FILTER");
        assertFalse(e.stream().anyMatch(x -> x.contains("dw.u")), "no columns known for dw.u");

        Set<String> pg = edges(script(Dialect.POSTGRESQL, """
                insert into dw.t (a, b) values (1, 2), (3, 4);
                select * from (values (1, 2)) v (a, b);
                """));
        assertTrue(pg.isEmpty(), pg::toString);
        Set<String> db2 = edges(script(Dialect.DB2, """
                create table dw.t (a integer, b integer);
                insert into dw.t values (1, 2), (3, 4);
                insert into dw.t values (v_unknown, 2);
                """));
        assertTrue(db2.isEmpty(), db2::toString);
    }
}
