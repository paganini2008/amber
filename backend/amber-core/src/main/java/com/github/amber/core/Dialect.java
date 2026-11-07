package com.github.amber.core;

import java.util.Locale;

/**
 * The SQL dialects Amber understands. Each one has its own grammar and dialect module.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum Dialect {

    ORACLE("Oracle", NameCase.UPPER),
    SQLSERVER("SQL Server", NameCase.INSENSITIVE),
    POSTGRESQL("PostgreSQL", NameCase.LOWER),
    MYSQL("MySQL", NameCase.INSENSITIVE),
    MARIADB("MariaDB", NameCase.INSENSITIVE),
    DB2("Db2", NameCase.UPPER),
    H2("H2", NameCase.UPPER),
    SQLITE("SQLite", NameCase.INSENSITIVE);

    /**
     * How the database itself treats the case of unquoted identifiers. Amber matches names
     * case-insensitively regardless (see {@code ObjectName}); this is informational, e.g. for
     * generating SQL.
     */
    public enum NameCase {
        /** Unquoted names fold to upper case; quoted names are exact (Oracle, Db2, H2). */
        UPPER,
        /** Unquoted names fold to lower case; quoted names are exact (PostgreSQL). */
        LOWER,
        /** Names compare case-insensitively whether quoted or not (SQL Server, MySQL, MariaDB, SQLite). */
        INSENSITIVE
    }

    private final String displayName;
    private final NameCase nameCase;

    Dialect(String displayName, NameCase nameCase) {
        this.displayName = displayName;
        this.nameCase = nameCase;
    }

    public String displayName() {
        return displayName;
    }

    public NameCase nameCase() {
        return nameCase;
    }

    /** Stable identifier used in JSON-RPC, settings and file names, e.g. {@code "sqlserver"}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Dialect fromId(String id) {
        for (Dialect d : values()) {
            if (d.id().equalsIgnoreCase(id)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown dialect: " + id);
    }
}
