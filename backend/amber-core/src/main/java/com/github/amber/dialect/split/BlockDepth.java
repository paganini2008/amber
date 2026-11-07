package com.github.amber.dialect.split;

import java.util.Set;

/**
 * Tracks {@code BEGIN … END} nesting in compound statements so that {@code ;} inside a procedure,
 * trigger or function body does not end the statement. {@code CASE} opens a level (closed by
 * {@code END} or {@code END CASE}); {@code END IF}, {@code END LOOP}, {@code END WHILE},
 * {@code END FOR} and {@code END REPEAT} close constructs that never opened a level.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class BlockDepth {

    private static final Set<String> NON_BLOCK_ENDS = Set.of("IF", "LOOP", "WHILE", "FOR", "REPEAT");

    private int depth;

    /** @param next the token after {@code token}, or {@code null} */
    public void accept(ScanToken token, ScanToken next) {
        if (token.isWord("BEGIN") || token.isWord("CASE")) {
            depth++;
        } else if (token.isWord("END") && depth > 0 && (next == null || !next.isWordIn(NON_BLOCK_ENDS))) {
            depth--;
        }
    }

    public boolean inBlock() {
        return depth > 0;
    }
}
