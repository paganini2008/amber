package com.github.amber.core.source;

/**
 * A half-open range {@code [start, end)} in a source text.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record TextRange(TextPosition start, TextPosition end) {

    public TextRange {
        if (end.offset() < start.offset()) {
            throw new IllegalArgumentException("Range end before start: " + start + " > " + end);
        }
    }

    public int length() {
        return end.offset() - start.offset();
    }
}
