package com.github.amber.dialect;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum UnitStatus {
    /** Parsed without errors. */
    PARSED,
    /** Syntax errors in strict mode. */
    FAILED,
    /** Syntax errors, kept because fallback is allowed; no structure or lineage is derived from it. */
    DEGRADED,
    /** Not SQL for the grammar: client commands and inline data. */
    SKIPPED
}
