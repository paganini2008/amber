package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.er.ErModel.Cardinality;
import com.github.amber.er.ErModel.Certainty;
import com.github.amber.er.ErModel.ColumnSource;
import com.github.amber.er.ErModel.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.er.ErTestSupport.between;
import static com.github.amber.er.ErTestSupport.column;
import static com.github.amber.er.ErTestSupport.entity;
import static com.github.amber.er.ErTestSupport.model;
import static com.github.amber.er.ErTestSupport.one;
import static com.github.amber.er.ErTestSupport.table;
import static com.github.amber.er.ErTestSupport.withFk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inferred ER model on Oracle scripts (the priority dialect) and the dialect-neutral rules: declared
 * keys, joins with and without metadata, composite keys, self joins, comma joins, correlated subqueries,
 * CTEs, name matches, merging, data flow and the Mermaid text.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ErModelTest {

    private static final Dialect ORA = Dialect.ORACLE;

    @Test
    void declaredKeysInlineTableLevelAndAlter() {
        ErModel m = model(ORA, """
                create table dept (dept_id number primary key, dname varchar2(30));
                create table emp (
                  emp_id number primary key,
                  dept_id number references dept (dept_id),
                  mgr_id number,
                  site_code varchar2(5),
                  site_region varchar2(5),
                  constraint fk_emp_site foreign key (site_code, site_region) references hr.site (code, region)
                );
                alter table emp add constraint fk_emp_mgr foreign key (mgr_id) references emp (emp_id);
                """);
        ErModel.Relationship dept = one(m, "EMP", "DEPT");
        assertEquals("EMP", dept.from().entity());
        assertEquals(List.of("dept_id"), dept.from().columns());
        assertEquals(List.of("dept_id"), dept.to().columns());
        assertEquals(Evidence.DECLARED_FK, dept.evidence());
        assertEquals(Certainty.CERTAIN, dept.confidence());
        assertEquals(Cardinality.MANY_TO_ONE, dept.cardinality());
        assertEquals(0, dept.count());

        ErModel.Relationship site = one(m, "EMP", "HR.SITE");
        assertEquals(List.of("site_code", "site_region"), site.from().columns());
        assertEquals(List.of("code", "region"), site.to().columns());
        assertEquals("fk_emp_site", site.constraint());

        ErModel.Relationship mgr = one(m, "EMP", "EMP");
        assertEquals(List.of("mgr_id"), mgr.from().columns());
        assertEquals(List.of("emp_id"), mgr.to().columns());
        assertEquals("fk_emp_mgr", mgr.constraint());

        ErModel.Entity emp = entity(m, "EMP");
        assertTrue(emp.createdInScript());
        assertNotNull(emp.definitionRange());
        assertEquals("script DDL", emp.metadataSource());
        assertTrue(column(m, "EMP", "emp_id").pk());
        assertTrue(column(m, "EMP", "emp_id").unique());
        assertTrue(column(m, "EMP", "dept_id").fk());
        assertEquals(ColumnSource.DDL, column(m, "EMP", "dept_id").source());
        assertEquals("NUMBER", column(m, "EMP", "dept_id").type().toUpperCase());
        assertFalse(entity(m, "HR.SITE").createdInScript());
        assertTrue(m.mermaid().contains("emp }o--|| dept : \"dept_id\""), m.mermaid());
    }

    @Test
    void foreignKeyAndJoinMergeIntoOneRelationship() {
        ErModel m = model(ORA, """
                create table customers (id number primary key, name varchar2(50));
                create table orders (id number primary key, customer_id number references customers);
                select o.id, c.name from orders o join customers c on c.id = o.customer_id;
                select * from orders o, customers c where o.customer_id = c.id and c.name like 'A%';
                """);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals("ORDERS", r.from().entity());
        assertEquals(List.of("id"), r.to().columns(), "REFERENCES without columns means the primary key");
        assertEquals(Evidence.DECLARED_FK, r.evidence());
        assertEquals(Certainty.CERTAIN, r.confidence());
        assertEquals(2, r.count());
        assertEquals(List.of(2, 3), r.occurrences().stream().map(ErModel.Occurrence::statement).toList());
        assertTrue(r.note().contains("2 joins"), r.note());
        assertTrue(column(m, "ORDERS", "customer_id").joined());
    }

    @Test
    void joinWithMetadataPointsAtTheKey() {
        TableMeta customers = table("customers", List.of("id"), "id", "name");
        TableMeta orders = table("orders", List.of("order_id"), "order_id", "cust", "amount");
        ErModel m = model(ORA, """
                select o.amount, c.name from orders o inner join customers c on o.cust = c.id;
                select o.amount from customers c join orders o on c.id = o.cust;
                """, customers, orders);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals("ORDERS", r.from().entity());
        assertEquals(List.of("cust"), r.from().columns());
        assertEquals(Cardinality.MANY_TO_ONE, r.cardinality());
        assertEquals(Evidence.JOIN, r.evidence());
        assertEquals(Certainty.INFERRED, r.confidence());
        assertEquals(2, r.count());
        assertTrue(r.note().contains("primary key"), r.note());
        assertEquals(ColumnSource.METADATA, column(m, "ORDERS", "cust").source());
        assertTrue(column(m, "ORDERS", "cust").fk());
        assertEquals("test-pack", entity(m, "ORDERS").metadataSource());
    }

    @Test
    void joinWithoutMetadataUsesNamesOrStaysUnknown() {
        ErModel m = model(ORA, """
                select * from order_items oi join orders o on o.id = oi.order_id;
                select * from invoices i join customer c on i.customer_no = c.customer_no;
                select * from shipments s join parcels p on s.label = p.label;
                select * from a_things a join b_things b on a.ref_code = b.ref_code;
                """);
        ErModel.Relationship items = one(m, "ORDER_ITEMS", "ORDERS");
        assertEquals("ORDER_ITEMS", items.from().entity());
        assertEquals(Cardinality.MANY_TO_ONE, items.cardinality());
        assertEquals(Certainty.POSSIBLE, items.confidence());
        assertTrue(items.note().contains("guessed"), items.note());

        ErModel.Relationship inv = one(m, "INVOICES", "CUSTOMER");
        assertEquals("INVOICES", inv.from().entity());
        assertEquals(Cardinality.MANY_TO_ONE, inv.cardinality());

        ErModel.Relationship label = one(m, "SHIPMENTS", "PARCELS");
        assertEquals(Cardinality.UNKNOWN, label.cardinality());
        assertEquals(Certainty.POSSIBLE, label.confidence());

        assertEquals(Cardinality.UNKNOWN, one(m, "A_THINGS", "B_THINGS").cardinality());
        assertTrue(m.mermaid().contains("}o..o{"), m.mermaid());
    }

    @Test
    void manyToManyOnlyWhenMetadataProvesIt() {
        TableMeta a = table("a_side", List.of("id"), "id", "grp");
        TableMeta b = table("b_side", List.of("id"), "id", "grp");
        ErModel withMeta = model(ORA, "select * from a_side a join b_side b on a.grp = b.grp;", a, b);
        assertEquals(Cardinality.MANY_TO_MANY, one(withMeta, "A_SIDE", "B_SIDE").cardinality());
        assertEquals(Certainty.INFERRED, one(withMeta, "A_SIDE", "B_SIDE").confidence());
        ErModel withoutMeta = model(ORA, "select * from a_side a join b_side b on a.grp = b.grp;");
        assertEquals(Cardinality.UNKNOWN, one(withoutMeta, "A_SIDE", "B_SIDE").cardinality());
        // a table from DDL without any key does not prove anything
        ErModel ddl = model(ORA, """
                create table a_side (id number, grp number);
                create table b_side (id number, grp number);
                select * from a_side a join b_side b on a.grp = b.grp;
                """);
        assertEquals(Cardinality.UNKNOWN, one(ddl, "A_SIDE", "B_SIDE").cardinality());
    }

    @Test
    void oneToOneWhenBothSidesAreUnique() {
        TableMeta person = table("person", List.of("id"), "id", "name");
        TableMeta detail = table("person_detail", List.of("person_id"), "person_id", "bio");
        ErModel m = model(ORA, "select * from person p join person_detail d on d.person_id = p.id;", person, detail);
        ErModel.Relationship r = one(m, "PERSON", "PERSON_DETAIL");
        assertEquals(Cardinality.ONE_TO_ONE, r.cardinality());
        assertTrue(m.mermaid().contains("|o..||"), m.mermaid());
    }

    @Test
    void uniqueKeysAndUniqueIndexesCount() {
        TableMeta code = new TableMeta(null, null, "currency", "TABLE",
                List.of(new TableMeta.ColumnMeta("cur_id", "NUMBER", false),
                        new TableMeta.ColumnMeta("iso", "CHAR(3)", false)),
                List.of("cur_id"), List.of(List.of("iso")), List.of(), 10, "pack");
        ErModel m = model(ORA, "select * from payment p join currency c on p.iso = c.iso;", code);
        ErModel.Relationship r = one(m, "PAYMENT", "CURRENCY");
        assertEquals("PAYMENT", r.from().entity());
        assertEquals(Cardinality.MANY_TO_ONE, r.cardinality());
        assertTrue(r.note().contains("unique key"), r.note());
        assertTrue(column(m, "CURRENCY", "iso").unique());
    }

    @Test
    void compositeKeysAreGroupedPerCondition() {
        ErModel m = model(ORA, """
                create table order_lines (order_id number, line_no number, qty number,
                  constraint pk_ol primary key (order_id, line_no));
                select * from shipments s
                  join order_lines l on l.order_id = s.order_id and l.line_no = s.line_no and s.qty > 0;
                select * from shipments s, order_lines l where s.line_no = l.line_no and s.order_id = l.order_id;
                """);
        ErModel.Relationship r = one(m, "SHIPMENTS", "ORDER_LINES");
        assertEquals("SHIPMENTS", r.from().entity());
        assertEquals(2, r.from().columns().size());
        assertEquals(Cardinality.MANY_TO_ONE, r.cardinality());
        assertEquals(Certainty.INFERRED, r.confidence());
        assertEquals(2, r.count(), "both column orders are the same relationship");
    }

    @Test
    void selfJoinHierarchy() {
        ErModel m = model(ORA, """
                select e.name, m.name as manager
                  from employees e left join employees m on e.manager_id = m.id;
                """);
        ErModel.Relationship r = one(m, "EMPLOYEES", "EMPLOYEES");
        assertEquals(List.of("manager_id"), r.from().columns());
        assertEquals(List.of("id"), r.to().columns());
        assertEquals(Cardinality.MANY_TO_ONE, r.cardinality());
        assertTrue(m.mermaid().contains("employees }o..|| employees"), m.mermaid());
        // the same column on both sides of a self join is no relationship
        ErModel same = model(ORA, "select * from employees a join employees b on a.dept_id = b.dept_id;");
        assertTrue(between(same, "EMPLOYEES", "EMPLOYEES").isEmpty());
    }

    @Test
    void commaJoinsAndOracleOuterJoinMarks() {
        ErModel m = model(ORA, """
                select * from orders o, customers c, regions r
                 where o.customer_id = c.id (+)
                   and c.region_id = r.id
                   and o.status = 'OPEN';
                """);
        assertEquals(Evidence.JOIN, one(m, "ORDERS", "CUSTOMERS").evidence());
        ErModel.Relationship cr = one(m, "CUSTOMERS", "REGIONS");
        assertEquals("CUSTOMERS", cr.from().entity());
        assertTrue(between(m, "ORDERS", "REGIONS").isEmpty());
    }

    @Test
    void correlatedSubqueriesAndUpdateTargets() {
        ErModel m = model(ORA, """
                select o.id from orders o
                 where exists (select 1 from payments p where p.order_id = o.id)
                   and o.total > (select avg(x.total) from orders x where x.customer_id = o.customer_id);
                update orders o set o.paid = (select sum(p.amount) from payments p where p.order_id = o.id);
                delete from order_notes n where not exists (select 1 from orders o where o.id = n.order_id);
                """);
        ErModel.Relationship pay = one(m, "PAYMENTS", "ORDERS");
        assertEquals(2, pay.count());
        assertEquals("PAYMENTS", pay.from().entity());
        assertEquals(1, one(m, "ORDER_NOTES", "ORDERS").count());
        // x.customer_id = o.customer_id: the same column of the same table
        assertTrue(between(m, "ORDERS", "ORDERS").isEmpty());
    }

    @Test
    void cteColumnsCountOnlyWhenTheyPassThrough() {
        ErModel m = model(ORA, """
                with c as (select id, name from customers),
                     t as (select customer_id, sum(amount) total from orders group by customer_id),
                     k as (select id + 0 as id from regions)
                select * from c join t on t.customer_id = c.id join k on k.id = c.id;
                """);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals("ORDERS", r.from().entity());
        assertTrue(between(m, "REGIONS", "CUSTOMERS").isEmpty(), "k.id is computed");
        assertTrue(m.stats().unresolved() >= 1);
        assertTrue(m.entities().stream().noneMatch(e -> e.key().equals("C") || e.key().equals("T")));
    }

    @Test
    void derivedTablesPassThrough() {
        ErModel m = model(ORA, """
                select * from (select id, customer_id from orders where status = 'X') x
                  join customers c on c.id = x.customer_id;
                """);
        assertEquals("ORDERS", one(m, "ORDERS", "CUSTOMERS").from().entity());
    }

    @Test
    void conditionsThatAreNoJoins() {
        ErModel m = model(ORA, """
                select * from a_tab a join b_tab b on a.k = b.k or a.j = b.j;
                select * from a_tab a join c_tab c on upper(a.k) = c.k;
                select * from a_tab a join d_tab d on a.k + 1 = d.k;
                select * from a_tab a, e_tab e where a.k = :bind and e.k = 5;
                select * from a_tab a join f_tab f on a.k = f.k and (a.x = 1 or f.y = 2);
                select * from a_tab a join g_tab g on a.k between g.lo and g.hi;
                """);
        assertTrue(between(m, "A_TAB", "B_TAB").isEmpty());
        assertTrue(between(m, "A_TAB", "C_TAB").isEmpty());
        assertTrue(between(m, "A_TAB", "D_TAB").isEmpty());
        assertTrue(between(m, "A_TAB", "E_TAB").isEmpty());
        assertEquals(1, between(m, "A_TAB", "F_TAB").size());
        assertTrue(between(m, "A_TAB", "G_TAB").isEmpty());
        assertEquals(7, m.entities().size());
    }

    @Test
    void unqualifiedColumnsResolveThroughMetadata() {
        TableMeta orders = table("orders", List.of("id"), "id", "customer_id");
        TableMeta customers = table("customers", List.of("cid"), "cid", "name");
        ErModel m = model(ORA, "select name from orders, customers where customer_id = cid;", orders, customers);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals(Cardinality.MANY_TO_ONE, r.cardinality());
        assertEquals("CUSTOMERS", r.to().entity());
        // without metadata the tables of unqualified columns are unknown
        ErModel none = model(ORA, "select name from orders, customers where customer_id = cid;");
        assertTrue(between(none, "ORDERS", "CUSTOMERS").isEmpty());
        assertEquals(1, none.stats().unresolved());
    }

    @Test
    void temporaryTablesAreEntities() {
        ErModel m = model(ORA, """
                create global temporary table tmp_due (order_id number, due date) on commit preserve rows;
                insert into tmp_due select o.id, o.due from orders o where o.status = 'OPEN';
                select * from tmp_due t join orders o on o.id = t.order_id;
                """);
        ErModel.Entity tmp = entity(m, "TMP_DUE");
        assertEquals("TEMP_TABLE", tmp.kind());
        assertTrue(tmp.createdInScript());
        assertEquals("TMP_DUE", one(m, "TMP_DUE", "ORDERS").from().entity());
    }

    @Test
    void nameMatchesWithoutJoins() {
        ErModel m = model(ORA, """
                create table customers (id number primary key, name varchar2(40));
                create table orders (id number primary key, customer_id number, note varchar2(40));
                select * from orders;
                select * from customers;
                """);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals(Evidence.NAME_MATCH, r.evidence());
        assertEquals(Certainty.POSSIBLE, r.confidence());
        assertEquals(Cardinality.MANY_TO_ONE, r.cardinality());
        assertEquals(List.of("id"), r.to().columns());
        assertFalse(column(m, "ORDERS", "customer_id").fk(), "a name match alone does not mark a key");
        ErModel off = model(ORA, """
                create table customers (id number primary key, name varchar2(40));
                create table orders (id number primary key, customer_id number, note varchar2(40));
                """, new ErModelBuilder.Options(false, false, 100));
        assertTrue(off.relationships().isEmpty());
    }

    @Test
    void nameMatchGuards() {
        TableMeta customer = table("customer", List.of("cust_no"), "cust_no", "name");
        ErModel m = model(ORA, """
                create table customers (id number primary key);
                create table orders (id number primary key, customer_id number, product_id number, store_id number);
                create table stores (store_key number, name varchar2(10));
                create table products (code varchar2(10));
                select * from orders;
                select * from customers;
                select * from customer;
                select * from stores;
                select * from products;
                """, customer);
        assertTrue(between(m, "ORDERS", "CUSTOMERS").isEmpty(), "customer and customers: ambiguous");
        assertTrue(between(m, "ORDERS", "PRODUCTS").isEmpty(), "products has no key column");
        assertTrue(between(m, "ORDERS", "STORES").isEmpty(), "stores has no fitting column");
    }

    @Test
    void dataFlowOnlyWhenAsked() {
        String sql = """
                insert into sales_hist (order_id, amount)
                  with src as (select id, amount from orders)
                  select id, amount from src;
                create table big_orders as select o.id, o.amount from orders o where o.amount > 100;
                """;
        ErModel off = model(ORA, sql);
        assertTrue(off.relationships().stream().noneMatch(r -> r.evidence() == Evidence.DATA_FLOW));
        ErModel on = model(ORA, sql, new ErModelBuilder.Options(true, true, 100));
        ErModel.Relationship hist = between(on, "ORDERS", "SALES_HIST").stream()
                .filter(r -> r.evidence() == Evidence.DATA_FLOW).findFirst().orElseThrow();
        assertEquals(Evidence.NAME_MATCH, between(on, "ORDERS", "SALES_HIST").stream()
                .filter(r -> r.evidence() != Evidence.DATA_FLOW).findFirst().orElseThrow().evidence());
        assertEquals("ORDERS", hist.from().entity());
        assertEquals(List.of("id", "amount"), hist.from().columns());
        assertEquals(List.of("order_id", "amount"), hist.to().columns());
        assertEquals(Evidence.DATA_FLOW, between(on, "ORDERS", "BIG_ORDERS").stream()
                .filter(r -> r.evidence() == Evidence.DATA_FLOW).findFirst().orElseThrow().evidence());
        assertTrue(entity(on, "BIG_ORDERS").createdInScript());
        assertTrue(on.mermaid().contains("(data flow)"), on.mermaid());
    }

    @Test
    void occurrencesAreCappedButCounted() {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            sql.append("select * from orders o join customers c on c.id = o.customer_id;\n");
        }
        ErModel m = model(ORA, sql.toString(), new ErModelBuilder.Options(false, true, 3));
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals(7, r.count());
        assertEquals(3, r.occurrences().size());
        ErModel.Occurrence o = r.occurrences().get(0);
        assertEquals("c.id = o.customer_id", sql.substring(o.range().start().offset(), o.range().end().offset()));
        assertEquals(1, o.range().start().line());
    }

    @Test
    void qualifiedAndUnqualifiedNamesAreOneEntity() {
        ErModel m = model(ORA, """
                select * from sales.orders o join sales.customers c on c.id = o.customer_id;
                select * from orders;
                """);
        assertEquals(List.of("SALES.ORDERS", "SALES.CUSTOMERS"), m.entities().stream().map(ErModel.Entity::key).toList());
        assertEquals("sales.orders", entity(m, "SALES.ORDERS").name());
        assertTrue(m.mermaid().contains("sales_orders {"), m.mermaid());
    }

    @Test
    void metadataForeignKeysOnlyBetweenEntitiesOfTheScript() {
        TableMeta customers = table("customers", List.of("id"), "id", "name");
        TableMeta regions = table("regions", List.of("id"), "id");
        TableMeta orders = withFk(withFk(table("orders", List.of("id"), "id", "customer_id", "region_id"),
                "fk_o_c", List.of("customer_id"), "customers", List.of("id")),
                "fk_o_r", List.of("region_id"), "regions", List.of());
        ErModel m = model(ORA, "select * from orders o join customers c on c.id = o.customer_id;",
                customers, regions, orders);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals(Evidence.METADATA_FK, r.evidence());
        assertEquals(Certainty.CERTAIN, r.confidence());
        assertEquals("fk_o_c", r.constraint());
        assertEquals(1, r.count());
        assertTrue(r.note().contains("metadata"), r.note());
        assertTrue(m.entities().stream().noneMatch(e -> e.key().equals("REGIONS")));
        // referenced table in the script, referenced columns unknown: the primary key
        ErModel both = model(ORA, "select * from orders o, regions r where 1 = 1;", customers, regions, orders);
        ErModel.Relationship reg = one(both, "ORDERS", "REGIONS");
        assertEquals(List.of("id"), reg.to().columns());
    }

    @Test
    void mermaidEscapesAndLimitsAttributes() {
        StringBuilder cols = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            cols.append(", c").append(i).append(" number");
        }
        ErModel m = model(ORA, "create table \"Wide Table\" (id number primary key" + cols + ");\n"
                + "create table \"Order\"\"s\" (\"Wide Id\" number references \"Wide Table\"(id));\n");
        String mm = m.mermaid();
        assertTrue(mm.startsWith("erDiagram\n"), mm);
        assertTrue(mm.contains("Wide_Table {"), mm);
        assertTrue(mm.contains("more more_columns \"… 9 more\""), mm);
        assertTrue(mm.contains("number Wide_Id FK \"Wide Id\""), mm);
        assertTrue(mm.contains("Orders }o--|| Wide_Table : \"Wide Id = id\""), mm);
        assertFalse(mm.contains("\"\""), mm);
    }

    @Test
    void relationshipsAreOrderedAndNumbered() {
        ErModel m = model(ORA, """
                create table a_parent (id number primary key);
                create table a_child (id number primary key, a_parent_id number references a_parent);
                select * from x_one x join y_two y on x.k = y.k;
                select * from x_one x join y_two y on x.k = y.k;
                select * from p_one p join q_two q on p.k = q.k;
                """);
        List<ErModel.Relationship> r = m.relationships();
        assertEquals("R1", r.get(0).id());
        assertEquals(Evidence.DECLARED_FK, r.get(0).evidence());
        assertEquals(2, r.get(1).count(), "most used join first");
        assertEquals("R3", r.get(2).id());
        assertNull(r.get(1).constraint());
    }
}
