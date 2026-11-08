package com.github.amber.jdbc;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.meta.TableMeta;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Reads table metadata through {@link DatabaseMetaData} only: Amber itself never writes or sends a SQL
 * statement; the driver queries its own catalog. Connections are opened read-only with short
 * timeouts, and only the objects asked for are looked up (never a full catalog scan).
 *
 * <p>Read-only guarantee: this package must not call {@code createStatement}, {@code prepareStatement},
 * {@code prepareCall} or {@code execute*}; a unit test enforces it.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class MetadataReader implements AutoCloseable {

    /** Login, network and metadata-call timeout ({@code AMBER_JDBC_TIMEOUT_SECONDS}, default 15). */
    private static final int TIMEOUT_SECONDS = Math.max(1,
            com.github.amber.core.config.Env.get().integer("AMBER_JDBC_TIMEOUT_SECONDS", 15));

    /** Result of {@link #test()}. */
    public record ServerInfo(String product, String version, int majorVersion, int minorVersion, String user,
                             boolean readOnly) {
    }

    private final DataSourceSpec spec;
    private final Connection connection;
    private final DatabaseMetaData meta;

    private MetadataReader(DataSourceSpec spec, Connection connection) throws SQLException {
        this.spec = spec;
        this.connection = connection;
        this.meta = connection.getMetaData();
    }

    public static MetadataReader open(DataSourceSpec spec) throws SQLException {
        DriverManager.setLoginTimeout(TIMEOUT_SECONDS);
        Properties props = new Properties();
        if (spec.properties() != null) {
            props.putAll(spec.properties());
        }
        if (spec.user() != null) {
            props.setProperty("user", spec.user());
        }
        if (spec.password() != null) {
            props.setProperty("password", spec.password());
        }
        Connection c = DriverManager.getConnection(spec.jdbcUrl(), props);
        try {
            c.setReadOnly(true);
        } catch (SQLException ignored) {
            // some drivers do not support the hint; the reader never writes anyway
        }
        try {
            c.setNetworkTimeout(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "amber-jdbc-timeout");
                t.setDaemon(true);
                return t;
            }), TIMEOUT_SECONDS * 1000);
        } catch (SQLException | UnsupportedOperationException | AbstractMethodError ignored) {
            // optional
        }
        return new MetadataReader(spec, c);
    }

    public ServerInfo test() throws SQLException {
        return new ServerInfo(meta.getDatabaseProductName(), meta.getDatabaseProductVersion(),
                meta.getDatabaseMajorVersion(), meta.getDatabaseMinorVersion(), meta.getUserName(),
                connection.isReadOnly());
    }

    /**
     * Metadata of the named tables and views that exist in this data source; names not found are
     * simply absent from the result.
     */
    public List<TableMeta> read(List<ObjectName> names) throws SQLException {
        Map<String, TableMeta> out = new LinkedHashMap<>();
        for (ObjectName name : names) {
            TableMeta t = find(name);
            if (t != null) {
                out.putIfAbsent(t.qualifiedName().toUpperCase(Locale.ROOT), t);
            }
        }
        return new ArrayList<>(out.values());
    }

    private TableMeta find(ObjectName name) throws SQLException {
        List<String> parts = name.parts().stream().map(ObjectName.Part::text).toList();
        String table = parts.get(parts.size() - 1);
        String schema = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
        String catalog = parts.size() >= 3 ? parts.get(parts.size() - 3) : null;
        boolean catalogIsDatabase = spec.dialectEnum() == Dialect.MYSQL || spec.dialectEnum() == Dialect.MARIADB;
        if (catalogIsDatabase && parts.size() == 2) {
            catalog = schema;                         // MySQL: db.table
            schema = null;
        }
        String defaultSchema = safeSchema();
        for (String t : variants(table)) {
            for (String s : schema != null ? variants(schema) : nullOr(defaultSchema)) {
                for (String c : catalog != null ? variants(catalog) : nullList()) {
                    try (ResultSet rs = meta.getTables(c, s, t, new String[] {"TABLE", "VIEW", "BASE TABLE",
                            "MATERIALIZED VIEW", "GLOBAL TEMPORARY", "SYNONYM", "ALIAS"})) {
                        if (rs.next()) {
                            return describe(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"),
                                    rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"));
                        }
                    }
                }
            }
        }
        return null;
    }

    private TableMeta describe(String catalog, String schema, String table, String type) throws SQLException {
        List<TableMeta.ColumnMeta> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, null)) {
            while (rs.next()) {
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                columns.add(new TableMeta.ColumnMeta(rs.getString("COLUMN_NAME"),
                        size > 0 && typeName != null && !typeName.contains("(") ? typeName + "(" + size + ")" : typeName,
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
            }
        }
        List<String> pk = new ArrayList<>();
        Map<Short, String> pkBySeq = new java.util.TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                pkBySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        } catch (SQLException ignored) {
            // views and some drivers
        }
        pk.addAll(pkBySeq.values());
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();
        Set<String> unique = new LinkedHashSet<>();
        long rows = -1;
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, true)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    rows = Math.max(rows, rs.getLong("CARDINALITY"));
                    continue;
                }
                String ix = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (ix == null || col == null) {
                    continue;
                }
                indexColumns.computeIfAbsent(ix, k -> new ArrayList<>()).add(col);
                if (!rs.getBoolean("NON_UNIQUE")) {
                    unique.add(ix);
                }
            }
        } catch (SQLException ignored) {
            // views and some drivers
        }
        List<TableMeta.IndexMeta> indexes = new ArrayList<>();
        List<List<String>> uniqueKeys = new ArrayList<>();
        indexColumns.forEach((ix, cols) -> {
            indexes.add(new TableMeta.IndexMeta(ix, cols, unique.contains(ix)));
            if (unique.contains(ix)) {
                uniqueKeys.add(cols);
            }
        });
        String kind = type != null && type.toUpperCase(Locale.ROOT).contains("VIEW") ? "VIEW" : "TABLE";
        return new TableMeta(catalog, schema, table, kind, columns, pk, uniqueKeys, indexes, rows,
                spec.id() == null ? spec.describe() : spec.id(),
                "VIEW".equals(kind) ? List.of() : importedKeys(catalog, schema, table));
    }

    /**
     * Foreign keys of a table through {@link DatabaseMetaData#getImportedKeys} (the driver reads its own
     * catalog); columns in key order. Drivers without support simply give none.
     */
    List<TableMeta.ForeignKeyMeta> importedKeys(String catalog, String schema, String table) {
        record Part(String name, String refCatalog, String refSchema, String refTable, short seq, String column,
                    String refColumn) {
        }
        Map<String, List<Part>> byKey = new LinkedHashMap<>();
        Map<String, String> current = new java.util.HashMap<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                Part p = new Part(rs.getString("FK_NAME"), rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_SCHEM"),
                        rs.getString("PKTABLE_NAME"), rs.getShort("KEY_SEQ"), rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKCOLUMN_NAME"));
                if (p.refTable() == null || p.column() == null) {
                    continue;
                }
                // Unnamed keys: one per referenced table; KEY_SEQ restarting at 1 starts another key.
                boolean named = p.name() != null && !p.name().isBlank();
                String base = named ? p.name() : p.refCatalog() + "." + p.refSchema() + "." + p.refTable();
                String key = current.get(base);
                if (key == null || (!named && p.seq() <= 1)) {
                    key = base + "#" + byKey.size();
                    current.put(base, key);
                }
                List<Part> parts = byKey.computeIfAbsent(key, k -> new ArrayList<>());
                parts.add(p);
            }
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            return List.of();                             // optional: some drivers / objects do not support it
        }
        List<TableMeta.ForeignKeyMeta> out = new ArrayList<>();
        for (List<Part> parts : byKey.values()) {
            parts.sort(java.util.Comparator.comparingInt(Part::seq));
            Part first = parts.get(0);
            boolean refColumnsKnown = parts.stream().allMatch(x -> x.refColumn() != null);
            out.add(new TableMeta.ForeignKeyMeta(first.name() == null || first.name().isBlank() ? null : first.name(),
                    parts.stream().map(Part::column).toList(), first.refCatalog(), first.refSchema(), first.refTable(),
                    refColumnsKnown ? parts.stream().map(Part::refColumn).toList() : List.of()));
        }
        return out;
    }

    private String safeSchema() {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError e) {
            return null;
        }
    }

    /** Name as written, upper-case and lower-case: catalogs fold names differently. */
    private static List<String> variants(String name) {
        Set<String> v = new LinkedHashSet<>();
        v.add(name);
        v.add(name.toUpperCase(Locale.ROOT));
        v.add(name.toLowerCase(Locale.ROOT));
        return new ArrayList<>(v);
    }

    private static List<String> nullOr(String value) {
        List<String> l = new ArrayList<>();
        if (value != null) {
            l.add(value);
        }
        l.add(null);
        return l;
    }

    private static List<String> nullList() {
        List<String> l = new ArrayList<>();
        l.add(null);
        return l;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
