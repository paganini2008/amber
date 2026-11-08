package com.github.amber.jdbc;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hard deadline for database conversations, including a server that accepts and never answers.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class DeadlineTest {

    @Test
    void passesResultsAndErrorsThrough() throws Exception {
        assertEquals("ok", Deadline.within(5, "test", () -> "ok"));
        SQLException e = assertThrows(SQLException.class,
                () -> Deadline.within(5, "test", () -> { throw new SQLException("login failed"); }));
        assertEquals("login failed", e.getMessage());
        assertTrue(Deadline.SECONDS >= 1);
    }

    @Test
    void givesUpOnAServerThatNeverAnswers() throws Exception {
        List<Socket> held = new ArrayList<>();
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!silent.isClosed()) {
                        held.add(silent.accept());            // accept, then say nothing
                    }
                } catch (Exception ignored) {
                    // closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            DataSourceSpec spec = new DataSourceSpec("silent", "postgresql",
                    "jdbc:postgresql://127.0.0.1:" + silent.getLocalPort() + "/x", null, null, null, "u", "p", Map.of());
            long started = System.nanoTime();
            SQLTimeoutException e = assertThrows(SQLTimeoutException.class,
                    () -> Deadline.within(2, "silent", () -> {
                        try (MetadataReader r = MetadataReader.open(spec)) {
                            return r.test();
                        }
                    }));
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;
            assertTrue(seconds < 6, () -> "took " + seconds + " s");
            assertTrue(e.getMessage().startsWith("No answer from silent within 2 seconds"), e.getMessage());
        } finally {
            for (Socket s : held) {
                s.close();
            }
        }
    }
}
