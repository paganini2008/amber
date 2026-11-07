package com.github.amber.jdbc;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads metadata from embedded H2 and SQLite databases. The test fixture creates its own tables in a
 * private in-memory / temporary database; the reader under test only uses DatabaseMetaData.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class MetadataReaderTest {

    private static final String H2_URL = "jdbc:h2:mem:amber_meta_test;DB_CLOSE_DELAY=-1";
    private static Connection keepAlive;

    @BeforeAll
    static void fixture() throws Exception {
        keepAlive = DriverManager.getConnection(H2_URL);
        try (Statement s = keepAlive.createStatement()) {          // test fixture only, not Amber code
            s.execute("create schema shop");
            s.execute("create table shop.customer (id bigint primary key, email varchar(200) not null, tier varchar(10))");
            s.execute("create table shop.purchase (id bigint primary key, customer_id bigint not null, total decimal(12,2))");
            s.execute("create index ix_purchase_customer on shop.purchase (customer_id)");
            s.execute("create unique index ux_customer_email on shop.customer (email)");
        }
    }

    private static DataSourceSpec h2() {
        return new DataSourceSpec("h2-test", "h2", H2_URL, null, null, null, null, null, null);
    }

    @Test
    void readsOnlyTheRequestedTables() throws Exception {
        try (MetadataReader r = MetadataReader.open(h2())) {
            MetadataReader.ServerInfo info = r.test();
            assertEquals("H2", info.product());
            List<TableMeta> tables = r.read(List.of(ObjectName.parse(Dialect.H2, "shop.purchase"),
                    ObjectName.parse(Dialect.H2, "shop.missing_table")));
            assertEquals(1, tables.size());
            TableMeta purchase = tables.getFirst();
            assertEquals(List.of("ID", "CUSTOMER_ID", "TOTAL"), purchase.columnNames());
            assertEquals(List.of("ID"), purchase.primaryKey());
            assertTrue(purchase.isIndexedLeading("customer_id"));
            assertFalse(purchase.isUnique(List.of("CUSTOMER_ID")));
        }
    }

    @Test
    void findsUnqualifiedAndLowerCaseNames() throws Exception {
        try (MetadataReader r = MetadataReader.open(h2())) {
            TableMeta customer = r.read(List.of(ObjectName.parse(Dialect.H2, "SHOP.Customer"))).getFirst();
            assertTrue(customer.isUnique(List.of("email")));
        }
    }

    @Test
    void onlineLookupForAScript(@TempDir Path dir) throws Exception {
        Path sqlite = dir.resolve("app.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
            s.execute("create table audit_log (id integer primary key, note text)");   // fixture
        }
        DialectFrontend f = DialectFrontends.get(Dialect.H2);
        var script = ScriptIrBuilder.build(f, f.parse(SourceText.of("""
                create table tmp_x as select id from shop.purchase;
                insert into shop.customer (id, email) select id, 'x' from tmp_x;
                select * from shop.nowhere;
                """)));
        List<ObjectName> names = OnlineMetadata.referencedObjects(script);
        assertEquals(List.of("SHOP.PURCHASE", "SHOP.CUSTOMER", "SHOP.NOWHERE"), names.stream().map(ObjectName::key).toList(),
                "tables the script creates are not looked up");
        OnlineMetadata.Result r = OnlineMetadata.load(List.of(
                new DataSourceSpec("sqlite-test", "sqlite", null, null, null, sqlite.toString(), null, null, null), h2()), names);
        assertEquals(2, r.tables().size());
        assertEquals(List.of("shop.nowhere"), r.missing());
        assertEquals(2, r.servers().size());
        assertTrue(r.errors().isEmpty(), r.errors()::toString);
    }

    @Test
    void readOnlyGuaranteeNoStatementsInJdbcPackage() throws IOException {
        Path dir = Path.of("src/main/java/com/github/amber/jdbc");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String code = Files.readString(p)
                        .replaceAll("(?s)/\\*.*?\\*/", "")       // comments may mention the forbidden calls
                        .replaceAll("//[^\\n]*", "");
                for (String forbidden : List.of("createStatement", "prepareStatement", "prepareCall", ".execute(",
                        ".executeQuery(", ".executeUpdate(", "addBatch")) {
                    assertFalse(code.contains(forbidden), p + " must not run SQL (" + forbidden + ")");
                }
            }
        }
    }

    @Test
    void urlsAreBuiltPerDialectWithoutLeakingPasswords() {
        DataSourceSpec pg = new DataSourceSpec("pg", "postgresql", null, "db.local", 15432, "demo", "admin", "secret", null);
        assertEquals("jdbc:postgresql://db.local:15432/demo?readOnly=true", pg.jdbcUrl());
        assertFalse(pg.describe().contains("secret"));
        assertTrue(new DataSourceSpec("o", "oracle", null, "h", null, "DEMO", null, null, null).jdbcUrl()
                .equals("jdbc:oracle:thin:@//h:1521/DEMO"));
        assertTrue(new DataSourceSpec("s", "sqlserver", null, "h", 11433, "demo", null, null, null).jdbcUrl()
                .contains("applicationIntent=ReadOnly"));
    }
}
