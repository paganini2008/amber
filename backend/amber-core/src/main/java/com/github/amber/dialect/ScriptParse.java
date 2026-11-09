package com.github.amber.dialect;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.source.SourceText;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse result of a whole script: one {@link UnitParse} per unit, in source order.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record ScriptParse(Dialect dialect, SourceText source, ParseOptions options, List<UnitParse> units,
                          Duration elapsed, List<Diagnostic> preprocessing) {

    public ScriptParse {
        units = List.copyOf(units);
        preprocessing = List.copyOf(preprocessing);
    }

    public ScriptStatus status() {
        boolean failed = units.stream().anyMatch(u -> u.status() == UnitStatus.FAILED);
        if (failed) {
            return ScriptStatus.FAILED;
        }
        boolean degraded = units.stream().anyMatch(u -> u.status() == UnitStatus.DEGRADED);
        return degraded ? ScriptStatus.PARTIAL : ScriptStatus.SUCCESS;
    }

    public boolean success() {
        return status() == ScriptStatus.SUCCESS;
    }

    /** Preprocessing warnings, then all diagnostics of all units in source order. */
    public List<Diagnostic> diagnostics() {
        List<Diagnostic> all = new ArrayList<>(preprocessing);
        units.forEach(u -> all.addAll(u.diagnostics()));
        return all;
    }

    /**
     * The same parse without the ANTLR trees: status, units and diagnostics only. Trees take most of the
     * memory of a parse (about 1 GB for a 100,000-line script), so a parse kept after the IR is built
     * should be kept like this.
     */
    public ScriptParse withoutTrees() {
        return new ScriptParse(dialect, source, options, units.stream().map(UnitParse::withoutTree).toList(), elapsed,
                preprocessing);
    }

    public long count(UnitStatus status) {
        return units.stream().filter(u -> u.status() == status).count();
    }
}
