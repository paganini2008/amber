package com.github.amber.dialect.findings;

import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The findings step stops cleanly when its time budget runs out or its thread is interrupted.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class FindingsBudgetTest {

    private static com.github.amber.core.ir.Script script() throws IOException {
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        String sql = new String(FindingsBudgetTest.class.getResourceAsStream("/sql/findings/risky_oracle.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        return ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
    }

    @Test
    void exhaustedBudgetReportsWhereChecksStopped() throws IOException {
        List<Finding> out = Findings.of(script(), new Catalog(), -1L);
        assertEquals(1, out.size(), out.toString());
        assertEquals("AMB-I003", out.get(0).rule());
    }

    @Test
    void generousBudgetRunsEveryCheck() throws IOException {
        List<Finding> out = Findings.of(script(), new Catalog(), Long.MAX_VALUE);
        assertTrue(out.stream().noneMatch(f -> f.rule().equals("AMB-I003")), out.toString());
        assertTrue(out.size() > 1, out.toString());
    }

    @Test
    void interruptedThreadCancels() throws IOException {
        var script = script();
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> Findings.of(script));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void catalogListsTheGuardRule() {
        assertTrue(RuleCatalog.all().stream().anyMatch(r -> r.id().equals("AMB-I003")));
    }
}
