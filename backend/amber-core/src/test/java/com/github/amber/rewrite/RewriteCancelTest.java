package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An interrupted worker ({@code $/cancel}) stops suggesting and optimizing instead of finishing the script.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class RewriteCancelTest {

    private static final String SQL = "SELECT * FROM orders WHERE id IN (SELECT order_id FROM items);\n"
            + "SELECT DISTINCT a FROM t GROUP BY a;\n";

    /** Interrupted before it starts: parsing already stops (wrapped), which the server reads as cancel. */
    @Test
    void suggestStopsWhenInterrupted() {
        Thread.currentThread().interrupt();
        try {
            Throwable t = assertThrows(RuntimeException.class,
                    () -> RewriteSuggester.suggest(Dialect.POSTGRESQL, SQL, null, null, true));
            assertTrue(isCancellation(t), t.toString());
        } finally {
            Thread.interrupted();
        }
    }

    /** Interrupted after parsing, while candidates are verified: the next parse or the optimizer's own checks stop it. */
    @Test
    void optimizeStopsWhenInterruptedMidway() {
        try {
            Throwable t = assertThrows(RuntimeException.class, () -> Optimizer.optimize(Dialect.POSTGRESQL, SQL, null,
                    null, true, null, true, Map.of(), text -> {
                        Thread.currentThread().interrupt();
                        return text;
                    }));
            assertTrue(isCancellation(t), t.toString());
        } finally {
            Thread.interrupted();
        }
    }

    private static boolean isCancellation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CancellationException || t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
