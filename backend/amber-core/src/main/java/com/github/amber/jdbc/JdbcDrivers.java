package com.github.amber.jdbc;

import com.github.amber.core.Dialect;
import com.github.amber.core.i18n.Messages;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/**
 * Opens JDBC connections through the driver jar of the connection ({@link DataSourceSpec#driverPath()}), or
 * through a driver on the engine's class path ({@link DriverManager}; only tests have any).
 *
 * <p>Amber includes no driver: the desktop app downloads the one a connection needs from Maven Central on
 * demand, or the user chooses a jar. Without either, the error names the database whose driver is
 * missing ({@code db.driver.notInstalled}). The jar is loaded with its own class loader whose parent is the engine's; classes found in
 * the jar win over the engine's (child first), so a user jar can also replace a bundled driver. The
 * drivers in the jar are found through {@link ServiceLoader} ({@code META-INF/services/java.sql.Driver}),
 * with a list of well-known driver classes as a fallback, and the one that accepts the connection's URL
 * is used. The connection is opened with {@link Driver#connect} directly, since {@link DriverManager}
 * refuses drivers from a foreign class loader. Loaded jars are cached per path and modification time.
 *
 * @Author: Fred Feng
 * @Date: 09/11/2026
 * @Version 1.0.0
 */
public final class JdbcDrivers {

    /** Driver classes tried when a jar has no service entry for them. */
    static final List<String> KNOWN_DRIVERS = List.of("com.ibm.db2.jcc.DB2Driver", "oracle.jdbc.OracleDriver",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", "org.postgresql.Driver", "com.mysql.cj.jdbc.Driver",
            "org.mariadb.jdbc.Driver", "org.h2.Driver", "org.sqlite.JDBC");

    /** Finds a driver on the engine's class path for a URL; {@link DriverManager#getDriver} in production. */
    @FunctionalInterface
    interface Lookup {
        Driver find(String url) throws SQLException;
    }

    private record Loaded(long modified, long size, URLClassLoader loader, List<Driver> drivers) {
    }

    private static final Map<Path, Loaded> CACHE = new ConcurrentHashMap<>();

    private JdbcDrivers() {
    }

    /** Opens a connection to {@code spec} with the given properties (user, password, driver options). */
    public static Connection connect(DataSourceSpec spec, Properties props) throws SQLException {
        return connect(spec, props, DriverManager::getDriver);
    }

    static Connection connect(DataSourceSpec spec, Properties props, Lookup classpath) throws SQLException {
        String url = spec.jdbcUrl();
        Driver driver = spec.driverPath() != null && !spec.driverPath().isBlank()
                ? fromJar(spec.driverPath().strip(), url)
                : bundled(spec, url, classpath);
        Connection c = driver.connect(url, props);
        if (c == null) {            // the contract for a URL the driver does not handle
            throw new JdbcDriverException(Messages.t("db.driver.noDriverForUrl", spec.maskedUrl()));
        }
        return c;
    }

    private static Driver bundled(DataSourceSpec spec, String url, Lookup classpath) {
        try {
            Driver d = classpath.find(url);
            if (d != null) {
                return d;
            }
        } catch (SQLException e) {
            // "No suitable driver": explained below
        }
        // Amber includes no driver: the desktop app downloads it on demand and passes driverPath.
        String database = databaseName(spec, url);
        if (database != null) {
            throw new JdbcDriverException(Messages.t("db.driver.notInstalled", database));
        }
        throw new JdbcDriverException(Messages.t("db.driver.noDriverForUrl", spec.maskedUrl()));
    }

    /** The product name for the connection's dialect, or from the JDBC URL; null when unknown. */
    static String databaseName(DataSourceSpec spec, String url) {
        try {
            if (spec.dialect() != null) {
                return spec.dialectEnum().displayName();
            }
        } catch (IllegalArgumentException e) {
            // not a known dialect id: try the URL
        }
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        for (Dialect d : Dialect.values()) {
            String id = d.name().toLowerCase(Locale.ROOT);
            if (u.startsWith("jdbc:" + id + ":") || (d == Dialect.SQLSERVER && u.startsWith("jdbc:sqlserver:"))) {
                return d.displayName();
            }
        }
        return null;
    }

    /** The driver in the jar at {@code path} that accepts {@code url}. */
    static Driver fromJar(String path, String url) throws SQLException {
        Loaded loaded = load(path);
        for (Driver d : loaded.drivers()) {
            if (d.acceptsURL(url)) {
                return d;
            }
        }
        throw new JdbcDriverException(Messages.t("db.driver.noDriverInJar", path, DataSourceSpec.mask(url)));
    }

    private static Loaded load(String path) {
        Path p;
        try {
            p = Path.of(path);
        } catch (InvalidPathException e) {
            throw new JdbcDriverException(Messages.t("db.driver.notFound", path));
        }
        if (!p.isAbsolute()) {
            throw new JdbcDriverException(Messages.t("db.driver.notAbsolute", path));
        }
        if (!Files.exists(p)) {
            throw new JdbcDriverException(Messages.t("db.driver.notFound", path));
        }
        if (!Files.isRegularFile(p)) {
            throw new JdbcDriverException(Messages.t("db.driver.notAFile", path));
        }
        if (!Files.isReadable(p)) {
            throw new JdbcDriverException(Messages.t("db.driver.unreadable", path));
        }
        Path key = p.toAbsolutePath().normalize();
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(key).toMillis();
            size = Files.size(key);
        } catch (IOException e) {
            throw new JdbcDriverException(Messages.t("db.driver.unreadable", path));
        }
        Loaded cached = CACHE.get(key);
        if (cached != null && cached.modified() == modified && cached.size() == size) {
            return cached;
        }
        synchronized (CACHE) {
            cached = CACHE.get(key);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached;
            }
            Loaded fresh = open(key, path, modified, size);
            // A replaced jar's old loader is not closed: a connection opened with it may still be in use.
            CACHE.put(key, fresh);
            return fresh;
        }
    }

    private static Loaded open(Path key, String shown, long modified, long size) {
        try (JarFile jar = new JarFile(key.toFile())) {
            jar.size();                              // a readable zip directory, or an IOException
        } catch (IOException e) {
            throw new JdbcDriverException(Messages.t("db.driver.notAJar", shown,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        URL url;
        try {
            url = key.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new JdbcDriverException(Messages.t("db.driver.notFound", shown));
        }
        ChildFirstLoader loader = new ChildFirstLoader(url, JdbcDrivers.class.getClassLoader());
        List<Driver> drivers = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        try {
            ServiceLoader.load(Driver.class, loader).stream().forEach(provider -> {
                try {
                    if (provider.type().getClassLoader() == loader) {     // not a driver of the engine itself
                        drivers.add(provider.get());
                        seen.add(provider.type().getName());
                    }
                } catch (ServiceConfigurationError | LinkageError e) {
                    // a broken entry: the other drivers in the jar may still work
                }
            });
        } catch (ServiceConfigurationError e) {
            // unreadable service file: fall back to the known class names
        }
        for (String name : KNOWN_DRIVERS) {
            if (seen.contains(name)) {
                continue;
            }
            try {
                Class<?> c = Class.forName(name, false, loader);
                if (c.getClassLoader() == loader && Driver.class.isAssignableFrom(c)) {
                    drivers.add((Driver) c.getDeclaredConstructor().newInstance());
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                // not in this jar
            }
        }
        return new Loaded(modified, size, loader, List.copyOf(drivers));
    }

    /** Forgets cached jars; for tests. */
    static void clearCache() {
        CACHE.clear();
    }

    /** Number of cached jars; for tests. */
    static int cached() {
        return CACHE.size();
    }

    /**
     * Loads classes from the jar before asking the parent, except the platform's own ({@code java.*},
     * {@code javax.*}, {@code jdk.*}, {@code sun.*}), so the jar's driver is used even when the engine
     * bundles another version of it.
     */
    private static final class ChildFirstLoader extends URLClassLoader {

        static {
            registerAsParallelCapable();
        }

        ChildFirstLoader(URL jar, ClassLoader parent) {
            super(new URL[] {jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                    || name.startsWith("sun.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}
