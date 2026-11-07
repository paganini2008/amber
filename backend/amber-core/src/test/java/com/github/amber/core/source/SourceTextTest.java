package com.github.amber.core.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class SourceTextTest {

    @Test
    void mapsOffsetsToLinesAndColumnsForAllLineEndings() {
        SourceText src = SourceText.of("ab\ncd\r\nef\rgh");
        assertEquals(4, src.lineCount());
        assertEquals(new TextPosition(0, 1, 1), src.positionAt(0));
        assertEquals(new TextPosition(4, 2, 2), src.positionAt(4));
        assertEquals(new TextPosition(7, 3, 1), src.positionAt(7));
        assertEquals(new TextPosition(10, 4, 1), src.positionAt(10));
        assertEquals(new TextPosition(12, 4, 3), src.positionAt(12));
    }

    @Test
    void slicesRanges() {
        SourceText src = SourceText.of("select 1;\nselect 2;");
        assertEquals("select 2;", src.slice(src.range(10, 19)));
    }
}
