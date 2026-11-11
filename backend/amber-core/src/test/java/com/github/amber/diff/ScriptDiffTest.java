package com.github.amber.diff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.diff.DiffResult.ColumnChange;
import com.github.amber.diff.DiffResult.ColumnChangeKind;
import com.github.amber.diff.DiffResult.Risk;
import com.github.amber.diff.DiffResult.StatementPair;
import com.github.amber.diff.DiffResult.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change impact scenarios on synthetic scripts: format-only edits, removed filters, switched column
 * sources, renamed CTEs, reordered statements, inserted temp-table steps, statements moved under an IF,
 * new findings, and fallback parsing, for Oracle, SQL Server and PostgreSQL first.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class ScriptDiffTest {

    static DiffResult diff(Dialect d, String before, String after) {
        return ScriptDiff.compare(d, before, after, Catalog.of(List.of()));
    }

    static List<StatementPair> flat(DiffResult r) {
        List<StatementPair> out = new ArrayList<>();
        r.statements().forEach(p -> flat(p, out));
        return out;
    }

    private static void flat(StatementPair p, List<StatementPair> out) {
        out.add(p);
        p.children().forEach(c -> flat(c, out));
    }

    static boolean hasReason(DiffResult r, Risk level, String code) {
        return r.summary().reasons().stream().anyMatch(x -> x.level() == level && x.code().equals(code));
    }

    static String explain(DiffResult r) {
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(r.summary())
                    + "\nstatements: " + flat(r).stream().map(p -> p.status() + " " + p.kind() + " " + p.target()).toList()
                    + "\ncolumns: " + r.lineage().columns().stream().map(ColumnChange::message).toList()
                    + "\nindirect: " + r.lineage().indirect().stream().map(DiffResult.IndirectChange::message).toList()
                    + "\nobjects: " + r.objects().stream().map(DiffResult.ObjectChange::message).toList()
                    + "\nshapes: " + r.shapes().stream().map(DiffResult.ShapeChange::message).toList()
                    + "\nfindings: " + r.findings().stream().map(f -> f.change() + " " + f.rule()).toList()
                    + "\nflow: " + r.controlFlow().stream().map(DiffResult.ControlFlowChange::message).toList();
        } catch (Exception e) {
            return e.toString();
        }
    }

    static long count(DiffResult r, Predicate<StatementPair> p) {
        return flat(r).stream().filter(p).count();
    }

    // ----- format only ------------------------------------------------------------------------------

    private static void assertFormatOnly(Dialect d, String before, String after, int statements) {
        DiffResult r = diff(d, before, after);
        assertEquals(statements, r.summary().statements().formatOnly(), explain(r));
        assertEquals(0, r.summary().statements().modified() + r.summary().statements().added()
                + r.summary().statements().removed() + r.summary().statements().moved(), explain(r));
        assertEquals(Risk.LOW, r.summary().risk(), explain(r));
        assertTrue(r.lineage().columns().isEmpty() && r.objects().isEmpty() && r.findings().isEmpty(), explain(r));
        assertTrue(r.summary().headline().startsWith("Formatting only"), r.summary().headline());
    }

    // ----- a refactoring that keeps the logic is not reported as moved conditions or new outputs ----------

    @Test
    void editingInsideADerivedTableKeepsItsJoinsAndFilters() {
        String before = """
                create table rpt as
                select a.acct_id, e.last_dt
                  from accounts a
                  left join (select * from (select distinct to_char(max(trn_date)) as last_dt, acct_no from txn
                                             where trn_code = '13011' group by acct_no) where 1=1 and last_dt <= '20251031') e
                    on trim(a.acct_id) = trim(e.acct_no)
                 where a.status = 'A';
                """;
        String after = """
                create table rpt as
                select a.acct_id, e.last_dt
                  from accounts a
                  left join (select * from (select to_char(max(trn_date)) as last_dt, acct_no from txn
                                             where trn_code = '13011' group by acct_no) where last_dt <= '20251031') e
                    on trim(a.acct_id) = trim(e.acct_no)
                 where a.status = 'A';
                """;
        DiffResult r = diff(Dialect.ORACLE, before, after);
        assertTrue(r.summary().reasons().stream().noneMatch(x -> x.code().startsWith("JOIN_") || x.code().startsWith("FILTER_")),
                r.summary().reasons()::toString);
    }

    @Test
    void longExpressionsThatOnlyChangeLayoutAreTheSameOutput() {
        String expr = "substr(loan_account_number, 0, 15) || '-' || substr(loan_account_number, 16, 4) || '-' || m.branch_code";
        String before = "select id from loans where id in (select " + expr.replace(" || ", "||") + " from loan_map m);";
        String after = "select id from loans where id in (select " + expr + " from loan_map m);";
        DiffResult r = diff(Dialect.ORACLE, before, after);
        assertTrue(r.summary().reasons().stream().noneMatch(x -> x.code().equals("SHAPE_OUTPUTS_CHANGED")), r.summary().reasons()::toString);
        assertTrue(ShapeComparer.sameOutput("SUBSTR(A,0,15)||'-'||SUBSTR(B…", "SUBSTR(A,0,15)||'-'||SUBSTR(B,1…"));
        assertTrue(!ShapeComparer.sameOutput("SUBSTR(A,0,15)||'-'||X", "SUBSTR(A,0,15)||'-'||Y"));
    }

    @Test
    void formatOnlyOracle() {
        assertFormatOnly(Dialect.ORACLE, """
                insert into rpt.daily_total (day_key, total)
                select o.day_key, sum(o.amount) from sales.orders o where o.status = 'PAID' group by o.day_key;
                delete from stage.load_log where created < sysdate - 30;
                """, """
                -- nightly totals
                INSERT INTO rpt.daily_total (day_key, total)
                SELECT o.day_key,
                       SUM(o.amount)
                  FROM sales.orders o
                 WHERE o.status = 'PAID'   /* paid only */
                 GROUP BY o.day_key;
                DELETE FROM stage.load_log WHERE created < SYSDATE - 30;
                """, 2);
    }

    @Test
    void formatOnlySqlServer() {
        assertFormatOnly(Dialect.SQLSERVER, """
                update dbo.customer set tier = 'GOLD' where total_spent > 1000;
                select c.id, c.name from dbo.customer c where c.tier = 'GOLD';
                """, """
                UPDATE dbo.customer
                   SET tier = 'GOLD'
                 WHERE total_spent > 1000;   -- promote
                SELECT c.id, c.name
                  FROM dbo.customer c
                 WHERE c.tier = 'GOLD';
                """, 2);
    }

    @Test
    void formatOnlyPostgres() {
        assertFormatOnly(Dialect.POSTGRESQL, """
                create table rpt.region_sum as select region, count(*) as n from crm.customer group by region;
                """, """
                CREATE TABLE rpt.region_sum AS
                SELECT region, COUNT(*) AS n
                  FROM crm.customer
                 GROUP BY region;
                """, 1);
    }

    @Test
    void identicalScriptsAreUnchanged() {
        String sql = "select a from t;\nselect b from u;\n";
        DiffResult r = diff(Dialect.POSTGRESQL, sql, sql);
        assertEquals(2, r.summary().statements().unchanged());
        assertEquals("No changes", r.summary().headline());
        assertEquals(Risk.LOW, r.summary().risk());
    }

    // ----- removed filter ---------------------------------------------------------------------------

    private static void assertFilterRemoved(Dialect d, String before, String after) {
        DiffResult r = diff(d, before, after);
        assertEquals(Risk.HIGH, r.summary().risk(), explain(r));
        assertTrue(hasReason(r, Risk.HIGH, "FILTER_REMOVED"), explain(r));
        assertTrue(r.lineage().indirect().stream().anyMatch(i -> i.role().equals("FILTER") && i.change().equals("REMOVED")),
                explain(r));
        StatementPair p = flat(r).stream().filter(x -> x.status() == Status.MODIFIED).findFirst().orElseThrow();
        assertEquals("DELETE", p.kind());
        assertFalse(p.hunks().isEmpty());
        assertTrue(p.hunks().get(0).beforeText().toUpperCase().contains("WHERE"), explain(r));
        assertEquals("", p.hunks().get(0).afterText());
    }

    @Test
    void deleteFilterRemovedOracle() {
        assertFilterRemoved(Dialect.ORACLE, """
                insert into stage.orders_copy select * from sales.orders;
                delete from stage.orders_copy where load_date < sysdate - 7;
                """, """
                insert into stage.orders_copy select * from sales.orders;
                delete from stage.orders_copy;
                """);
    }

    @Test
    void deleteFilterRemovedSqlServer() {
        assertFilterRemoved(Dialect.SQLSERVER, """
                delete from dbo.audit_trail where event_type = 'DEBUG' and created_at < getdate();
                """, """
                delete from dbo.audit_trail;
                """);
    }

    @Test
    void deleteFilterRemovedPostgres() {
        assertFilterRemoved(Dialect.POSTGRESQL, """
                delete from app.session_log where expires_at < now();
                select count(*) from app.session_log;
                """, """
                delete from app.session_log;
                select count(*) from app.session_log;
                """);
    }

    @Test
    void updateLosesOneFilterColumn() {
        DiffResult r = diff(Dialect.ORACLE, """
                update crm.customer set tier = 'B' where region = 'N' and score < 10;
                """, """
                update crm.customer set tier = 'B' where region = 'N';
                """);
        assertTrue(hasReason(r, Risk.HIGH, "FILTER_REMOVED"), explain(r));
    }

    // ----- column lineage ---------------------------------------------------------------------------

    private static void assertSourceSwitched(Dialect d, String before, String after, String column) {
        DiffResult r = diff(d, before, after);
        ColumnChange c = r.lineage().columns().stream()
                .filter(x -> x.kind() == ColumnChangeKind.SOURCES_CHANGED && x.column().equalsIgnoreCase(column))
                .findFirst().orElseThrow(() -> new AssertionError(explain(r)));
        assertTrue(c.removedSources().stream().anyMatch(s -> s.toLowerCase().endsWith("orders.amount")), explain(r));
        assertTrue(c.addedSources().stream().anyMatch(s -> s.toLowerCase().endsWith("orders.net_amount")), explain(r));
        assertNotNull(c.beforeRange());
        assertNotNull(c.afterRange());
        assertEquals(Risk.HIGH, r.summary().risk(), explain(r));
        assertTrue(hasReason(r, Risk.HIGH, "COLUMN_SOURCE_CHANGED"), explain(r));
        assertEquals(1, r.summary().statements().modified(), explain(r));
    }

    @Test
    void columnSourceSwitchOracle() {
        assertSourceSwitched(Dialect.ORACLE, """
                insert into rpt.order_fact (order_id, revenue)
                select o.order_id, o.amount from sales.orders o where o.status = 'PAID';
                """, """
                insert into rpt.order_fact (order_id, revenue)
                select o.order_id, o.net_amount from sales.orders o where o.status = 'PAID';
                """, "revenue");
    }

    @Test
    void columnSourceSwitchSqlServerThroughTempTable() {
        assertSourceSwitched(Dialect.SQLSERVER, """
                select o.order_id, o.amount as revenue into #paid from sales.orders o where o.status = 'PAID';
                insert into rpt.order_fact (order_id, revenue) select p.order_id, p.revenue from #paid p;
                """, """
                select o.order_id, o.net_amount as revenue into #paid from sales.orders o where o.status = 'PAID';
                insert into rpt.order_fact (order_id, revenue) select p.order_id, p.revenue from #paid p;
                """, "revenue");
    }

    @Test
    void columnSourceSwitchPostgresThroughCte() {
        assertSourceSwitched(Dialect.POSTGRESQL, """
                insert into rpt.order_fact (order_id, revenue)
                with paid as (select order_id, amount from sales.orders where status = 'PAID')
                select order_id, amount from paid;
                """, """
                insert into rpt.order_fact (order_id, revenue)
                with paid as (select order_id, net_amount as amount from sales.orders where status = 'PAID')
                select order_id, amount from paid;
                """, "revenue");
    }

    @Test
    void transformChangeIsReported() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                insert into rpt.cust_total (customer_id, total) select customer_id, amount from sales.orders;
                """, """
                insert into rpt.cust_total (customer_id, total) select customer_id, sum(amount) from sales.orders group by customer_id;
                """);
        assertTrue(r.lineage().columns().stream().anyMatch(c -> c.kind() == ColumnChangeKind.TRANSFORM_CHANGED
                && c.afterTransforms().contains("AGGREGATE")), explain(r));
        assertTrue(r.lineage().indirect().stream().anyMatch(i -> i.role().equals("GROUP") && i.change().equals("ADDED")),
                explain(r));
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.CLAUSE_ADDED
                && s.detail().equals("GROUP BY")), explain(r));
    }

    // ----- CTE renamed ------------------------------------------------------------------------------

    private static void assertCteRenamed(Dialect d, String before, String after) {
        DiffResult r = diff(d, before, after);
        assertTrue(r.lineage().columns().isEmpty(), explain(r));
        assertTrue(r.lineage().indirect().isEmpty(), explain(r));
        assertTrue(r.shapes().stream().allMatch(s -> s.kind() == DiffResult.ShapeChangeKind.RENAMED), explain(r));
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.RENAMED), explain(r));
        assertEquals(Risk.LOW, r.summary().risk(), explain(r));
    }

    @Test
    void cteRenamedPostgres() {
        assertCteRenamed(Dialect.POSTGRESQL, """
                with big as (select customer_id, amount from sales.orders where amount > 100)
                insert into rpt.big_orders (customer_id, amount) select b.customer_id, b.amount from big b;
                """, """
                with large_orders as (select customer_id, amount from sales.orders where amount > 100)
                insert into rpt.big_orders (customer_id, amount) select b.customer_id, b.amount from large_orders b;
                """);
    }

    @Test
    void cteRenamedSqlServer() {
        assertCteRenamed(Dialect.SQLSERVER, """
                with big as (select customer_id, amount from sales.orders where amount > 100)
                insert into rpt.big_orders (customer_id, amount) select b.customer_id, b.amount from big b;
                """, """
                with large_orders as (select customer_id, amount from sales.orders where amount > 100)
                insert into rpt.big_orders (customer_id, amount) select b.customer_id, b.amount from large_orders b;
                """);
    }

    @Test
    void cteRenamedOracle() {
        assertCteRenamed(Dialect.ORACLE, """
                insert into rpt.big_orders (customer_id, amount)
                with big as (select customer_id, amount from sales.orders where amount > 100)
                select b.customer_id, b.amount from big b;
                """, """
                insert into rpt.big_orders (customer_id, amount)
                with large_orders as (select customer_id, amount from sales.orders where amount > 100)
                select b.customer_id, b.amount from large_orders b;
                """);
    }

    @Test
    void aliasRenameIsLow() {
        DiffResult r = diff(Dialect.ORACLE, """
                insert into rpt.x (id) select o.id from sales.orders o;
                """, """
                insert into rpt.x (id) select ord.id from sales.orders ord;
                """);
        assertEquals(Risk.LOW, r.summary().risk(), explain(r));
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.RENAMED), explain(r));
    }

    // ----- reorder ----------------------------------------------------------------------------------

    @Test
    void reorderedStatementsAreMoved() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                insert into a.t1 select * from s.src1;
                insert into a.t2 select * from s.src2;
                insert into a.t3 select * from s.src3;
                select 1;
                """, """
                insert into a.t3 select * from s.src3;
                insert into a.t1 select * from s.src1;
                insert into a.t2 select * from s.src2;
                select 1;
                """);
        assertEquals(1, r.summary().statements().moved(), explain(r));
        assertEquals(3, r.summary().statements().unchanged(), explain(r));
        StatementPair moved = flat(r).stream().filter(p -> p.status() == Status.MOVED).findFirst().orElseThrow();
        assertTrue(moved.reordered());
        assertEquals(2, moved.beforeId());
        assertEquals(0, moved.afterId());
        assertTrue(hasReason(r, Risk.MEDIUM, "ORDER_CHANGED"), explain(r));
        assertEquals("INSERT", r.statements().get(0).kind());
    }

    @Test
    void reorderedOracle() {
        DiffResult r = diff(Dialect.ORACLE, """
                select a from t1;
                select b from t2;
                """, """
                select b from t2;
                select a from t1;
                """);
        assertEquals(1, r.summary().statements().moved(), explain(r));
        assertEquals(Risk.LOW, r.summary().risk(), explain(r));
    }

    // ----- temp table step inserted -------------------------------------------------------------------

    @Test
    void tempTableStepInsertedSqlServer() {
        DiffResult r = diff(Dialect.SQLSERVER, """
                insert into rpt.cust_sales (customer_id, total)
                select o.customer_id, sum(o.amount) from sales.orders o group by o.customer_id;
                """, """
                select o.customer_id, o.amount into #recent from sales.orders o where o.order_date > '2026-01-01';
                insert into rpt.cust_sales (customer_id, total)
                select r.customer_id, sum(r.amount) from #recent r group by r.customer_id;
                drop table #recent;
                """);
        assertEquals(2, r.summary().statements().added(), explain(r));
        assertEquals(1, r.summary().statements().modified(), explain(r));
        assertTrue(r.objects().stream().anyMatch(o -> o.kind() == DiffResult.ObjectChangeKind.ADDED
                && o.object().equalsIgnoreCase("#recent") && "INTERMEDIATE".equals(o.afterRole())), explain(r));
        assertTrue(r.objects().stream().anyMatch(o -> o.scope() == DiffResult.Scope.STATEMENT
                && o.kind() == DiffResult.ObjectChangeKind.REF_ADDED), explain(r));
        // the final column still comes from sales.orders through the temp table
        assertTrue(r.lineage().columns().stream().noneMatch(c -> c.kind() == ColumnChangeKind.SOURCES_CHANGED
                && c.target().equalsIgnoreCase("rpt.cust_sales")), explain(r));
        assertTrue(r.lineage().indirect().stream().anyMatch(i -> i.target().equalsIgnoreCase("rpt.cust_sales")
                && i.role().equals("FILTER") && i.change().equals("ADDED")), explain(r));
    }

    @Test
    void tempTableStepInsertedPostgres() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                truncate rpt.cust_sales;
                insert into rpt.cust_sales (customer_id, total) select customer_id, amount from sales.orders;
                """, """
                truncate rpt.cust_sales;
                create temp table recent as select customer_id, amount from sales.orders where order_date > current_date - 30;
                insert into rpt.cust_sales (customer_id, total) select customer_id, amount from recent;
                """);
        assertTrue(count(r, p -> p.status() == Status.ADDED && p.kind().equals("CREATE_TABLE_AS")) == 1, explain(r));
        assertTrue(r.objects().stream().anyMatch(o -> o.object().equalsIgnoreCase("recent")), explain(r));
        assertTrue(r.lineage().columns().stream().anyMatch(c -> c.kind() == ColumnChangeKind.ADDED
                && c.target().equalsIgnoreCase("recent")), explain(r));
    }

    @Test
    void tempTableStepInsertedOracle() {
        DiffResult r = diff(Dialect.ORACLE, """
                insert into rpt.cust_sales (customer_id, total) select customer_id, amount from sales.orders;
                """, """
                create global temporary table tmp_recent (customer_id number, amount number);
                insert into tmp_recent (customer_id, amount) select customer_id, amount from sales.orders where status = 'X';
                insert into rpt.cust_sales (customer_id, total) select customer_id, amount from tmp_recent;
                """);
        assertTrue(r.summary().statements().added() >= 2, explain(r));
        assertTrue(r.objects().stream().anyMatch(o -> o.object().equalsIgnoreCase("tmp_recent")
                && o.kind() == DiffResult.ObjectChangeKind.ADDED), explain(r));
    }

    // ----- control flow -----------------------------------------------------------------------------

    private static void assertMovedIntoIf(Dialect d, String before, String after) {
        DiffResult r = diff(d, before, after);
        assertEquals(1, r.controlFlow().size(), explain(r));
        DiffResult.ControlFlowChange c = r.controlFlow().get(0);
        assertEquals("UPDATE", c.kind(), explain(r));
        assertTrue(c.message().contains("moved into IF"), c.message());
        assertTrue(c.afterPath().size() > c.beforePath().size(), explain(r));
        assertTrue(hasReason(r, Risk.MEDIUM, "CONTROL_FLOW_CHANGED"), explain(r));
        assertTrue(flat(r).stream().anyMatch(p -> p.status() == Status.MOVED && p.kind().equals("UPDATE")), explain(r));
    }

    @Test
    void updateMovedIntoIfOracle() {
        assertMovedIntoIf(Dialect.ORACLE, """
                create or replace procedure p_refresh(p_mode in number) as
                begin
                  update crm.customer set tier = 'A' where score > 90;
                  delete from stage.tmp_cust;
                end;
                /
                """, """
                create or replace procedure p_refresh(p_mode in number) as
                begin
                  if p_mode = 1 then
                    update crm.customer set tier = 'A' where score > 90;
                  end if;
                  delete from stage.tmp_cust;
                end;
                /
                """);
    }

    @Test
    void updateMovedIntoIfSqlServer() {
        assertMovedIntoIf(Dialect.SQLSERVER, """
                create procedure dbo.p_refresh @mode int as
                begin
                  update crm.customer set tier = 'A' where score > 90;
                  delete from stage.tmp_cust;
                end
                go
                """, """
                create procedure dbo.p_refresh @mode int as
                begin
                  if @mode = 1
                  begin
                    update crm.customer set tier = 'A' where score > 90;
                  end
                  delete from stage.tmp_cust;
                end
                go
                """);
    }

    @Test
    void updateMovedIntoIfPostgres() {
        assertMovedIntoIf(Dialect.POSTGRESQL, """
                create or replace function app.refresh(p_mode int) returns void as $$
                begin
                  update crm.customer set tier = 'A' where score > 90;
                  delete from stage.tmp_cust;
                end;
                $$ language plpgsql;
                """, """
                create or replace function app.refresh(p_mode int) returns void as $$
                begin
                  if p_mode = 1 then
                    update crm.customer set tier = 'A' where score > 90;
                  end if;
                  delete from stage.tmp_cust;
                end;
                $$ language plpgsql;
                """);
    }

    @Test
    void statementMovedOutOfIf() {
        DiffResult r = diff(Dialect.ORACLE, """
                begin
                  if v_x = 1 then
                    update crm.customer set tier = 'A' where score > 90;
                    commit;
                  end if;
                end;
                /
                """, """
                begin
                  update crm.customer set tier = 'A' where score > 95;
                  if v_x = 1 then
                    commit;
                  end if;
                end;
                /
                """);
        assertTrue(r.controlFlow().stream().anyMatch(c -> c.message().contains("moved out of IF")), explain(r));
        assertTrue(flat(r).stream().anyMatch(p -> p.status() == Status.MODIFIED && p.kind().equals("UPDATE")
                && !p.hunks().isEmpty()), explain(r));
    }

    // ----- findings ---------------------------------------------------------------------------------

    private static void assertNewNotIn(Dialect d, String before, String after) {
        DiffResult r = diff(d, before, after);
        assertTrue(r.findings().stream().anyMatch(f -> f.change().equals("NEW") && f.rule().equals("AMB-S004")
                && f.afterRange() != null), explain(r));
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.BLOCK_ADDED
                && s.block().contains("NOT IN")), explain(r));
        assertTrue(r.summary().newFindings() >= 1);
    }

    @Test
    void newNotInFindingOracle() {
        assertNewNotIn(Dialect.ORACLE, """
                insert into rpt.open_orders (order_id) select o.order_id from sales.orders o where o.status = 'OPEN';
                """, """
                insert into rpt.open_orders (order_id) select o.order_id from sales.orders o where o.status = 'OPEN'
                   and o.order_id not in (select r.order_id from sales.refunds r);
                """);
    }

    @Test
    void newNotInFindingSqlServer() {
        assertNewNotIn(Dialect.SQLSERVER, """
                select o.order_id from sales.orders o where o.status = 'OPEN';
                """, """
                select o.order_id from sales.orders o where o.status = 'OPEN'
                   and o.order_id not in (select r.order_id from sales.refunds r);
                """);
    }

    @Test
    void newNotInFindingPostgres() {
        assertNewNotIn(Dialect.POSTGRESQL, """
                select o.order_id from sales.orders o where o.status = 'OPEN';
                """, """
                select o.order_id from sales.orders o where o.status = 'OPEN'
                   and o.order_id not in (select r.order_id from sales.refunds r);
                """);
    }

    @Test
    void resolvedFinding() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                select o.order_id from sales.orders o where o.order_id not in (select r.order_id from sales.refunds r);
                """, """
                select o.order_id from sales.orders o
                 where not exists (select 1 from sales.refunds r where r.order_id = o.order_id);
                """);
        assertTrue(r.findings().stream().anyMatch(f -> f.change().equals("RESOLVED") && f.rule().equals("AMB-S004")),
                explain(r));
        assertEquals(1, r.summary().resolvedFindings(), explain(r));
    }

    @Test
    void unchangedFindingsAreNotReported() {
        String sql = "select o.order_id from sales.orders o where o.order_id not in (select r.order_id from sales.refunds r);\n";
        DiffResult r = diff(Dialect.POSTGRESQL, sql, "select 1;\n" + sql);
        assertTrue(r.findings().isEmpty(), explain(r));
    }

    // ----- other risk rules -------------------------------------------------------------------------

    @Test
    void addedDropIsHigh() {
        DiffResult r = diff(Dialect.ORACLE, """
                insert into rpt.x (id) select id from sales.orders;
                """, """
                insert into rpt.x (id) select id from sales.orders;
                drop table sales.orders_archive;
                """);
        assertEquals(Risk.HIGH, r.summary().risk(), explain(r));
        assertTrue(hasReason(r, Risk.HIGH, "DROP_ADDED"), explain(r));
        assertTrue(hasReason(r, Risk.HIGH, "DESTRUCTIVE_ACCESS"), explain(r));
    }

    @Test
    void addedDeletesAndTruncates() {
        DiffResult r = diff(Dialect.POSTGRESQL, "select 1;\n", """
                select 1;
                delete from app.users;
                update app.accounts set active = false where last_login < now();
                truncate app.events;
                """);
        assertTrue(hasReason(r, Risk.HIGH, "DELETE_ADDED"), explain(r));
        assertTrue(hasReason(r, Risk.MEDIUM, "UPDATE_ADDED"), explain(r));
        assertTrue(hasReason(r, Risk.HIGH, "TRUNCATE_ADDED"), explain(r));
    }

    @Test
    void targetChangedIsHigh() {
        DiffResult r = diff(Dialect.SQLSERVER, """
                insert into rpt.sales_v1 (id, amt) select o.id, o.amount from sales.orders o where o.region = 'EU';
                """, """
                insert into rpt.sales_v2 (id, amt) select o.id, o.amount from sales.orders o where o.region = 'EU';
                """);
        assertEquals(1, r.summary().statements().modified(), explain(r));
        assertTrue(hasReason(r, Risk.HIGH, "TARGET_CHANGED"), explain(r));
        assertTrue(r.lineage().columns().stream().anyMatch(c -> c.kind() == ColumnChangeKind.REMOVED), explain(r));
        assertTrue(r.lineage().columns().stream().anyMatch(c -> c.kind() == ColumnChangeKind.ADDED), explain(r));
    }

    @Test
    void removedStatementsAndObjects() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                insert into rpt.a (id) select id from s.x;
                insert into rpt.b (id) select id from s.y;
                """, """
                insert into rpt.a (id) select id from s.x;
                """);
        assertEquals(1, r.summary().statements().removed(), explain(r));
        assertTrue(hasReason(r, Risk.MEDIUM, "STATEMENT_REMOVED"), explain(r));
        assertTrue(r.objects().stream().anyMatch(o -> o.kind() == DiffResult.ObjectChangeKind.REMOVED
                && o.object().equals("rpt.b")), explain(r));
        assertTrue(hasReason(r, Risk.MEDIUM, "TARGET_DROPPED"), explain(r));
    }

    @Test
    void joinAndShapeChanges() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                insert into rpt.r (id, name, amt)
                select o.id, c.name, o.amount from sales.orders o join crm.customer c on c.id = o.customer_id;
                """, """
                insert into rpt.r (id, name, amt)
                select distinct o.id, c.name, o.amount from sales.orders o join crm.customer c on c.id = o.customer_id
                  join crm.region g on g.id = c.region_id where g.active = 1;
                """);
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.JOIN_COUNT_CHANGED), explain(r));
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.SOURCE_ADDED), explain(r));
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.CLAUSE_ADDED
                && s.detail().equals("DISTINCT")), explain(r));
        assertTrue(r.lineage().indirect().stream().anyMatch(i -> i.role().equals("JOIN") && i.change().equals("ADDED")),
                explain(r));
        assertEquals(Risk.MEDIUM, r.summary().risk(), explain(r));
    }

    @Test
    void outputOrderAndSetOperator() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                select a, b from s.t union select a, b from s.u;
                """, """
                select b, a from s.t union all select b, a from s.u;
                """);
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.SET_OPERATOR_CHANGED), explain(r));
        assertTrue(r.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.OUTPUT_ORDER_CHANGED), explain(r));
        DiffResult r2 = diff(Dialect.POSTGRESQL, "select a, b from s.t where a > 1 order by a;\n",
                "select a, c from s.t where a > 1;\n");
        assertTrue(r2.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.OUTPUTS_CHANGED
                && s.detail().contains("+c") && s.detail().contains("-b")), explain(r2));
        assertTrue(r2.shapes().stream().anyMatch(s -> s.kind() == DiffResult.ShapeChangeKind.CLAUSE_REMOVED
                && s.detail().equals("ORDER BY")), explain(r2));
    }

    @Test
    void modifiedSelectWithoutImpactIsLow() {
        DiffResult r = diff(Dialect.POSTGRESQL, "select a from s.t where a > 1;\n", "select a from s.t where a > 2;\n");
        assertEquals(1, r.summary().statements().modified(), explain(r));
        assertEquals(Risk.LOW, r.summary().risk(), explain(r));
        assertTrue(hasReason(r, Risk.LOW, "MODIFIED"), explain(r));
        StatementPair p = r.statements().get(0);
        assertEquals(1, p.hunks().size());
        assertEquals("1", p.hunks().get(0).beforeText());
        assertEquals("2", p.hunks().get(0).afterText());
        assertTrue(p.similarity() > 0.5 && p.similarity() < 1);
    }

    @Test
    void newSourceAndRoleChanges() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                insert into stage.s (id) select id from src.a;
                """, """
                insert into stage.s (id) select id from src.a;
                insert into rpt.final (id) select s.id from stage.s s join src.b b on b.id = s.id;
                """);
        assertTrue(r.objects().stream().anyMatch(o -> o.kind() == DiffResult.ObjectChangeKind.ROLE_CHANGED
                && o.object().equals("stage.s") && o.beforeRole().equals("TARGET") && o.afterRole().equals("INTERMEDIATE")),
                explain(r));
        assertTrue(hasReason(r, Risk.MEDIUM, "NEW_SOURCE"), explain(r));
        assertTrue(hasReason(r, Risk.HIGH, "NEW_TARGET"), explain(r));
    }

    @Test
    void accessChangesOnExistingObject() {
        DiffResult r = diff(Dialect.ORACLE, """
                insert into rpt.x (id) select id from sales.orders;
                """, """
                delete from sales.orders where id < 0;
                """);
        assertTrue(r.objects().stream().anyMatch(o -> o.kind() == DiffResult.ObjectChangeKind.ACCESS_ADDED
                && "DELETE".equals(o.access())), explain(r));
        assertTrue(r.objects().stream().anyMatch(o -> o.kind() == DiffResult.ObjectChangeKind.ACCESS_REMOVED
                && "READ".equals(o.access())), explain(r));
        assertTrue(hasReason(r, Risk.HIGH, "DESTRUCTIVE_ACCESS"), explain(r));
    }

    @Test
    void writeRemovedAndKindChanged() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                insert into work.t (id) select id from src.a;
                select id from work.t;
                """, """
                create temp table t (id int);
                select id from t;
                """);
        assertTrue(r.objects().stream().anyMatch(o -> o.kind() == DiffResult.ObjectChangeKind.KIND_CHANGED
                || o.kind() == DiffResult.ObjectChangeKind.ACCESS_REMOVED), explain(r));
    }

    // ----- robustness -------------------------------------------------------------------------------

    @Test
    void fallbackWhenStrictParsingFails() {
        DiffResult r = diff(Dialect.POSTGRESQL, """
                select a from s.t;
                delete from s.u where id = 1;
                """, """
                select a from s.t;
                selec broken from;
                delete from s.u where id = 1;
                """);
        assertTrue(r.after().fallback(), explain(r));
        assertFalse(r.before().fallback());
        assertTrue(r.after().unparsed() >= 1);
        assertFalse(r.after().diagnostics().isEmpty());
        assertTrue(r.summary().notes().stream().anyMatch(n -> n.contains("fallback")), explain(r));
        assertTrue(hasReason(r, Risk.MEDIUM, "UNPARSED_CHANGE"), explain(r));
        assertEquals(2, r.summary().statements().unchanged(), explain(r));
    }

    @Test
    void emptyScripts() {
        DiffResult r = diff(Dialect.POSTGRESQL, "", "select 1;");
        assertEquals(1, r.summary().statements().added());
        DiffResult r2 = ScriptDiff.compare(Dialect.POSTGRESQL, "select 1;", "", null);
        assertEquals(1, r2.summary().statements().removed());
    }

    @Test
    void serializesToJson() throws Exception {
        DiffResult r = diff(Dialect.ORACLE, """
                insert into rpt.order_fact (order_id, revenue) select o.order_id, o.amount from sales.orders o;
                delete from stage.x where d < sysdate;
                """, """
                insert into rpt.order_fact (order_id, revenue) select o.order_id, o.net_amount from sales.orders o;
                delete from stage.x;
                """);
        String json = new ObjectMapper().writeValueAsString(r);
        assertTrue(json.contains("\"status\":\"MODIFIED\""), json);
        assertTrue(json.contains("\"risk\":\"HIGH\""), json);
        assertTrue(json.contains("\"hunks\""), json);
        var tree = new ObjectMapper().readTree(json);
        assertEquals("oracle", tree.get("dialect").asText());
        assertTrue(tree.get("statements").get(0).get("beforeRange").get("start").has("line"));
    }

    // ----- smoke on other dialects --------------------------------------------------------------------

    @Test
    void mysqlSmoke() {
        DiffResult r = diff(Dialect.MYSQL, """
                insert into rpt.t (id, amt) select id, amount from shop.orders;
                delete from shop.cart where created < now();
                """, """
                INSERT INTO rpt.t (id, amt) SELECT id, amount FROM shop.orders;
                delete from shop.cart;
                """);
        assertEquals(1, r.summary().statements().formatOnly(), explain(r));
        assertEquals(Risk.HIGH, r.summary().risk(), explain(r));
    }

    @Test
    void db2Smoke() {
        DiffResult r = diff(Dialect.DB2, """
                insert into rpt.t (id, amt) select id, amount from shop.orders;
                """, """
                insert into rpt.t (id, amt) select id, net_amount from shop.orders;
                """);
        assertTrue(r.lineage().columns().stream().anyMatch(c -> c.kind() == ColumnChangeKind.SOURCES_CHANGED), explain(r));
    }

    @Test
    void sqliteSmoke() {
        DiffResult r = diff(Dialect.SQLITE, """
                create table t (id integer);
                insert into t select id from s;
                """, """
                insert into t select id from s;
                create table t (id integer);
                """);
        assertEquals(1, r.summary().statements().moved(), explain(r));
    }

    @Test
    void mariadbAndH2Smoke() {
        assertEquals(Risk.LOW, diff(Dialect.MARIADB, "select 1;", "SELECT 1;").summary().risk());
        assertEquals(Risk.LOW, diff(Dialect.H2, "select 1;", "SELECT  1;").summary().risk());
    }
}
