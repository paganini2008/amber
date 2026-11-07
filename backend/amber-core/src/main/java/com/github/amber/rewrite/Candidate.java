package com.github.amber.rewrite;

import java.util.List;

/**
 * A rewrite proposed by a rule, before verification.
 *
 * @param expectedDifferences prefixes of structural-check differences this rule causes by design (for
 *                            example removing ORDER BY changes "Ordering and row limits"); any other
 *                            difference on a rewrite declared equivalent drops it
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
record Candidate(String rule, String title, String explanation, List<Edit> edits, Safety safety, String safetyNote,
                 List<String> expectedDifferences) {

    Candidate(String rule, String title, String explanation, List<Edit> edits, Safety safety, String safetyNote) {
        this(rule, title, explanation, edits, safety, safetyNote, List.of());
    }

    Candidate {
        edits = edits.stream().sorted(java.util.Comparator.comparingInt(Edit::start)).toList();
        expectedDifferences = List.copyOf(expectedDifferences);
    }

    int firstOffset() {
        return edits.getFirst().start();
    }
}
