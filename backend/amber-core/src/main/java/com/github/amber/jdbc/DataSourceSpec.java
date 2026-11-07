package com.github.amber.jdbc;

import com.github.amber.core.Dialect;

import java.util.Map;

/**
 * A database to read metadata from. Either {@code url} is given, or it is built from host, port and
 * database. The password is passed per request by the desktop app (which stores it encrypted) and is
 * never logged or saved by the engine.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record DataSourceSpec(String id, String dialect, String url, String host, Integer port, String database,
                             String user, String password, Map<String, String> properties) {

    public Dialect dialectEnum() {
        return Dialect.fromId(dialect);
    }

    /** The JDBC URL to connect with. */
    public String jdbcUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        String h = host == null || host.isBlank() ? "localhost" : host;
        String db = database == null ? "" : database;
        return switch (dialectEnum()) {
            case ORACLE -> "jdbc:oracle:thin:@//" + h + ":" + port(1521) + "/" + db;
            case SQLSERVER -> "jdbc:sqlserver://" + h + ":" + port(1433) + ";databaseName=" + db
                    + ";encrypt=true;trustServerCertificate=true;applicationIntent=ReadOnly";
            case POSTGRESQL -> "jdbc:postgresql://" + h + ":" + port(5432) + "/" + db + "?readOnly=true";
            case MYSQL -> "jdbc:mysql://" + h + ":" + port(3306) + "/" + db;
            case MARIADB -> "jdbc:mariadb://" + h + ":" + port(3306) + "/" + db;
            case DB2 -> "jdbc:db2://" + h + ":" + port(50000) + "/" + db;
            case H2 -> "jdbc:h2:" + db;
            case SQLITE -> "jdbc:sqlite:" + db;
        };
    }

    private int port(int fallback) {
        return port == null || port <= 0 ? fallback : port;
    }

    /** For messages: never includes the password. */
    public String describe() {
        return (id == null ? "" : id + " ") + "(" + dialect + " " + (url != null && !url.isBlank() ? url.replaceAll("password=[^;&]*", "password=***")
                : (host == null ? "" : host) + (port == null ? "" : ":" + port) + "/" + (database == null ? "" : database)) + ")";
    }
}
