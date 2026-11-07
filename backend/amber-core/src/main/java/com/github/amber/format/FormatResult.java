package com.github.amber.format;

import com.github.amber.core.source.TextRange;

import java.util.List;

/**
 * Output of the {@link Formatter}.
 *
 * @param text     the whole script after formatting
 * @param changed  true when {@code text} differs from the input
 * @param warnings human-readable notes, one per statement that was kept as written because it could not
 *                 be formatted safely
 * @param skipped  ranges in the <em>input</em> of the statements that were kept as written for the
 *                 reasons given in {@code warnings}
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record FormatResult(String text, boolean changed, List<String> warnings, List<TextRange> skipped) {

    public FormatResult {
        warnings = List.copyOf(warnings);
        skipped = List.copyOf(skipped);
    }
}
