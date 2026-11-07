package com.github.amber.dialect;

import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.StatementUnit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SplitAssertions {

    private SplitAssertions() {
    }

    /** Asserts unit kinds and texts; expected entries look like {@code "SQL: select 1;"}. */
    public static void assertSplit(ScriptSplitter splitter, String script, String... expected) {
        SourceText src = SourceText.of(script);
        List<StatementUnit> units = splitter.split(src);
        List<String> actual = units.stream()
                .map(u -> u.kind() + ": " + src.slice(u.range()).replace("\r\n", "\n"))
                .toList();
        assertEquals(List.of(expected), actual);
    }
}
