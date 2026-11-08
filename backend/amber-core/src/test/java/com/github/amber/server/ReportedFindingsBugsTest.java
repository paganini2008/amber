package com.github.amber.server;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Problems the robustness pass found in packages owned by the rules work ({@code dialect/findings/**},
 * {@code rewrite/**}); kept as regression tests.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ReportedFindingsBugsTest {

    /**
     * Fuzzer seed {@code samples/mariadb-helpdesk.sql#1571413642} (seed 7, parens(24)): a JOIN whose ON is
     * wrapped in two dozen parentheses, parsed with fallback. {@code Findings.of} never returns and fills
     * the heap ({@code findings.rules.Scope.parseSources} / {@code Scope.skipHints}); the stuck worker then
     * starves every other request of the engine.
     */
    @Test
    void findingsOnParenthesizedBrokenJoinFinish() {
        String sql = "UPDATE desk.agents a\n   SET a.open_load = (SELECT COUNT(*) FROM tmp_open_tickets o\n"
                + "   JOIN desk.assignments s " + "(".repeat(24) + "ON" + ")".repeat(7) + " s.ticket_id = o.id\n"
                + "  WHERE s.agent_id = a.id);\n";
        DialectFrontend f = DialectFrontends.get(Dialect.MARIADB);
        var script = ScriptIrBuilder.build(f, f.parse(SourceText.of(sql), ParseOptions.FALLBACK));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Findings.of(script));
    }
}
