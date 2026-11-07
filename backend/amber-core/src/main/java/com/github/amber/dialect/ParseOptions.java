package com.github.amber.dialect;

/**
 * Options for parsing a script.
 *
 * @param allowFallback when false (the default), any syntax error fails the whole script. When true,
 *                      statements that fail to parse are kept as {@link UnitStatus#DEGRADED} and the
 *                      rest of the script is still analyzed.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record ParseOptions(boolean allowFallback) {

    public static final ParseOptions STRICT = new ParseOptions(false);
    public static final ParseOptions FALLBACK = new ParseOptions(true);
}
