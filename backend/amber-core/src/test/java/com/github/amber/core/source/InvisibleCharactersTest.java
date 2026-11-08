package com.github.amber.core.source;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invisible characters from copy and paste must not break parsing, and must be reported.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class InvisibleCharactersTest {

    private static final String SCRIPT = "select a from t;\n﻿select b​ from u;\n\u000cselect c　from v;\n\u001a";

    @Test
    void keepsLengthAndReportsEachKind() {
        SourceText src = SourceText.of(SCRIPT);
        InvisibleCharacters.Result r = InvisibleCharacters.normalize(src);
        assertEquals(SCRIPT.length(), r.text().length());
        assertEquals("select a from t;\n select b  from u;\n select c from v;\n ", r.text());
        assertEquals(6, r.warnings().size());
        Diagnostic nbsp = r.warnings().get(0);
        assertEquals(1, nbsp.range().start().line());
        assertEquals(7, nbsp.range().start().column());
        assertTrue(nbsp.message().startsWith("1 no-break space treated as whitespace"));
        assertEquals(0, InvisibleCharacters.normalize(SourceText.of("﻿select 1;\tselect 2;\r\n")).warnings().size());
    }

    @Test
    void everyDialectParsesScriptsWithInvisibleCharacters() {
        for (Dialect d : Dialect.values()) {
            ScriptParse parse = DialectFrontends.get(d).parse(SourceText.of(SCRIPT));
            assertTrue(parse.success(), () -> d + ": " + parse.diagnostics());
            assertEquals(6, parse.diagnostics().size(), d::toString);
        }
    }
}
