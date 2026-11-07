package com.github.amber.server;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Parses a small bundled script per dialect once at engine start, on a background thread. ANTLR
 * builds its prediction DFA lazily and shares it per parser class, so the first real script would
 * otherwise pay most of the cost (several seconds for T-SQL).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Warmup {

    private Warmup() {
    }

    static Thread startInBackground(PrintStream log) {
        Thread t = new Thread(null, () -> run(log), "amber-warmup", 64L * 1024 * 1024);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return t;
    }

    /** Warms the priority dialects first. */
    static void run(PrintStream log) {
        long started = System.nanoTime();
        Dialect[] order = {Dialect.ORACLE, Dialect.SQLSERVER, Dialect.POSTGRESQL, Dialect.MYSQL, Dialect.MARIADB,
                Dialect.DB2, Dialect.H2, Dialect.SQLITE};
        for (Dialect d : order) {
            DialectFrontend frontend = DialectFrontends.all().get(d);
            String sql = script(d);
            if (frontend != null && sql != null) {
                frontend.parse(new SourceText("warmup-" + d.id() + ".sql", sql), ParseOptions.FALLBACK);
            }
        }
        log.printf("amber: parsers warmed up in %d ms%n", (System.nanoTime() - started) / 1_000_000);
    }

    static String script(Dialect d) {
        try (InputStream in = Warmup.class.getResourceAsStream("/warmup/" + d.id() + ".sql")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
