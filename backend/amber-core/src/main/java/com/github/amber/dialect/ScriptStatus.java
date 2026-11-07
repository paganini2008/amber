package com.github.amber.dialect;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum ScriptStatus {
    /** Every SQL unit parsed. */
    SUCCESS,
    /** At least one unit failed in strict mode; the analysis must stop and show the errors. */
    FAILED,
    /** Fallback mode: at least one unit is degraded, the others parsed. */
    PARTIAL
}
