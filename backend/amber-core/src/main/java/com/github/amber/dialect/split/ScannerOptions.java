package com.github.amber.dialect.split;

/**
 * Lexical rules that differ between dialects and matter for finding statement boundaries.
 *
 * @param nestedBlockComments  {@code /* /* *\/ *\/} nests (PostgreSQL, SQL Server, Db2, H2)
 * @param bracketIdentifiers   {@code [name]} is a quoted identifier (SQL Server)
 * @param dollarQuotes         {@code $tag$ … $tag$} strings (PostgreSQL)
 * @param qQuotes              {@code q'[ … ]'} alternative quoting (Oracle)
 * @param escapeStrings        {@code E'…'} strings with backslash escapes (PostgreSQL)
 * @param sigilWords           words may start with {@code @} or {@code #} (SQL Server variables, temp tables)
 * @param backtickIdentifiers  {@code `name`} is a quoted identifier (MySQL, MariaDB)
 * @param hashComments         {@code #} starts a line comment (MySQL, MariaDB)
 * @param backslashStrings     backslash escapes inside every quoted string (MySQL, MariaDB default SQL mode)
 * @param hashInNames          {@code #} may appear inside names ({@code a#b}; Oracle, SQL Server, Db2); in
 *                             PostgreSQL it is an operator ({@code json #> path}), in MySQL a comment
 * @param dashCommentNeedsSpace {@code --} starts a comment only when followed by whitespace, so
 *                             {@code 5--1} is arithmetic (MySQL, MariaDB)
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record ScannerOptions(boolean nestedBlockComments, boolean bracketIdentifiers, boolean dollarQuotes,
                             boolean qQuotes, boolean escapeStrings, boolean sigilWords,
                             boolean backtickIdentifiers, boolean hashComments, boolean backslashStrings,
                             boolean dashCommentNeedsSpace, boolean slashComments,
                             boolean versionedComments, boolean hashInNames) {

    public static final ScannerOptions ORACLE = new ScannerOptions(false, false, false, true, false, false, false, false, false, false, false, false, true);
    public static final ScannerOptions SQLSERVER = new ScannerOptions(true, true, false, false, false, true, false, false, false, false, false, false, true);
    public static final ScannerOptions POSTGRESQL = new ScannerOptions(true, false, true, false, true, false, false, false, false, false, false, false, false);
    public static final ScannerOptions MYSQL = new ScannerOptions(false, false, false, false, false, false, true, true, true, true, false, true, false);
    public static final ScannerOptions DB2 = new ScannerOptions(true, false, false, false, false, false, false, false, false, false, false, false, true);
    public static final ScannerOptions SQLITE = new ScannerOptions(false, true, false, false, false, false, true, false, false, false, false, false, false);
    public static final ScannerOptions H2 = new ScannerOptions(true, false, true, false, false, false, false, false, false, false, true, false, false);

    /** Lexical rules for a dialect; MariaDB shares MySQL's. */
    public static ScannerOptions forDialect(com.github.amber.core.Dialect dialect) {
        return switch (dialect) {
            case ORACLE -> ORACLE;
            case SQLSERVER -> SQLSERVER;
            case POSTGRESQL -> POSTGRESQL;
            case MYSQL, MARIADB -> MYSQL;
            case DB2 -> DB2;
            case H2 -> H2;
            case SQLITE -> SQLITE;
        };
    }
}
