package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnEvidence;
import com.github.amber.core.lineage.ColumnEvidence.Kind;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unqualified columns placed by the script's own column evidence: the scanner that builds the index, the
 * index's choice, and its use in lineage and AMB-I002 across dialects. Metadata always comes first.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ColumnEvidenceTest {

    // ----- helpers ----------------------------------------------------------------------------------

    private static ColumnEvidence scan(Dialect d, String sql) {
        return ColumnEvidenceScanner.scan(d, SourceText.of(sql));
    }

    private static ColumnEvidence.Fact fact(ColumnEvidence e, Dialect d, String table, String column) {
        return e.find(ObjectName.parse(d, table), column);
    }

    private static Script build(Dialect d, String sql, TableMeta... tables) {
        DialectFrontend f = DialectFrontends.get(d);
        ScriptParse p = f.parse(SourceText.of(sql));
        assertEquals(ScriptStatus.SUCCESS, p.status(), () -> d + ": " + p.diagnostics());
        return ScriptIrBuilder.build(f, p, Catalog.of(Arrays.asList(tables)));
    }

    /** Source columns of every edge of the statement at {@code index} (top level), as {@code KIND:owner.column}. */
    private static Set<String> sources(Script s, int index) {
        Statement st = s.statements().get(index);
        Set<String> out = new TreeSet<>();
        st.forEach(x -> {
            if (x.lineage() != null) {
                for (ColumnEdge e : x.lineage().edges()) {
                    out.add(e.from().ownerKind() + ":" + e.from().toString().toLowerCase(java.util.Locale.ROOT));
                }
            }
        });
        return out;
    }

    private static List<String> i002(Script s, TableMeta... tables) {
        List<String> out = new ArrayList<>();
        for (Finding f : Findings.of(s, Catalog.of(Arrays.asList(tables)))) {
            if (f.rule().equals("AMB-I002")) {
                out.add(f.range().start().line() + ":" + f.message());
            }
        }
        return out;
    }

    private static TableMeta table(String name, String... columns) {
        List<TableMeta.ColumnMeta> cols = new ArrayList<>();
        for (String c : columns) {
            cols.add(new TableMeta.ColumnMeta(c, "INTEGER", true));
        }
        return new TableMeta(null, null, name, "TABLE", cols, List.of(), List.of(), List.of(), 100, "test-pack");
    }

    // ----- the scanner ------------------------------------------------------------------------------

    @Test
    void qualifiedReferencesThroughAliasesAndNames() {
        ColumnEvidence e = scan(Dialect.ORACLE, """
                select o.cust_id, orders.status, sales.items.qty, x.nextval, pkg.f(o.amount), o.price(+)
                  from orders o, sales.items
                 where o.id = 1;
                """);
        ColumnEvidence.Fact f = fact(e, Dialect.ORACLE, "orders", "CUST_ID");
        assertNotNull(f);
        assertEquals(Kind.QUALIFIED_REFERENCE, f.kind());
        assertEquals(1, f.line());
        assertEquals("orders", f.table());
        assertNotNull(fact(e, Dialect.ORACLE, "orders", "status"));
        assertNotNull(fact(e, Dialect.ORACLE, "orders", "amount"));
        assertNotNull(fact(e, Dialect.ORACLE, "orders", "price"), "Oracle (+) is no call");
        assertNotNull(fact(e, Dialect.ORACLE, "sales.items", "qty"));
        assertNotNull(fact(e, Dialect.ORACLE, "items", "qty"), "an unqualified name matches its qualified twin");
        assertNull(fact(e, Dialect.ORACLE, "other.items", "qty"), "two different schemas are different tables");
        assertNull(fact(e, Dialect.ORACLE, "pkg", "f"), "a call is no column");
        assertNull(fact(e, Dialect.ORACLE, "x", "nextval"), "x is no table of the statement");
        assertNull(fact(e, Dialect.ORACLE, "sales", "items"), "a table name is no column");
    }

    @Test
    void insertUpdateAndMergeColumnLists() {
        ColumnEvidence e = scan(Dialect.ORACLE, """
                insert into orders (order_id, cust_id, "Amount") values (1, 2, 3);
                update customers set name = 'x', region = case when a = 1 then 'EU' end, (p, q) = (select 1, 2 from dual)
                 where id = 1;
                merge into stock s using (select 1 item from dual) n on (s.item = n.item)
                 when matched then update set qty = 1, s.updated = sysdate
                 when not matched then insert (item, qty) values (n.item, 1);
                insert all into log_a (a1, a2) values (1, 2) into log_b (b1) values (3) select 1 from dual;
                select amount into v_amount from orders where order_id = 1;
                """);
        assertEquals(Kind.INSERT_COLUMN_LIST, fact(e, Dialect.ORACLE, "orders", "cust_id").kind());
        assertEquals("Amount", fact(e, Dialect.ORACLE, "orders", "AMOUNT").column());
        assertEquals(Kind.UPDATE_SET, fact(e, Dialect.ORACLE, "customers", "name").kind());
        assertEquals(2, fact(e, Dialect.ORACLE, "customers", "name").line());
        assertNotNull(fact(e, Dialect.ORACLE, "customers", "region"), "CASE … END inside a SET value");
        assertNull(fact(e, Dialect.ORACLE, "customers", "a"));
        assertNull(fact(e, Dialect.ORACLE, "customers", "id"), "WHERE is no evidence");
        assertEquals(Kind.MERGE_ACTION, fact(e, Dialect.ORACLE, "stock", "qty").kind());
        assertEquals(Kind.QUALIFIED_REFERENCE, fact(e, Dialect.ORACLE, "stock", "item").kind());
        assertNotNull(fact(e, Dialect.ORACLE, "stock", "updated"));
        assertNotNull(fact(e, Dialect.ORACLE, "log_a", "a2"));
        assertNotNull(fact(e, Dialect.ORACLE, "log_b", "b1"));
        assertNull(fact(e, Dialect.ORACLE, "v_amount", "amount"));
        assertEquals(11, e.size());
        assertFalse(e.isEmpty());
    }

    @Test
    void sqlServerBatchesAliasesAndTableVariables() {
        ColumnEvidence e = scan(Dialect.SQLSERVER, """
                UPDATE o SET status = 'PAID', @n = 1 FROM dbo.orders o JOIN customers c ON c.id = o.cust_id
                INSERT dbo.audit (who, [when]) VALUES ('me', GETDATE())
                DECLARE @t TABLE (k INT)
                CREATE TABLE #stage (k INT, v INT)
                GO
                SELECT x.a FROM t1 x JOIN t2 x2 ON x2.b = x.a
                SELECT x.c FROM t3 x
                """);
        assertEquals(Kind.UPDATE_SET, fact(e, Dialect.SQLSERVER, "dbo.orders", "status").kind());
        assertNull(fact(e, Dialect.SQLSERVER, "o", "status"), "o is the alias of dbo.orders");
        assertNull(fact(e, Dialect.SQLSERVER, "dbo.orders", "@n"));
        assertNotNull(fact(e, Dialect.SQLSERVER, "customers", "id"));
        assertNotNull(fact(e, Dialect.SQLSERVER, "orders", "cust_id"));
        assertEquals(Kind.INSERT_COLUMN_LIST, fact(e, Dialect.SQLSERVER, "dbo.audit", "when").kind());
        assertTrue(e.definedByScript(ObjectName.parse(Dialect.SQLSERVER, "@t")));
        assertTrue(e.definedByScript(ObjectName.parse(Dialect.SQLSERVER, "#stage")));
        assertFalse(e.definedByScript(ObjectName.parse(Dialect.SQLSERVER, "dbo.orders")));
        // statements without semicolons are cut at their keywords: x is t1 in one and t3 in the other
        assertNotNull(fact(e, Dialect.SQLSERVER, "t1", "a"));
        assertNotNull(fact(e, Dialect.SQLSERVER, "t2", "b"));
    }

    @Test
    void aliasForTwoTablesInOneStatementIsIgnored() {
        ColumnEvidence e = scan(Dialect.POSTGRESQL, """
                select x.a from t1 x where exists (select 1 from t2 x where x.b = 1);
                """);
        assertNull(fact(e, Dialect.POSTGRESQL, "t1", "a"));
        assertNull(fact(e, Dialect.POSTGRESQL, "t2", "b"));
    }

    @Test
    void postgresqlBodiesAndUpserts() {
        ColumnEvidence e = scan(Dialect.POSTGRESQL, """
                create table if not exists stage (k int);
                create function f() returns void as $body$
                begin
                  insert into orders as o (id, cust_id) values (1, 2)
                    on conflict (id) do update set status = excluded.status;
                  update only customers c set region = 'EU' where c.id = 1;
                end
                $body$ language plpgsql;
                select * from generate_series(1, 3) g, items i where i.sku = 'a';
                """);
        ColumnEvidence.Fact f = fact(e, Dialect.POSTGRESQL, "orders", "cust_id");
        assertEquals(4, f.line(), "lines count inside the body");
        assertEquals(Kind.UPDATE_SET, fact(e, Dialect.POSTGRESQL, "orders", "status").kind());
        assertEquals(Kind.UPDATE_SET, fact(e, Dialect.POSTGRESQL, "customers", "region").kind());
        assertNotNull(fact(e, Dialect.POSTGRESQL, "customers", "id"));
        assertNotNull(fact(e, Dialect.POSTGRESQL, "items", "sku"));
        assertNull(fact(e, Dialect.POSTGRESQL, "generate_series", "g"));
        assertTrue(e.definedByScript(ObjectName.parse(Dialect.POSTGRESQL, "stage")));
    }

    @Test
    void mysqlInsertSetAndDuplicateKey() {
        ColumnEvidence e = scan(Dialect.MYSQL, """
                insert ignore into orders set cust_id = 1, status = 'NEW';
                insert into customers (id, name) values (1, 'a') on duplicate key update name = values(name), seen = 1;
                update orders o join customers c on c.id = o.cust_id set o.total = 1, flag = 1;
                """);
        assertEquals(Kind.INSERT_COLUMN_LIST, fact(e, Dialect.MYSQL, "orders", "status").kind());
        assertEquals(Kind.UPDATE_SET, fact(e, Dialect.MYSQL, "customers", "seen").kind());
        assertNotNull(fact(e, Dialect.MYSQL, "orders", "total"));
        assertNull(fact(e, Dialect.MYSQL, "orders", "flag"), "a multi-table UPDATE places only qualified columns");
        assertNull(fact(e, Dialect.MYSQL, "customers", "flag"));
    }

    @Test
    void oddTextIsReadWithoutFailing() {
        ColumnEvidence e = scan(Dialect.SQLSERVER, """
                DECLARE @t AS TABLE (k INT);
                INSERT INTO a (SELECT 1);
                INSERT INTO b (x y);
                INSERT INTO c (p, q
                """);
        assertTrue(e.definedByScript(ObjectName.parse(Dialect.SQLSERVER, "@t")));
        assertNull(fact(e, Dialect.SQLSERVER, "a", "SELECT"));
        assertNull(fact(e, Dialect.SQLSERVER, "b", "x"));
        assertNull(fact(e, Dialect.SQLSERVER, "c", "p"), "an unterminated list says nothing");
        ColumnEvidence pg = scan(Dialect.POSTGRESQL, """
                create table (x int);
                create table;
                update t as x set a = 1, b = (2)), e = 3 where x.c = 1;
                update set a = 1;
                update;
                insert into d as dd (u) values (1);
                select o.v from orders o, sales.orders where 1 = 1;
                select "".w from "" z;
                select $a$ unbalanced $b$, $$$$;
                select 'unterminated
                """);
        assertNotNull(fact(pg, Dialect.POSTGRESQL, "t", "a"));
        assertNotNull(fact(pg, Dialect.POSTGRESQL, "t", "c"));
        assertNull(fact(pg, Dialect.POSTGRESQL, "t", "e"), "the list ends at the stray parenthesis");
        assertNotNull(fact(pg, Dialect.POSTGRESQL, "d", "u"));
        assertNotNull(fact(pg, Dialect.POSTGRESQL, "sales.orders", "v"), "the qualified twin replaces orders");
    }

    @Test
    void choiceNeedsExactlyOneTable() {
        ColumnEvidence e = new ColumnEvidence();
        assertNull(e.choose(List.of(name("a"), name("b")), "x"), "empty evidence");
        e.add("A", "A", "a", "x", Kind.QUALIFIED_REFERENCE, 1);
        e.add("A", "A", "a", "X", Kind.UPDATE_SET, 9);
        e.add("B", "B", "b", "y", Kind.INSERT_COLUMN_LIST, 2);
        e.add("B", "B", "b", "\"\"", Kind.INSERT_COLUMN_LIST, 2);
        assertEquals(2, e.size(), "first fact per table and column; empty names are dropped");
        assertEquals(1, e.find(name("a"), "x").line());
        ColumnEvidence.Choice c = e.choose(List.of(name("a"), name("b")), "x");
        assertEquals(0, c.index());
        assertEquals(1, e.choose(List.of(name("a"), name("b")), "Y").index());
        assertNull(e.choose(List.of(), "x"));
        assertNull(e.choose(List.of(name("a"), name("b")), "z"), "nobody has it");
        assertNull(e.choose(Arrays.asList(name("a"), null), "x"), "a source evidence cannot judge");
        e.add("B", "B", "b", "x", Kind.QUALIFIED_REFERENCE, 3);
        assertNull(e.choose(List.of(name("a"), name("b")), "x"), "both have it");
        e.define("C", "C");
        assertNull(e.choose(List.of(name("a"), name("c")), "y"), "a table created by DDL decides by its DDL");
        assertSame(null, ColumnEvidence.EMPTY.find(name("a"), "x"));
        assertFalse(ColumnEvidence.EMPTY.definedByScript(name("a")));
    }

    private static ObjectName name(String s) {
        return ObjectName.parse(Dialect.ORACLE, s);
    }

    // ----- lineage and AMB-I002 ---------------------------------------------------------------------

    @Test
    void oracleJoinWithoutAliasesPlacedByInsertColumnLists() {
        String sql = """
                insert into orders (order_id, cust_id, status) values (1, 10, 'NEW');
                insert into customers (id, name) values (10, 'A');
                select order_id, name from orders join customers on cust_id = id where status = 'PAID';
                """;
        Script s = build(Dialect.ORACLE, sql);
        Set<String> src = sources(s, 2);
        assertTrue(src.containsAll(Set.of("TABLE:orders.order_id", "TABLE:customers.name", "TABLE:orders.cust_id",
                "TABLE:customers.id", "TABLE:orders.status")), src::toString);
        assertTrue(src.stream().noneMatch(x -> x.startsWith("UNKNOWN")), src::toString);
        assertTrue(s.statements().get(2).lineage().edges().stream()
                .noneMatch(e -> e.confidence() == com.github.amber.core.ir.Confidence.CERTAIN), "never certain");
        assertEquals(List.of(), i002(s));
    }

    @Test
    void sqlServerCommaJoinPlacedByQualifiedUsageElsewhere() {
        String sql = """
                SELECT o.order_id, o.cust_id FROM orders o WHERE o.status = 'NEW';
                SELECT c.id, c.name FROM customers c;
                SELECT order_id, name FROM orders, customers WHERE cust_id = id AND status = 'PAID' AND region = 'EU';
                """;
        Script s = build(Dialect.SQLSERVER, sql);
        Set<String> src = sources(s, 2);
        assertTrue(src.containsAll(Set.of("TABLE:orders.cust_id", "TABLE:customers.id", "TABLE:orders.status",
                "TABLE:customers.name")), src::toString);
        assertTrue(src.contains("UNKNOWN:?.region"), "no evidence for region: " + src);
        assertEquals(List.of("3:Could not tell which table region belongs to."), i002(s));
    }

    @Test
    void evidenceLaterInTheScriptCountsToo() {
        Script s = build(Dialect.POSTGRESQL, """
                select status from orders, customers where cust_id = id;
                insert into orders (cust_id, status) values (1, 'x') on conflict (cust_id) do nothing;
                update customers set id = id where name = 'n';
                """);
        Set<String> src = sources(s, 0);
        assertTrue(src.containsAll(Set.of("TABLE:orders.status", "TABLE:orders.cust_id", "TABLE:customers.id")),
                src::toString);
        assertEquals(List.of(), i002(s));
    }

    @Test
    void ambiguousEvidenceStaysUnresolved() {
        Script s = build(Dialect.MYSQL, """
                insert into customers (id, name) values (1, 'a');
                insert into orders set id = 1, cust_id = 1;
                select id from orders, customers where cust_id = 1;
                """);
        Set<String> src = sources(s, 2);
        assertTrue(src.contains("UNKNOWN:?.id"), src::toString);
        assertTrue(src.contains("TABLE:orders.cust_id"), src::toString);
        assertEquals(List.of("3:Could not tell which table id belongs to."), i002(s));
    }

    @Test
    void metadataOverridesEvidence() {
        String sql = """
                insert into orders (order_id, cust_id) values (1, 10);
                select name from orders, customers where cust_id = 10;
                """;
        // a pack says customers has cust_id: metadata decides, the INSERT list of orders does not matter
        TableMeta customers = table("customers", "id", "name", "cust_id");
        Script s = build(Dialect.ORACLE, sql, customers);
        Set<String> src = sources(s, 1);
        assertTrue(src.contains("TABLE:customers.cust_id"), src::toString);
        assertFalse(src.contains("TABLE:orders.cust_id"), src::toString);
        // a pack that knows orders without cust_id: the evidence for orders is ignored, nothing else has it
        TableMeta orders = table("orders", "order_id", "amount");
        Script s2 = build(Dialect.ORACLE, sql, orders);
        assertTrue(sources(s2, 1).contains("UNKNOWN:?.cust_id"), () -> sources(s2, 1).toString());
        // DDL in the script, read before the query: same
        Script s3 = build(Dialect.ORACLE, """
                create table customers (id number, name varchar2(10), cust_id number);
                insert into orders (order_id, cust_id) values (1, 10);
                select name from orders, customers where cust_id = 10;
                """);
        assertTrue(sources(s3, 2).contains("TABLE:customers.cust_id"), () -> sources(s3, 2).toString());
    }

    @Test
    void tableCreatedLaterByDdlBlocksEvidenceUntilRead() {
        Script s = build(Dialect.POSTGRESQL, """
                select status from orders, stage where k = 1;
                insert into orders (k, status) values (1, 'x');
                create table stage (k int, v int);
                """);
        assertTrue(sources(s, 0).contains("UNKNOWN:?.k"), () -> sources(s, 0).toString());
    }

    @Test
    void cteWithUnknownColumnsBlocksEvidence() {
        Script s = build(Dialect.POSTGRESQL, """
                insert into orders (cust_id) values (1);
                with x as (select * from somewhere) select cust_id from orders, x;
                """);
        assertTrue(sources(s, 1).contains("UNKNOWN:?.cust_id"), () -> sources(s, 1).toString());
        Script s2 = build(Dialect.POSTGRESQL, """
                insert into orders (cust_id) values (1);
                with x as (select a from somewhere) select cust_id from orders, x;
                """);
        assertTrue(sources(s2, 1).contains("TABLE:orders.cust_id"), () -> sources(s2, 1).toString());
    }

    @Test
    void singleTableScopeIsUnchanged() {
        Script s = build(Dialect.ORACLE, """
                insert into customers (cust_id) values (1);
                select cust_id from orders;
                """);
        assertTrue(sources(s, 1).contains("TABLE:orders.cust_id"), () -> sources(s, 1).toString());
    }

    @Test
    void evidenceIsSetOnTheCatalog() {
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        Catalog c = new Catalog();
        assertNull(c.evidence());
        ScriptIrBuilder.build(f, f.parse(SourceText.of("select o.a from t o;")), c);
        assertNotNull(c.evidence());
        assertNotNull(c.copy().evidence(), "a copy keeps it");
        assertEquals(1, c.evidence().size());
    }
}
