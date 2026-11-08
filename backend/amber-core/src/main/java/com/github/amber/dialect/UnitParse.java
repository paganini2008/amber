package com.github.amber.dialect;

import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.dialect.split.StatementUnit;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.PredictionMode;

import java.time.Duration;
import java.util.List;

/**
 * Parse result of one {@link StatementUnit}. Token offsets inside {@code tree} are relative to the
 * unit; diagnostics are already mapped to the whole script.
 *
 * @param mode {@code SLL} when the fast path succeeded, {@code LL} when the full pass was needed,
 *             {@code null} for skipped units
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record UnitParse(StatementUnit unit, UnitStatus status, ParserRuleContext tree, PredictionMode mode,
                        List<Diagnostic> diagnostics, Duration elapsed) {

    public UnitParse {
        diagnostics = List.copyOf(diagnostics);
    }

    public static UnitParse skipped(StatementUnit unit) {
        return new UnitParse(unit, UnitStatus.SKIPPED, null, null, List.of(), Duration.ZERO);
    }

    UnitParse withStatus(UnitStatus newStatus) {
        return new UnitParse(unit, newStatus, tree, mode, diagnostics, elapsed);
    }

    /** The same result without its parse tree, for callers that keep it after the IR is built. */
    public UnitParse withoutTree() {
        return tree == null ? this : new UnitParse(unit, status, null, mode, diagnostics, elapsed);
    }
}
