package com.github.amber.dialect;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.sqlite.grammar.SQLiteParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ANTLR's static DFA cache is dropped when it grows past {@link AntlrFrontend#maxDfaStates}; parsing goes on
 * and still succeeds. (Found by the robustness pass: after the corpus the caches held about 620 MB, which
 * with one 100,000-line analysis exceeded a 2 GB heap.)
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class DfaCacheLimitTest {

    @Test
    void cacheIsDroppedPastTheLimitAndParsingGoesOn() {
        int max = AntlrFrontend.maxDfaStates;
        int every = AntlrFrontend.dfaCheckEvery;
        AntlrFrontend.maxDfaStates = 50;
        AntlrFrontend.dfaCheckEvery = 1;
        try {
            StringBuilder sql = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sql.append("SELECT a").append(i).append(", COUNT(*) FROM t").append(i)
                        .append(" WHERE b IN (SELECT c FROM u) GROUP BY a").append(i).append(";\n");
            }
            ScriptParse p = DialectFrontends.get(Dialect.SQLITE).parse(SourceText.of(sql.toString()));
            assertTrue(p.success(), p.diagnostics()::toString);
            long states = AntlrFrontend.dfaStates(new SQLiteParser(null).getInterpreter().decisionToDFA);
            assertTrue(states < 2_000, "DFA states after the limit: " + states);
        } finally {
            AntlrFrontend.maxDfaStates = max;
            AntlrFrontend.dfaCheckEvery = every;
        }
    }
}
