package com.github.amber.dialect;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statements that drive a grammar's prediction into exponential time and memory are refused quickly and
 * reported as syntax errors. Found by the mutation fuzzer: a MySQL statement starting with 16 or more
 * opening parentheses (legal: a parenthesized query) filled a 2 GB heap.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ParserGuardTest {

    private static final DialectFrontend MYSQL = DialectFrontends.get(Dialect.MYSQL);

    private static ScriptParse parse(String sql, ParseOptions options) {
        return MYSQL.parse(SourceText.of(sql), options);
    }

    @Test
    void deeplyParenthesizedMySqlQueriesAreRefusedQuickly() {
        String[] refused = {
                "(".repeat(58) + "UPDATE))))))) t SET a = 1;",
                "(".repeat(16) + "SELECT 1 FROM t" + ")".repeat(16) + ";",
                "(SELECT 1 FROM t) UNION ALL " + "(".repeat(20) + "SELECT 2 FROM u" + ")".repeat(20) + ";"};
        for (String sql : refused) {
            long t0 = System.nanoTime();
            ScriptParse strict = parse(sql, ParseOptions.STRICT);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 2_000, ms + " ms");
            assertEquals(ScriptStatus.FAILED, strict.status());
            assertTrue(strict.diagnostics().get(0).message().contains("parentheses in a row"),
                    strict.diagnostics().toString());
            String script = "SELECT a FROM t1;\n" + sql + "\nSELECT b FROM t2;\n";
            ScriptParse fallback = parse(script, ParseOptions.FALLBACK);
            assertEquals(ScriptStatus.PARTIAL, fallback.status());
            assertEquals(2, fallback.count(UnitStatus.PARSED));
        }
    }

    @Test
    void ordinaryNestingStillParses() {
        String[] accepted = {
                "(".repeat(12) + "SELECT 1 FROM t" + ")".repeat(12) + ";",
                "SELECT * FROM (".repeat(50) + "SELECT 1 FROM t" + ") x".repeat(50) + ";",
                "SELECT * FROM t WHERE a IN " + "(".repeat(40) + "SELECT 1 FROM u" + ")".repeat(40) + ";",
                "SELECT " + "(".repeat(100) + "1" + ")".repeat(100) + " FROM t;",
                "(SELECT 1 FROM t) UNION ((SELECT 2 FROM u));"};
        for (String sql : accepted) {
            ScriptParse p = parse(sql, ParseOptions.STRICT);
            assertTrue(p.success(), () -> sql.substring(0, 40) + ": " + p.diagnostics());
        }
    }

    @Test
    void otherDialectsHaveNoSuchLimit() {
        String sql = "(".repeat(30) + "SELECT 1 FROM t" + ")".repeat(30) + ";";
        assertTrue(DialectFrontends.get(Dialect.POSTGRESQL).parse(SourceText.of(sql)).success());
    }
}
