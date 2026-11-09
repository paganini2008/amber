package com.github.amber.jdbc;

/**
 * No usable JDBC driver for a data source: the driver is not bundled (IBM Db2), or the driver jar the user
 * chose is missing, unreadable, not a jar, or holds no driver for the connection's URL. A configuration
 * problem, not an engine error: {@code db.test} answers it as invalid params with the message alone, and
 * {@code db.loadMetadata} lists the message under {@code errors}.
 *
 * @Author: Fred Feng
 * @Date: 09/11/2026
 * @Version 1.0.0
 */
public final class JdbcDriverException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public JdbcDriverException(String message) {
        super(message);
    }
}
