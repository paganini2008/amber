package com.github.amber.dialect.split;

import com.github.amber.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class WholeScriptSplitterTest {

    @Test
    void trimsAndReturnsOneUnit() {
        SourceText src = SourceText.of("\n  select 1;\n select 2;  \n");
        List<StatementUnit> units = WholeScriptSplitter.INSTANCE.split(src);
        assertEquals(1, units.size());
        assertEquals("select 1;\n select 2;", src.slice(units.getFirst().range()));
        assertEquals(List.of(), WholeScriptSplitter.INSTANCE.split(SourceText.of("  \n ")));
    }
}
