package com.github.amber.jdbc;

import java.sql.SQLTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A hard deadline for one database conversation (connect, test, read metadata). JDBC login and network
 * timeouts do not fire for every driver when a server accepts the connection and then never answers; this
 * runs the work on a daemon thread and gives up after {@code AMBER_DB_DEADLINE_SECONDS} (default 45),
 * interrupting the worker. The abandoned work closes its own connection when it ends.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class Deadline {

    /** Default seconds for one data source; the desktop app gives up at 60 s, so the engine answers first. */
    public static final int SECONDS = Math.max(1,
            com.github.amber.core.config.Env.get().integer("AMBER_DB_DEADLINE_SECONDS", 45));

    private Deadline() {
    }

    public static <T> T within(String what, Callable<T> work) throws Exception {
        return within(SECONDS, what, work);
    }

    public static <T> T within(int seconds, String what, Callable<T> work) throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "amber-db-deadline");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<T> f = exec.submit(work);
            try {
                return f.get(seconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw new SQLTimeoutException("No answer from " + what + " within " + seconds
                        + " seconds. Check the host, port and network, or whether the server is busy.");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw e;
            } catch (InterruptedException e) {
                f.cancel(true);                     // the request itself was cancelled
                Thread.currentThread().interrupt();
                throw e;
            }
        } finally {
            exec.shutdown();
        }
    }
}
