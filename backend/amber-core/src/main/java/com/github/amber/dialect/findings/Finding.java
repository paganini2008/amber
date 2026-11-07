package com.github.amber.dialect.findings;

import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.source.TextRange;

/**
 * A risk or improvement hint about a statement. Findings are hints, never a performance score:
 * without metadata they are reported as possible ({@link Confidence#INFERRED}).
 *
 * @param rule       stable id, e.g. {@code AMB-S012}; suppress with {@code -- amber:ignore AMB-S012}
 * @param title      short name of the problem
 * @param message    what was found, in this statement
 * @param suggestion how to fix it
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record Finding(String rule, Severity severity, String title, String message, String suggestion,
                      Statement statement, TextRange range, Confidence confidence) {

    public enum Severity {
        HIGH, MEDIUM, LOW, INFO
    }
}
