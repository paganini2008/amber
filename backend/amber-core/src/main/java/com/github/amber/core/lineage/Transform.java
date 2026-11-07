package com.github.amber.core.lineage;

/**
 * How a value is derived along a direct lineage edge.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public enum Transform {
    /** The value is copied unchanged ({@code SELECT a}). */
    PASS_THROUGH,
    /** Computed by an expression ({@code a + b}, {@code substr(a, 1, 3)}). */
    EXPRESSION,
    /** An aggregate ({@code sum(a)}, {@code count(*)}, {@code listagg}). */
    AGGREGATE,
    /** A window function ({@code rank() over (…)}). */
    WINDOW,
    /** A {@code CASE} expression. */
    CASE,
    /** A type conversion ({@code cast}, {@code to_char}, {@code convert}). */
    CAST,
    /** No source column: a literal or a function without column arguments. */
    CONSTANT
}
