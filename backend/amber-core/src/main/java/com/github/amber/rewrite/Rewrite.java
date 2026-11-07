package com.github.amber.rewrite;

import java.util.List;

/**
 * A rule-based rewrite of one statement, like an IDE quick fix. It is a suggestion: Amber never applies
 * it to the user's file. Every rewrite returned has been applied, parsed strictly and compared
 * structurally with the original ({@code check}).
 *
 * @param id                 stable within one response, e.g. {@code R3}
 * @param rule               the finding it fixes, e.g. {@code AMB-S004}
 * @param title              short name of the change
 * @param explanation        what changes and why
 * @param statement          statement id (step index, as in {@code analyze})
 * @param edits              minimal, non-overlapping edits on the original text, in source order
 * @param rewrittenStatement the full statement text with the edits applied
 * @param safety             how safe the rewrite is
 * @param safetyNote         what the safety depends on, in plain words
 * @param check              label of the structural check: {@code STRUCTURE_PRESERVED} or
 *                           {@code STRUCTURE_CHANGED} (rewrites that do not parse are never returned)
 * @param checkDifferences   what the structural check reported as different; each one is expected for
 *                           this rule and explained in {@code safetyNote}
 * @param conflictsWith      id of an earlier rewrite of the same statement whose edits overlap this one,
 *                           or {@code null}; apply one or the other, then ask again
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record Rewrite(String id, String rule, String title, String explanation, int statement, List<Edit> edits,
                      String rewrittenStatement, Safety safety, String safetyNote, String check,
                      List<String> checkDifferences, String conflictsWith) {

    public Rewrite {
        edits = List.copyOf(edits);
        checkDifferences = checkDifferences == null ? List.of() : List.copyOf(checkDifferences);
    }
}
