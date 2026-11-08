package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.er.ErModel.Cardinality;
import com.github.amber.er.ErModel.Evidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static com.github.amber.er.ErTestSupport.between;
import static com.github.amber.er.ErTestSupport.entity;
import static com.github.amber.er.ErTestSupport.model;
import static com.github.amber.er.ErTestSupport.one;
import static com.github.amber.er.ErTestSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ER model per dialect: SQL Server and PostgreSQL in depth (with Oracle in {@link ErModelTest}), and
 * a declared-key / join smoke test for MySQL, MariaDB, Db2, H2 and SQLite.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ErDialectsTest {

    // ----- SQL Server -----------------------------------------------------------------------------

    @Test
    void sqlServerDeclaredKeys() {
        ErModel m = model(Dialect.SQLSERVER, """
                create table dbo.customers (id int not null primary key, name nvarchar(50));
                create table dbo.orders (
                  id int not null primary key,
                  customer_id int foreign key references dbo.customers(id),
                  shop_id int,
                  constraint fk_orders_shop foreign key (shop_id) references dbo.shops (shop_id)
                );
                alter table dbo.orders with check add constraint fk_orders_agent foreign key (agent_id) references [dbo].[agents] ([id]);
                """);
        assertEquals(Evidence.DECLARED_FK, one(m, "DBO.ORDERS", "DBO.CUSTOMERS").evidence());
        assertEquals("fk_orders_shop", one(m, "DBO.ORDERS", "DBO.SHOPS").constraint());
        ErModel.Relationship agent = one(m, "DBO.ORDERS", "DBO.AGENTS");
        assertEquals(List.of("id"), agent.to().columns());
        assertEquals("fk_orders_agent", agent.constraint());
    }

    @Test
    void sqlServerUpdateFromAliasesAndTempTables() {
        TableMeta orders = table("orders", List.of("id"), "id", "customer_id", "total");
        ErModel m = model(Dialect.SQLSERVER, """
                select o.id, o.customer_id into #recent from dbo.orders o where o.total > 10;
                update o set o.total = 0
                  from dbo.orders o
                  inner join dbo.customers c on c.id = o.customer_id
                  where c.blocked = 1;
                select r.id from #recent r join [dbo].[orders] x with (nolock) on x.id = r.id;
                delete d from dbo.order_notes d join #recent r on r.id = d.order_id;
                """, orders);
        assertTrue(m.entities().stream().noneMatch(e -> e.key().equals("O") || e.key().equals("D")),
                m.entities().stream().map(ErModel.Entity::key).toList().toString());
        ErModel.Relationship oc = between(m, "DBO.ORDERS", "DBO.CUSTOMERS").stream()
                .filter(r -> r.evidence() == Evidence.JOIN).findFirst().orElseThrow();
        assertEquals("DBO.ORDERS", oc.from().entity());
        assertEquals("TEMP_TABLE", entity(m, "#RECENT").kind());
        assertTrue(entity(m, "#RECENT").createdInScript());
        ErModel.Relationship self = one(m, "#RECENT", "DBO.ORDERS");
        assertEquals(Cardinality.MANY_TO_ONE, self.cardinality());
        assertEquals("#RECENT", self.from().entity());
        assertEquals("#RECENT", one(m, "DBO.ORDER_NOTES", "#RECENT").to().entity());
        assertEquals(Evidence.JOIN, one(m, "DBO.ORDER_NOTES", "#RECENT").evidence());
    }

    @Test
    void sqlServerApplyAndMerge() {
        ErModel m = model(Dialect.SQLSERVER, """
                merge dbo.stock as t
                using dbo.stock_delta as s on t.item_id = s.item_id and t.site_id = s.site_id
                when matched then update set t.qty = s.qty
                when not matched then insert (item_id, site_id, qty) values (s.item_id, s.site_id, s.qty);
                select * from dbo.items i cross apply (select top 1 * from dbo.prices p where p.item_id = i.id) x;
                """);
        ErModel.Relationship stock = one(m, "DBO.STOCK", "DBO.STOCK_DELTA");
        assertEquals(2, stock.from().columns().size());
        assertEquals("DBO.PRICES", one(m, "DBO.PRICES", "DBO.ITEMS").from().entity());
    }

    // ----- PostgreSQL -----------------------------------------------------------------------------

    @Test
    void postgresDeclaredKeys() {
        ErModel m = model(Dialect.POSTGRESQL, """
                create table app.account (id bigint primary key, email text unique);
                create table app.session (id bigint primary key, account_id bigint not null references app.account on delete cascade);
                create table app.audit (id bigint, account_email text, foreign key (account_email) references app.account (email));
                alter table only app.audit add constraint fk_audit_session foreign key (id) references app.session (id);
                """);
        ErModel.Relationship s = one(m, "APP.SESSION", "APP.ACCOUNT");
        assertEquals(List.of("id"), s.to().columns());
        assertEquals(Evidence.DECLARED_FK, s.evidence());
        assertEquals(List.of("email"), one(m, "APP.AUDIT", "APP.ACCOUNT").to().columns());
        assertEquals("fk_audit_session", one(m, "APP.AUDIT", "APP.SESSION").constraint());
    }

    @Test
    void postgresUsingJoinsAndDeleteUsing() {
        ErModel m = model(Dialect.POSTGRESQL, """
                create table orders (order_id int primary key, customer_id int);
                create table customers (customer_id int primary key, name text);
                select * from orders join customers using (customer_id);
                select * from orders o join order_lines l using (order_id) join products p using (product_id);
                delete from orders o using customers c where c.customer_id = o.customer_id and c.name is null;
                update orders o set customer_id = null from customers c where o.customer_id = c.customer_id;
                """);
        ErModel.Relationship oc = one(m, "ORDERS", "CUSTOMERS");
        assertEquals("ORDERS", oc.from().entity());
        assertEquals(Cardinality.MANY_TO_ONE, oc.cardinality());
        assertEquals(3, oc.count());
        assertEquals("ORDER_LINES", one(m, "ORDERS", "ORDER_LINES").from().entity());
        ErModel.Relationship lp = one(m, "ORDER_LINES", "PRODUCTS");
        assertEquals(List.of("product_id"), lp.from().columns());
        assertEquals(Cardinality.MANY_TO_ONE, lp.cardinality(), "products.product_id looks like its own key");
    }

    @Test
    void postgresLateralAndCte() {
        ErModel m = model(Dialect.POSTGRESQL, """
                with recent as (select id, user_id from events where ts > now() - interval '1 day')
                select u.name, r.id from users u join recent r on r.user_id = u.id;
                select u.id, x.n from users u, lateral (select count(*) n from events e where e.user_id = u.id) x;
                """);
        ErModel.Relationship r = one(m, "EVENTS", "USERS");
        assertEquals(2, r.count());
        assertEquals("EVENTS", r.from().entity());
    }

    // ----- other dialects (smoke) -----------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"MYSQL", "MARIADB", "DB2", "H2", "SQLITE"})
    void smoke(Dialect d) {
        String ddl = switch (d) {
            case SQLITE -> """
                    create table parent (id integer primary key, name text);
                    create table child (id integer primary key, parent_id integer references parent(id), kind text,
                      foreign key (kind) references kinds(code));
                    """;
            case MYSQL, MARIADB -> """
                    create table parent (id int primary key, name varchar(20));
                    create table child (id int primary key, parent_id int, kind varchar(5),
                      constraint fk_child_parent foreign key (parent_id) references parent (id));
                    alter table child add constraint fk_child_kind foreign key (kind) references kinds (code);
                    """;
            default -> """
                    create table parent (id int not null primary key, name varchar(20));
                    create table child (id int not null primary key, parent_id int references parent (id), kind varchar(5));
                    alter table child add constraint fk_child_kind foreign key (kind) references kinds (code);
                    """;
        };
        ErModel m = model(d, ddl + """
                select c.id, p.name from child c join parent p on p.id = c.parent_id;
                select * from child c, kinds k where k.code = c.kind;
                select * from child c join toys t on t.child_id = c.id;
                """);
        ErModel.Relationship cp = one(m, "CHILD", "PARENT");
        assertTrue(cp.evidence() == Evidence.DECLARED_FK, d + ": " + cp);
        assertEquals(1, cp.count(), d + ": " + cp);
        ErModel.Relationship ck = one(m, "CHILD", "KINDS");
        assertEquals(Evidence.DECLARED_FK, ck.evidence(), d + ": " + ck);
        assertEquals(List.of("code"), ck.to().columns());
        ErModel.Relationship toys = one(m, "TOYS", "CHILD");
        assertEquals("TOYS", toys.from().entity(), d + ": " + toys);
        assertEquals(Cardinality.MANY_TO_ONE, toys.cardinality());
        assertTrue(between(m, "PARENT", "KINDS").isEmpty());
    }
}
