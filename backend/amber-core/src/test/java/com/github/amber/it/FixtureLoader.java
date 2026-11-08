package com.github.amber.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test-fixture helper, not part of Amber: creates the online-mode fixture tables by running a file of
 * create/insert statements, one per line ending in {@code ;}. Usage:
 * {@code FixtureLoader <url> <user> <password> <file>}. Statements that fail (e.g. the object already
 * exists) are reported and skipped.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class FixtureLoader {

    private FixtureLoader() {
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(args[0], args[1], args[2]); Statement s = c.createStatement()) {
            for (String line : Files.readAllLines(Path.of(args[3]))) {
                String sql = line.strip();
                if (sql.isEmpty() || sql.startsWith("--")) {
                    continue;
                }
                if (sql.endsWith(";")) {
                    sql = sql.substring(0, sql.length() - 1);
                }
                try {
                    s.execute(sql);
                } catch (SQLException e) {
                    System.out.println("skipped: " + e.getMessage().lines().findFirst().orElse("") + " <- "
                            + sql.substring(0, Math.min(60, sql.length())));
                }
            }
            if (!c.getAutoCommit()) {
                c.commit();
            }
        }
        System.out.println("done " + args[3]);
    }
}
