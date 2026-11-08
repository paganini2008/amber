package com.github.amber.dialect.findings.rules;

import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.dialect.findings.Finding;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The offline suggestion rules AMB-P001 … AMB-P009 (performance and maintenance hints), run by
 * {@link com.github.amber.dialect.findings.Findings} after its own rules. Every rule is deterministic, reads
 * only the script and the imported metadata, and prefers missing a case to reporting a false one. A rule
 * that cannot read an unusual statement reports nothing for it (findings are hints and never fail the
 * analysis); {@code strict} mode rethrows instead, for tests.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class ExtraRules {

    private static final Set<StatementKind> QUERIES = Set.of(StatementKind.SELECT, StatementKind.INSERT,
            StatementKind.UPDATE, StatementKind.DELETE, StatementKind.MERGE, StatementKind.SELECT_INTO,
            StatementKind.CREATE_TABLE_AS, StatementKind.CREATE_VIEW, StatementKind.CREATE_MATERIALIZED_VIEW,
            StatementKind.CURSOR);

    private ExtraRules() {
    }

    /** Findings of the extra rules for a script; {@code catalog} is the imported metadata (may be empty). */
    public static List<Finding> of(Script script, Catalog catalog) {
        return of(script, catalog, false);
    }

    /** As {@link #of(Script, Catalog)}; with {@code strict} a rule failure is thrown instead of skipped. */
    public static List<Finding> of(Script script, Catalog catalog, boolean strict) {
        RuleContext ctx = new RuleContext(script, catalog);
        Set<Statement> inRoutine = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Statement st : script.flatten()) {
            if (st.kind().category() == StatementKind.Category.PROGRAM) {
                st.forEach(c -> {
                    if (c != st) {
                        inRoutine.add(c);
                    }
                });
            }
        }
        IndexRules index = new IndexRules(ctx);
        ConversionRule conversion = guard(() -> new ConversionRule(ctx), strict);
        QueryShapeRules shapes = new QueryShapeRules(ctx, inRoutine);
        for (Statement st : script.flatten()) {
            if (!QUERIES.contains(st.kind()) || !RuleContext.readable(st)) {
                continue;
            }
            Toks k = ctx.toks(st);
            if (k.size() == 0) {
                continue;
            }
            run(() -> index.statement(st, k), strict);
            if (conversion != null) {
                run(() -> conversion.statement(st, k), strict);
            }
            run(() -> shapes.statement(st, k), strict);
        }
        run(() -> new ProceduralRules(ctx, strict).run(), strict);
        run(index::finish, strict);
        return ctx.out;
    }

    private static void run(Runnable rule, boolean strict) {
        try {
            rule.run();
        } catch (RuntimeException e) {
            if (strict) {
                throw e;
            }
            // a rule that cannot read an unusual statement reports nothing for it
        }
    }

    private static <T> T guard(java.util.function.Supplier<T> s, boolean strict) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            if (strict) {
                throw e;
            }
            return null;
        }
    }
}
