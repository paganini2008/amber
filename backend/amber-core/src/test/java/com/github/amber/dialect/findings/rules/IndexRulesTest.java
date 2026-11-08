package com.github.amber.dialect.findings.rules;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Finding.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.dialect.findings.rules.RulesTestSupport.line;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.none;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.one;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.rule;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AMB-P001 (possible missing index) and AMB-P006 (OR across indexed columns).
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class IndexRulesTest {

    private static final TableMeta ORDERS = table("sales", "orders", List.of("id"), List.of("created_at"),
            "id number!", "customer_id number", "status varchar2(10)", "created_at date", "region varchar2(5)");
    private static final TableMeta CUSTOMERS = table("sales", "customers", List.of("id"), List.of("u:code"),
            "id number!", "code varchar2(20)", "name varchar2(80)", "segment varchar2(10)");

    @Test
    void unindexedJoinAndFilterColumnsAreAggregatedPerColumn() {
        String sql = """
                select o.id from sales.orders o join sales.customers c on c.segment = o.region where o.status = 'NEW';
                select count(*) from sales.orders where status = 'OPEN';
                update sales.orders set region = 'EU' where status = 'X';
                select * from sales.orders o where o.id = 5 and o.created_at > sysdate - 1;
                """;
        List<Finding> p001 = rule(Dialect.ORACLE, sql, "AMB-P001", ORDERS, CUSTOMERS);
        assertEquals(3, p001.size(), p001::toString);
        Finding status = p001.stream().filter(f -> f.message().contains(".status")).findFirst().orElseThrow();
        assertTrue(status.message().contains("3 times in 3 statements"), status::message);
        assertEquals(Severity.LOW, status.severity(), "3 statements raise it to LOW");
        assertEquals(Confidence.CERTAIN, status.confidence());
        assertEquals(1, line(status));
        assertTrue(status.suggestion().contains("check real data volumes and existing indexes"));
        assertTrue(status.suggestion().contains("CREATE INDEX ix_orders_status ON sales.orders (status)"));
        Finding region = p001.stream().filter(f -> f.message().contains(".region")).findFirst().orElseThrow();
        assertEquals(Severity.INFO, region.severity());
        assertTrue(region.message().contains("1 join condition"), region::message);
        assertTrue(p001.stream().anyMatch(f -> f.message().contains("customers.segment")));
    }

    @Test
    void indexedColumnsAndJoinsWithOneIndexedSideAreQuiet() {
        String sql = """
                select o.id from sales.orders o join sales.customers c on c.id = o.customer_id;
                select * from sales.customers where code = :code;
                select * from sales.orders where created_at between sysdate - 7 and sysdate;
                select * from sales.orders where status <> 'X';
                select * from sales.orders where status like '%X';
                select * from sales.orders where status = 'A' or region = 'EU';
                """;
        none(Dialect.ORACLE, sql, "AMB-P001", ORDERS, CUSTOMERS);
    }

    @Test
    void neverWithoutMetadataOrIndexInformation() {
        String sql = "select * from sales.orders where status = 'NEW';";
        none(Dialect.ORACLE, sql, "AMB-P001");
        TableMeta bare = table("sales", "orders", List.of(), List.of(), "id number", "status varchar2(10)");
        none(Dialect.ORACLE, sql, "AMB-P001", bare);
        TableMeta small = table("sales", "orders", List.of("id"), List.of(), 200, "id number", "status varchar2(10)");
        none(Dialect.ORACLE, sql, "AMB-P001", small);
    }

    @Test
    void secondColumnOfACompositeIndexWithItsLeadingColumnUsedIsFine() {
        TableMeta t = table("dbo", "events", List.of("id"), List.of("tenant_id,kind"),
                "id int!", "tenant_id int", "kind varchar(10)", "payload varchar(100)");
        none(Dialect.SQLSERVER, "select * from dbo.events where tenant_id = @t and kind = 'A';", "AMB-P001", t);
        Finding f = one(Dialect.SQLSERVER, "select * from dbo.events where kind = 'A';", "AMB-P001", t);
        assertTrue(f.message().contains("dbo.events.kind"), f::message);
    }

    @Test
    void scriptIndexesTablesCreatedInTheScriptAndSuppression() {
        TableMeta t = table("public", "items", List.of("id"), List.of(), "id int!", "sku text", "qty int");
        none(Dialect.POSTGRESQL, """
                create index ix_items_sku on public.items (sku);
                select * from public.items where sku = 'A';
                """, "AMB-P001", t);
        none(Dialect.POSTGRESQL, """
                create table items (id int primary key, sku text, qty int);
                select * from items where sku = 'A';
                """, "AMB-P001", t);
        none(Dialect.POSTGRESQL, """
                -- amber:ignore AMB-P001
                select * from public.items where sku = 'A';
                """, "AMB-P001", t);
        Finding f = one(Dialect.POSTGRESQL, """
                -- amber:ignore AMB-P001
                select * from public.items where sku = 'A';
                select * from public.items where sku = 'B';
                """, "AMB-P001", t);
        assertEquals(3, line(f), "the ignored use is not counted");
        assertTrue(f.message().contains("1 time in 1 statement"), f::message);
    }

    @Test
    void variablesAndOuterColumnsCountAsValues() {
        TableMeta t = table(null, "acct", List.of("id"), List.of(), "id number!", "owner_no varchar2(10)",
                "kind varchar2(5)");
        String plsql = """
                create or replace procedure p(v_owner varchar2) is
                  n number;
                begin
                  select count(*) into n from acct where owner_no = v_owner;
                end;
                /
                """;
        one(Dialect.ORACLE, plsql, "AMB-P001", t);
        one(Dialect.ORACLE, "select a.id, (select count(*) from acct b where b.kind = a.kind) from acct a;",
                "AMB-P001", t);
    }

    @Test
    void orAcrossDifferentIndexedColumns() {
        TableMeta t = table("dbo", "people", List.of("id"), List.of("email", "phone"),
                "id int!", "email varchar(80)", "phone varchar(20)", "name varchar(50)");
        Finding f = one(Dialect.SQLSERVER, "select * from dbo.people where email = @e or phone = @p;", "AMB-P006", t);
        assertEquals(Severity.INFO, f.severity());
        assertEquals(Confidence.CERTAIN, f.confidence());
        one(Dialect.POSTGRESQL, "select * from dbo.people p where p.status_flag is null and (p.email = 'a' or p.phone = 'b');"
                .replace("p.status_flag is null and ", "1 = 1 and "), "AMB-P006", t);
        one(Dialect.ORACLE, "select * from dbo.people where (email = 'a' or phone = 'b');", "AMB-P006", t);
        // same column, an unindexed column, another indexed AND term, no metadata: quiet
        none(Dialect.SQLSERVER, "select * from dbo.people where email = 'a' or email = 'b';", "AMB-P006", t);
        none(Dialect.SQLSERVER, "select * from dbo.people where email = 'a' or name = 'b';", "AMB-P006", t);
        none(Dialect.SQLSERVER, "select * from dbo.people where id = 3 and (email = 'a' or phone = 'b');",
                "AMB-P006", t);
        none(Dialect.SQLSERVER, "select * from dbo.people where email = 'a' or phone = 'b';", "AMB-P006");
        none(Dialect.SQLSERVER, "select * from dbo.people where email = 'a' and name = 'x' or phone = 'b';",
                "AMB-P006", t);
    }
}
