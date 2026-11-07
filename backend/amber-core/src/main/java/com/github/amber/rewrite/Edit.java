package com.github.amber.rewrite;

import com.github.amber.core.source.TextRange;

/**
 * One text replacement in the original script: {@code range} (offsets into the analyzed text) is
 * replaced by {@code replacement}. An empty range is an insertion, an empty replacement a deletion.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record Edit(TextRange range, String replacement) {

    public Edit {
        if (range == null || replacement == null) {
            throw new IllegalArgumentException("An edit needs a range and a replacement");
        }
    }

    public int start() {
        return range.start().offset();
    }

    public int end() {
        return range.end().offset();
    }

    /**
     * True when the two edits cannot both be applied unambiguously: their ranges overlap, or they start
     * at the same offset (two insertions at one point have no defined order).
     */
    public boolean overlaps(Edit other) {
        return start() == other.start() || (start() < other.end() && other.start() < end());
    }
}
