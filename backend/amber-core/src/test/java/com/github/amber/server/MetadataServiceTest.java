package com.github.amber.server;

import com.github.amber.jdbc.DataSourceSpec;
import com.github.amber.jdbc.JdbcDriverException;
import org.h2.Driver;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code metadata.import}, {@code db.test} and {@code db.loadMetadata} against DDL files and an
 * embedded H2 database.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class MetadataServiceTest {

    private static final String URL_A = "jdbc:h2:mem:amber_svc_a;DB_CLOSE_DELAY=-1";
    private static final String URL_B = "jdbc:h2:mem:amber_svc_b;DB_CLOSE_DELAY=-1";
    private static Connection a;
    private static Connection b;

    @BeforeAll
    static void fixture() throws Exception {
        a = DriverManager.getConnection(URL_A);
        b = DriverManager.getConnection(URL_B);
        try (Statement s = a.createStatement()) {                     // test fixture only, not Amber code
            s.execute("create schema inv");
            s.execute("create table inv.item (id bigint primary key, sku varchar(40) not null)");
            s.execute("create table inv.stock (item_id bigint, qty int)");
        }
        try (Statement s = b.createStatement()) {
            s.execute("create schema inv");
            s.execute("create table inv.stock (item_id bigint, qty int, site varchar(10))");
        }
    }

    @AfterAll
    static void close() throws Exception {
        a.close();
        b.close();
    }

    private static DataSourceSpec ds(String id, String url) {
        return new DataSourceSpec(id, "h2", url, null, null, null, null, null, null);
    }

    @Test
    void importsDdlAndPacks(@TempDir Path dir) throws Exception {
        Path ddl = dir.resolve("schema.sql");
        Files.writeString(ddl, """
                create table inv.item (id bigint primary key, sku varchar(40) not null);
                create unique index ux_item_sku on inv.item (sku);
                create table inv.stock (item_id bigint, qty int);
                """);
        MetadataService svc = new MetadataService();
        Path pack = dir.resolve("inv.amber-meta.json");
        var summary = svc.importMetadata(new MetadataService.ImportRequest("h2", List.of(ddl.toString()), null,
                pack.toString()));
        assertEquals(2, summary.tables());
        assertEquals(pack.toString(), summary.saved());
        assertTrue(Files.exists(pack));

        var again = svc.importMetadata(new MetadataService.ImportRequest("h2", null, List.of(pack.toString()), null));
        assertEquals(2, again.tables());
        assertEquals(summary.names(), again.names());

        var catalog = MetadataService.catalog("h2", List.of(pack.toString()), List.of(ddl.toString()), List.of());
        assertEquals(2, catalog.tables().size());
        assertThrows(IllegalArgumentException.class,
                () -> svc.importMetadata(new MetadataService.ImportRequest(null, null, null, null)));
    }

    @Test
    void testsAConnection() throws Exception {
        MetadataService svc = new MetadataService();
        var info = svc.test(new MetadataService.TestRequest(ds("a", URL_A)));
        assertEquals("H2", info.product());
        assertThrows(IllegalArgumentException.class, () -> svc.test(new MetadataService.TestRequest(null)));
    }

    @Test
    void loadsReferencedTablesFromSeveralSources(@TempDir Path dir) throws Exception {
        MetadataService svc = new MetadataService();
        Path save = dir.resolve("online.json");
        var r = svc.load(new MetadataService.LoadRequest("h2", """
                create table tmp_total (item_id bigint, qty int);
                insert into tmp_total select s.item_id, sum(s.qty) from inv.stock s join inv.item i on i.id = s.item_id
                 group by s.item_id;
                select * from inv.nowhere;
                """, null, List.of(ds("a", URL_A), ds("b", URL_B)), save.toString()));
        assertEquals(2, r.tables(), r::toString);
        assertEquals(List.of("inv.nowhere"), r.missing().stream().map(String::toLowerCase).toList());
        assertEquals(1, r.ambiguous().size(), r::toString);
        assertEquals("inv.stock", r.ambiguous().get(0).name());
        assertEquals("a", r.ambiguous().get(0).chosen(), "no column decides: the first source wins");
        assertTrue(r.errors().isEmpty(), r::toString);
        assertEquals(2, r.servers().size());
        assertTrue(Files.exists(save));
        assertThrows(IllegalArgumentException.class,
                () -> svc.load(new MetadataService.LoadRequest("h2", "select 1", null, List.of(), null)));
    }

    @Test
    void ambiguousNamePrefersTheTableThatFitsTheScript() throws Exception {
        var r = new MetadataService().load(new MetadataService.LoadRequest("h2",
                "insert into tmp_site select s.site, s.qty from inv.stock s;", null,
                List.of(ds("a", URL_A), ds("b", URL_B)), null));
        assertEquals("b", r.ambiguous().get(0).chosen(), "only b has the column site");
        assertTrue(r.pack().tables().stream().anyMatch(t -> t.column("site").isPresent()));
    }

    @Test
    void driverPathTravelsThroughJsonRpcAndProblemsAreClear(@TempDir Path dir) throws Exception {
        var mapper = Main.mapper();
        var handlers = Methods.handlers(mapper);
        String h2Jar = Path.of(Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var ok = mapper.valueToTree(handlers.get("db.test").handle(mapper.readTree("{\"dataSource\":{\"id\":\"a\","
                + "\"dialect\":\"h2\",\"url\":\"" + URL_A + "\",\"driverPath\":" + mapper.writeValueAsString(h2Jar) + "}}")));
        assertEquals("H2", ok.get("product").asText());

        String missing = dir.resolve("jcc.jar").toString();
        // db.test: an invalid-params error (IllegalArgumentException), message in English
        var e = assertThrows(JdbcDriverException.class, () -> handlers.get("db.test").handle(
                mapper.readTree("{\"dataSource\":{\"id\":\"d\",\"dialect\":\"db2\",\"host\":\"h\",\"database\":\"D\","
                        + "\"driverPath\":" + mapper.writeValueAsString(missing) + "},\"locale\":\"es\"}")));
        assertTrue(e.getMessage().startsWith("The JDBC driver file " + missing + " does not exist."), e::getMessage);

        // db.loadMetadata: listed under errors, in the request's language; the other source still loads
        var r = (MetadataService.LoadResult) handlers.get("db.loadMetadata").handle(mapper.readTree(
                "{\"dialect\":\"h2\",\"text\":\"select * from inv.item\",\"locale\":\"es\",\"dataSources\":["
                        + "{\"id\":\"d\",\"dialect\":\"db2\",\"host\":\"h\",\"database\":\"D\",\"driverPath\":"
                        + mapper.writeValueAsString(missing) + "},"
                        + "{\"id\":\"a\",\"dialect\":\"h2\",\"url\":\"" + URL_A + "\"}]}"));
        assertEquals("El archivo del controlador JDBC " + missing + " no existe. Puede que se haya movido o borrado; "
                + "vuelva a elegir el archivo .jar en los ajustes de la conexión.", r.errors().get("d"));
        assertEquals(1, r.tables(), r::toString);
    }
}
