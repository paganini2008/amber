package com.github.amber.core.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.source.SourceText;

import java.util.ArrayList;
import java.util.List;

/**
 * The IR of a whole script: top-level statements in source order, each possibly with children.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record Script(Dialect dialect, SourceText source, List<Statement> statements, List<Diagnostic> diagnostics) {

    public Script {
        statements = List.copyOf(statements);
        diagnostics = List.copyOf(diagnostics);
    }

    /** All statements depth-first in source order, containers before their children. */
    public List<Statement> flatten() {
        List<Statement> all = new ArrayList<>();
        statements.forEach(s -> s.forEach(all::add));
        return all;
    }
}
