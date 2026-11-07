package com.github.amber.dialect;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.source.SourceText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class ParseModesTest {

    private static final String SCRIPT = """
            define cutoff=add_months(trunc(sysdate), -1)
            create table t1 as select * from src where created_at < &cutoff;
            select from where;
            insert into t2 select a, b from t1;
            """;

    private final DialectFrontend oracle = DialectFrontends.get(Dialect.ORACLE);

    @Test
    void strictModeFailsAndReportsTheErrorAtItsScriptLine() {
        ScriptParse parse = oracle.parse(new SourceText("etl.sql", SCRIPT), ParseOptions.STRICT);

        assertEquals(ScriptStatus.FAILED, parse.status());
        assertEquals(4, parse.units().size());
        assertEquals(UnitStatus.SKIPPED, parse.units().get(0).status());
        assertEquals(UnitStatus.PARSED, parse.units().get(1).status());
        assertEquals(UnitStatus.FAILED, parse.units().get(2).status());
        assertEquals(UnitStatus.PARSED, parse.units().get(3).status());

        Diagnostic first = parse.diagnostics().getFirst();
        assertEquals(3, first.range().start().line());
        assertTrue(first.range().start().column() > 1);
    }

    @Test
    void fallbackModeDegradesOnlyTheBrokenStatement() {
        ScriptParse parse = oracle.parse(new SourceText("etl.sql", SCRIPT), ParseOptions.FALLBACK);

        assertEquals(ScriptStatus.PARTIAL, parse.status());
        assertEquals(1, parse.count(UnitStatus.DEGRADED));
        assertEquals(2, parse.count(UnitStatus.PARSED));
    }
}
