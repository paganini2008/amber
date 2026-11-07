package com.github.amber.core.ir;

/**
 * How much of a statement or reference Amber could establish.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum Confidence {
    /** Parsed, and the object is known from metadata or from the script's own DDL. */
    CERTAIN,
    /** Parsed, but some facts are inferred (for example the object's kind without metadata). */
    INFERRED,
    /** Fallback mode: the statement did not parse and was only scanned at token level. */
    PARTIAL,
    /** Dynamic SQL whose text is not a constant. */
    UNRESOLVED
}
