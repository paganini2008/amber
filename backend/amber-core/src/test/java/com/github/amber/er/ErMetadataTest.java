package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.MetadataPack;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.er.ErModel.Evidence;
import com.github.amber.jdbc.DataSourceSpec;
import com.github.amber.jdbc.MetadataReader;
import com.github.amber.server.MetadataService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static com.github.amber.er.ErTestSupport.one;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Foreign keys from metadata: read from an embedded H2 database through {@code DatabaseMetaData.getImportedKeys}
 * (the fixture creates its own private in-memory tables), carried through metadata packs (round trip; packs
 * written before {@code foreignKeys} existed still load) and imported DDL files.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ErMetadataTest {

    private static final String H2_URL = "jdbc:h2:mem:amber_er_fk_test;DB_CLOSE_DELAY=-1";
    private static Connection keepAlive;

    @BeforeAll
    static void fixture() throws Exception {
        keepAlive = DriverManager.getConnection(H2_URL);
        try (Statement s = keepAlive.createStatement()) {          // test fixture only, not Amber code
            s.execute("create schema shop");
            s.execute("create table shop.customer (id bigint primary key, name varchar(50))");
            s.execute("create table shop.product (sku varchar(10), variant int, primary key (sku, variant))");
            s.execute("create table shop.purchase (id bigint primary key, customer_id bigint, sku varchar(10),"
                    + " variant int, constraint fk_purchase_customer foreign key (customer_id) references shop.customer (id),"
                    + " constraint fk_purchase_product foreign key (sku, variant) references shop.product (sku, variant))");
            s.execute("create view shop.v_purchase as select id, customer_id from shop.purchase");
        }
    }

    @AfterAll
    static void close() throws Exception {
        keepAlive.close();
    }

    private static DataSourceSpec h2() {
        return new DataSourceSpec("h2-er", "h2", H2_URL, null, null, null, null, null, null);
    }

    @Test
    void readsImportedKeysThroughDatabaseMetaData() throws Exception {
        List<TableMeta> tables;
        try (MetadataReader r = MetadataReader.open(h2())) {
            tables = r.read(List.of(ObjectName.parse(Dialect.H2, "shop.purchase"),
                    ObjectName.parse(Dialect.H2, "shop.customer"), ObjectName.parse(Dialect.H2, "shop.product"),
                    ObjectName.parse(Dialect.H2, "shop.v_purchase")));
        }
        TableMeta purchase = tables.get(0);
        assertEquals(2, purchase.foreignKeys().size(), purchase.foreignKeys().toString());
        TableMeta.ForeignKeyMeta product = purchase.foreignKeys().stream()
                .filter(f -> f.refTable().equals("PRODUCT")).findFirst().orElseThrow();
        assertEquals("FK_PURCHASE_PRODUCT", product.name());
        assertEquals(List.of("SKU", "VARIANT"), product.columns());
        assertEquals(List.of("SKU", "VARIANT"), product.refColumns());
        assertEquals("SHOP", product.refSchema());
        assertTrue(tables.get(1).foreignKeys().isEmpty());
        assertTrue(tables.get(3).foreignKeys().isEmpty(), "views have no keys");

        ErModel m = ErTestSupport.model(Dialect.H2, """
                select p.id, c.name from shop.purchase p join shop.customer c on c.id = p.customer_id;
                select * from shop.purchase p join shop.product x on x.sku = p.sku and x.variant = p.variant;
                """, tables.toArray(new TableMeta[0]));
        ErModel.Relationship pc = one(m, "SHOP.PURCHASE", "SHOP.CUSTOMER");
        assertEquals(Evidence.METADATA_FK, pc.evidence());
        assertEquals(1, pc.count());
        ErModel.Relationship pp = one(m, "SHOP.PURCHASE", "SHOP.PRODUCT");
        assertEquals(Evidence.METADATA_FK, pp.evidence());
        assertEquals(1, pp.count(), "composite join merged with the composite key");
        assertEquals(ErModel.ColumnSource.METADATA, ErTestSupport.column(m, "SHOP.PURCHASE", "sku").source());
    }

    @Test
    void readsImportedKeysFromSqlite(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("er.db");
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {   // fixture only
            s.execute("create table author (id integer primary key, name text)");
            s.execute("create table book (id integer primary key, author_id integer references author(id),"
                    + " editor_id integer, foreign key (editor_id) references author(id))");
        }
        List<TableMeta> tables;
        try (MetadataReader r = MetadataReader.open(new DataSourceSpec("sqlite-er", "sqlite", url, null, null, null,
                null, null, null))) {
            tables = r.read(List.of(ObjectName.parse(Dialect.SQLITE, "book"), ObjectName.parse(Dialect.SQLITE, "author")));
        }
        TableMeta book = tables.get(0);
        assertEquals(2, book.foreignKeys().size(), book.foreignKeys().toString());
        assertTrue(book.foreignKeys().stream().allMatch(f -> f.refTable().equalsIgnoreCase("author")));
        ErModel m = ErTestSupport.model(Dialect.SQLITE, "select * from book b join author a on a.id = b.editor_id;",
                tables.toArray(new TableMeta[0]));
        List<ErModel.Relationship> rels = ErTestSupport.between(m, "BOOK", "AUTHOR");
        assertEquals(2, rels.size(), rels.toString());
        assertTrue(rels.stream().allMatch(r -> r.evidence() == Evidence.METADATA_FK));
        assertEquals(1, rels.stream().mapToInt(ErModel.Relationship::count).sum());
    }

    @Test
    void packsKeepForeignKeysAndOldPacksStillLoad(@TempDir Path dir) throws Exception {
        TableMeta orders = ErTestSupport.withFk(ErTestSupport.table("orders", List.of("id"), "id", "customer_id"),
                "fk_o_c", List.of("customer_id"), "customers", List.of("id"));
        Path file = dir.resolve("pack.json");
        MetadataPack.of("oracle", "test", List.of(orders, ErTestSupport.table("customers", List.of("id"), "id")))
                .write(file);
        assertTrue(Files.readString(file).contains("\"foreignKeys\""));
        MetadataPack back = MetadataPack.read(file);
        assertEquals(orders.foreignKeys(), back.tables().get(0).foreignKeys());
        assertEquals(orders, back.tables().get(0));

        Path old = dir.resolve("old.json");
        Files.writeString(old, """
                {"format":1,"dialect":"oracle","source":"old","created":"2026-11-01T00:00:00Z","tables":[
                 {"catalog":null,"schema":null,"name":"orders","kind":"TABLE",
                  "columns":[{"name":"id","type":"NUMBER","nullable":false}],
                  "primaryKey":["id"],"uniqueKeys":[],"indexes":[],"rowEstimate":-1,"source":"old"}]}
                """);
        TableMeta oldOrders = MetadataPack.read(old).tables().get(0);
        assertTrue(oldOrders.foreignKeys().isEmpty());
        assertEquals(List.of("id"), oldOrders.primaryKey());

        Catalog c = MetadataService.catalog("oracle", List.of(file.toString()), null, null);
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        ErModel m = ErModelBuilder.build(f, f.parse(SourceText.of("select * from orders o, customers c;")), c,
                ErModelBuilder.Options.DEFAULTS);
        assertEquals(Evidence.METADATA_FK, one(m, "ORDERS", "CUSTOMERS").evidence());
    }

    @Test
    void importedDdlFilesDeclareKeys(@TempDir Path dir) throws Exception {
        Path ddl = dir.resolve("schema.sql");
        Files.writeString(ddl, """
                create table customers (id number primary key);
                create table orders (id number primary key, customer_id number);
                alter table orders add constraint fk_o_c foreign key (customer_id) references customers (id);
                create index ix_orders_c on orders (customer_id);
                """);
        Catalog c = MetadataService.catalog("oracle", null, List.of(ddl.toString()), null);
        TableMeta orders = c.meta("ORDERS", "ORDERS").orElseThrow();
        assertEquals(1, orders.foreignKeys().size(), "CREATE INDEX keeps the key");
        assertEquals(1, orders.indexes().size());
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        ErModel m = ErModelBuilder.build(f, f.parse(SourceText.of(
                "select * from orders o join customers c on c.id = o.customer_id;")), c, ErModelBuilder.Options.DEFAULTS);
        ErModel.Relationship r = one(m, "ORDERS", "CUSTOMERS");
        assertEquals(Evidence.DECLARED_FK, r.evidence());
        assertEquals(1, r.count());
        assertEquals(ErModel.ColumnSource.DDL, ErTestSupport.column(m, "ORDERS", "customer_id").source());
        assertTrue(!ErTestSupport.entity(m, "ORDERS").createdInScript());
    }
}
