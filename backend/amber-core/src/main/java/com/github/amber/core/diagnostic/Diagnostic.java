package com.github.amber.core.diagnostic;

import com.github.amber.core.source.TextRange;

import java.util.List;
import java.util.Objects;

/**
 * A problem found while reading a script, such as a syntax error, always tied to a source range.
 *
 * @param code      stable machine-readable code, e.g. {@code "syntax"} or {@code "lexer"}
 * @param offending the text of the offending token, or {@code null} at end of input
 * @param expected  what the parser expected at that point; may be empty
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record Diagnostic(Severity severity, String code, String message, TextRange range,
                         String offending, List<String> expected) {

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(range, "range");
        expected = expected == null ? List.of() : List.copyOf(expected);
    }

    public static Diagnostic error(String code, String message, TextRange range, String offending, List<String> expected) {
        return new Diagnostic(Severity.ERROR, code, message, range, offending, expected);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
