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
 * @param suggested  the optimized SQL for this finding when it can be built safely, or null
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record Finding(String rule, Severity severity, String title, String message, String suggestion,
                      Statement statement, TextRange range, Confidence confidence, SuggestedSql suggested) {

    public enum Severity {
        HIGH, MEDIUM, LOW, INFO
    }

    /** What the suggested SQL is. */
    public enum SqlKind {
        /** The whole statement (or loop) rewritten; replaces the original text. */
        REWRITE_OF_STATEMENT,
        /** A statement to add, e.g. {@code CREATE INDEX}; the original stays. */
        ADDITIONAL_DDL
    }

    /**
     * Optimized SQL that comes with a finding, like an AI suggestion. It always parses strictly in the
     * script's dialect; a rewrite was also compared structurally with the original.
     *
     * @param sql      the SQL text
     * @param kind     rewrite of the statement or additional DDL
     * @param verified {@code STRUCTURE_PRESERVED} / {@code STRUCTURE_CHANGED} (structural check of a rewrite)
     *                 or {@code NOT_CHECKED} (additional DDL: parsed only)
     * @param note     what to check before using it, in a sentence
     * @param rewrite  id of the {@code rewrite.suggest} quick fix it comes from, or null
     */
    public record SuggestedSql(String sql, SqlKind kind, String verified, String note, String rewrite) {
    }

    public Finding(String rule, Severity severity, String title, String message, String suggestion,
                   Statement statement, TextRange range, Confidence confidence) {
        this(rule, severity, title, message, suggestion, statement, range, confidence, null);
    }

    public Finding withSeverity(Severity s) {
        return new Finding(rule, s, title, message, suggestion, statement, range, confidence, suggested);
    }

    public Finding withSuggested(SuggestedSql s) {
        return new Finding(rule, severity, title, message, suggestion, statement, range, confidence, s);
    }
}
