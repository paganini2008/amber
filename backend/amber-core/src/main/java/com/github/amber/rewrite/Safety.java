package com.github.amber.rewrite;

/**
 * How safe a rule-based rewrite is. Only {@link #EQUIVALENT} is unconditional; the other levels say
 * what the equivalence depends on, and the rewrite's {@code safetyNote} explains it.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum Safety {

    /** Same result for every database state: the rewrite follows from the SQL alone. */
    EQUIVALENT,

    /**
     * Same result given the metadata Amber knows (NOT NULL columns, column lists, keys) or a property
     * proven from the statement's literals; it stops being equivalent if that metadata changes.
     */
    EQUIVALENT_WITH_METADATA,

    /** Usually what the author meant, but the result can differ in edge cases the note describes. */
    CHANGES_BEHAVIOR_IN_EDGE_CASES
}
