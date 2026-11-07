package com.github.amber.core.source;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable SQL input with a name (usually a file name) and a line index for mapping char
 * offsets to line and column.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SourceText {

    private final String name;
    private final String text;
    private final int[] lineStarts;

    public SourceText(String name, String text) {
        this.name = Objects.requireNonNull(name, "name");
        this.text = Objects.requireNonNull(text, "text");
        this.lineStarts = indexLines(text);
    }

    public static SourceText of(String text) {
        return new SourceText("<input>", text);
    }

    public String name() {
        return name;
    }

    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    public int lineCount() {
        return lineStarts.length;
    }

    /** Maps a 0-based char offset to a position with 1-based line and column. */
    public TextPosition positionAt(int offset) {
        if (offset < 0 || offset > text.length()) {
            throw new IndexOutOfBoundsException("Offset " + offset + " outside [0, " + text.length() + "]");
        }
        int idx = Arrays.binarySearch(lineStarts, offset);
        int line = idx >= 0 ? idx : -idx - 2;
        return new TextPosition(offset, line + 1, offset - lineStarts[line] + 1);
    }

    public TextRange range(int startOffset, int endOffset) {
        return new TextRange(positionAt(startOffset), positionAt(endOffset));
    }

    public String slice(TextRange range) {
        return text.substring(range.start().offset(), range.end().offset());
    }

    /** Line starts for \n, \r\n and lone \r line endings. */
    private static int[] indexLines(String text) {
        int[] starts = new int[16];
        int count = 0;
        starts[count++] = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean lineBreak = c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'));
            if (lineBreak) {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }
        return Arrays.copyOf(starts, count);
    }

    @Override
    public String toString() {
        return "SourceText[" + name + ", " + text.length() + " chars, " + lineStarts.length + " lines]";
    }
}
