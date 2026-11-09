package com.github.amber.jdbc;

import com.github.amber.core.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JDBC driver chosen by the user as a jar ({@code driverPath}): loaded from the jar (real H2 and IBM
 * jcc jars from the test class path), cached per path and modification time, and clear messages when the
 * driver is not installed (named per database) or the jar is missing, unreadable, not a jar, or holds no driver for the URL.
 *
 * @Author: Fred Feng
 * @Date: 09/11/2026
 * @Version 1.0.0
 */
class JdbcDriversTest {

    private static final String H2_URL = "jdbc:h2:mem:amber_driver_path;DB_CLOSE_DELAY=-1";

    @AfterEach
    void forget() {
        JdbcDrivers.clearCache();
    }

    private static Path jarOf(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static DataSourceSpec spec(String dialect, String url, String driverPath) {
        return new DataSourceSpec("t", dialect, url, null, null, null, null, null, Map.of(), driverPath);
    }

    private static JdbcDriverException failure(DataSourceSpec spec) {
        return assertThrows(JdbcDriverException.class, () -> {
            try (MetadataReader r = MetadataReader.open(spec)) {
                r.test();
            }
        });
    }

    @Test
    void connectsThroughTheH2JarChosenAsDriverPath() throws Exception {
        Path h2 = jarOf(org.h2.Driver.class);
        assertTrue(h2.toString().endsWith(".jar"), h2::toString);
        try (MetadataReader r = MetadataReader.open(spec("h2", H2_URL, "  " + h2 + " "))) {
            assertEquals("H2", r.test().product());
        }
        Driver d = JdbcDrivers.fromJar(h2.toString(), H2_URL);
        assertEquals("org.h2.Driver", d.getClass().getName());
        assertNotSame(org.h2.Driver.class.getClassLoader(), d.getClass().getClassLoader(),
                "loaded from the jar, not the engine's class path (child first)");
        assertSame(d, JdbcDrivers.fromJar(h2.toString(), H2_URL), "cached per path and modification time");
        assertEquals(1, JdbcDrivers.cached());
    }

    @Test
    void reloadsAJarWhenItChanges(@TempDir Path dir) throws Exception {
        Path copy = Files.copy(jarOf(org.h2.Driver.class), dir.resolve("h2 copy.jar"));
        Driver first = JdbcDrivers.fromJar(copy.toString(), H2_URL);
        Files.setLastModifiedTime(copy, FileTime.fromMillis(Files.getLastModifiedTime(copy).toMillis() - 60_000));
        Driver second = JdbcDrivers.fromJar(copy.toString(), H2_URL);
        assertNotSame(first.getClass().getClassLoader(), second.getClass().getClassLoader());
        assertSame(second, JdbcDrivers.fromJar(copy.toString(), H2_URL));
    }

    @Test
    void findsTheDb2DriverInTheJccJar() throws Exception {
        Path jcc = jarOf(Class.forName("com.ibm.db2.jcc.DB2Driver"));
        Driver d = JdbcDrivers.fromJar(jcc.toString(), "jdbc:db2://db.example.local:50000/DEMO");
        assertEquals("com.ibm.db2.jcc.DB2Driver", d.getClass().getName());
        // the jcc jar holds no H2 driver, even though the engine has one
        JdbcDriverException e = assertThrows(JdbcDriverException.class, () -> JdbcDrivers.fromJar(jcc.toString(), H2_URL));
        assertTrue(e.getMessage().startsWith("The jar " + jcc + " contains no JDBC driver that accepts jdbc:h2:"),
                e::getMessage);
    }

    @Test
    void knownDriverClassesAreFoundWithoutAServiceEntry(@TempDir Path dir) throws Exception {
        Path stripped = dir.resolve("h2-no-services.jar");
        try (JarFile in = new JarFile(jarOf(org.h2.Driver.class).toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(stripped))) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) {
                    continue;
                }
                out.putNextEntry(new JarEntry(e.getName()));
                try (InputStream is = in.getInputStream(e)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }
        }
        try (MetadataReader r = MetadataReader.open(spec("h2", H2_URL, stripped.toString()))) {
            assertEquals("H2", r.test().product());
        }
    }

    @Test
    void aJarWithoutADriverSaysSo(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("empty.jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os)) {
            out.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
            out.write("com.example.NoSuchDriver\norg.h2.Driver\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("readme.txt"));
            out.write('x');
            out.closeEntry();
        }
        JdbcDriverException e = failure(spec("h2", "jdbc:h2:mem:x;password=secret", jar.toString()));
        assertEquals("The jar " + jar + " contains no JDBC driver that accepts jdbc:h2:mem:x;password=***. "
                + "Check that it is the driver for this database.", e.getMessage());
    }

    @Test
    void aMissingDriverNamesTheDatabaseToDownload() throws Exception {
        JdbcDrivers.Lookup none = url -> {
            throw new SQLException("No suitable driver found for " + url, "08001");
        };
        DataSourceSpec db2 = new DataSourceSpec("d", "db2", null, "db.local", 50000, "DEMO", "u", "p", null);
        JdbcDriverException e = assertThrows(JdbcDriverException.class,
                () -> JdbcDrivers.connect(db2, new Properties(), none));
        assertTrue(e.getMessage().startsWith("The Db2 JDBC driver is not installed. Amber does not include database drivers"),
                e::getMessage);
        assertTrue(e.getMessage().contains("Download driver"), e::getMessage);
        // known by its URL too, and a lookup that finds nothing is the same as one that throws
        assertEquals(e.getMessage(), assertThrows(JdbcDriverException.class, () -> JdbcDrivers.connect(
                spec(null, "jdbc:DB2://h:50000/D", null), new Properties(), url -> null)).getMessage());
        assertEquals(e.getMessage(), assertThrows(JdbcDriverException.class, () -> JdbcDrivers.connect(
                spec("informix", "jdbc:db2://h:50000/D", " "), new Properties(), none)).getMessage());
        // every dialect, by its product name
        assertTrue(assertThrows(JdbcDriverException.class, () -> JdbcDrivers.connect(spec("postgresql",
                "jdbc:postgresql://h/d", null), new Properties(), url -> null)).getMessage()
                .startsWith("The PostgreSQL JDBC driver is not installed."));
        assertTrue(assertThrows(JdbcDriverException.class, () -> JdbcDrivers.connect(spec(null,
                "jdbc:sqlserver://h:1433;databaseName=d", null), new Properties(), url -> null)).getMessage()
                .startsWith("The SQL Server JDBC driver is not installed."));
        // in the request's language: the zh-CN bundle text with the product name filled in
        Messages zh = Messages.of("zh-CN");
        String expected = Messages.call(zh, () -> Messages.t("db.driver.notInstalled", "Db2"));
        assertEquals(expected, assertThrows(JdbcDriverException.class, () -> Messages.call(zh,
                () -> JdbcDrivers.connect(db2, new Properties(), none))).getMessage());
        assertFalse(expected.startsWith("The Db2"), expected);
    }

    @Test
    void unknownDatabasesWithoutADriverNameTheUrlWithoutThePassword() {
        JdbcDriverException e = assertThrows(JdbcDriverException.class, () -> JdbcDrivers.connect(
                spec("informix", "jdbc:informix-sqli://h:9088/d:password=pw;user=u", null), new Properties(), url -> null));
        assertEquals("No JDBC driver for jdbc:informix-sqli://h:9088/d:password=***;user=u is available. "
                + "Choose the driver .jar under JDBC driver (.jar) in the connection settings.", e.getMessage());
        assertFalse(e.getMessage().contains("pw"));
    }

    @Test
    void aDriverThatDeclinesTheUrlIsExplained() {
        Driver declines = new StubDriver();
        JdbcDriverException e = assertThrows(JdbcDriverException.class, () -> JdbcDrivers.connect(
                spec("h2", "jdbc:h2:mem:declined", null), new Properties(), url -> declines));
        assertTrue(e.getMessage().startsWith("No JDBC driver for jdbc:h2:mem:declined"), e::getMessage);
    }

    @Test
    void badPathsAreExplained(@TempDir Path dir) throws Exception {
        assertEquals("The JDBC driver path drivers/jcc.jar is not an absolute path. Choose the .jar file again in the "
                + "connection settings.", failure(spec("db2", "jdbc:db2://h:1/d", "drivers/jcc.jar")).getMessage());
        Path missing = dir.resolve("gone.jar");
        assertEquals("The JDBC driver file " + missing + " does not exist. It may have been moved or deleted; choose "
                + "the .jar file again in the connection settings.", failure(spec("db2", "jdbc:db2://h:1/d",
                missing.toString())).getMessage());
        assertTrue(failure(spec("db2", "jdbc:db2://h:1/d", dir + "/bad\0name.jar")).getMessage()
                .contains("does not exist"));
        assertEquals("The JDBC driver path " + dir + " is a folder, not a .jar file.",
                failure(spec("db2", "jdbc:db2://h:1/d", dir.toString())).getMessage());
        Path text = Files.writeString(dir.resolve("notes.jar"), "not a zip");
        String notAJar = failure(spec("db2", "jdbc:db2://h:1/d", text.toString())).getMessage();
        assertTrue(notAJar.startsWith("The JDBC driver file " + text + " is not a valid .jar file ("), notAJar);
        Path locked = Files.copy(jarOf(org.h2.Driver.class), dir.resolve("locked.jar"));
        if (locked.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
            try {
                if (!Files.isReadable(locked)) {            // root can read anything
                    assertEquals("The JDBC driver file " + locked + " cannot be read. Check its permissions.",
                            failure(spec("h2", H2_URL, locked.toString())).getMessage());
                }
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
            }
        }
        assertEquals(0, JdbcDrivers.cached(), "nothing cached for a bad path");
    }

    @Test
    void knownDriversCoverEveryDialect() {
        assertEquals(8, JdbcDrivers.KNOWN_DRIVERS.size());
        assertTrue(JdbcDrivers.KNOWN_DRIVERS.contains("com.ibm.db2.jcc.DB2Driver"));
    }

    @Test
    void maskHidesPasswordsInAnyCase() {
        assertEquals("", DataSourceSpec.mask(null));
        assertEquals("jdbc:x://h/d?user=u&password=***&ssl=true", DataSourceSpec.mask("jdbc:x://h/d?user=u&PASSWORD=s3&ssl=true"));
        assertEquals("jdbc:db2://h:50000/D", spec("db2", "jdbc:db2://h:50000/D", null).maskedUrl());
    }

    /** Accepts every URL and opens nothing, as a driver does for a URL it does not handle. */
    private static final class StubDriver implements Driver {
        @Override
        public Connection connect(String url, Properties info) {
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}
