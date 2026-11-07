package com.github.amber.rewrite;

import com.github.amber.ai.AiCheck;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.ir.ScriptIrBuilder;

import java.util.List;
import java.util.Map;

/**
 * Applies a candidate's edits, parses the result strictly and compares it structurally with the original
 * statement. A statement that only parses inside its script (a PL/SQL or T-SQL body statement that needs
 * its declarations) is checked within the whole rewritten script instead, which must parse without new
 * diagnostics.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Verifier {

    /** A verified rewrite: the statement text after the edits and the structural check's verdict. */
    record Outcome(String rewrittenStatement, String label, List<String> differences) {
    }

    private final Dialect dialect;
    private final DialectFrontend frontend;
    private final String text;
    private final Catalog scriptCatalog;
    private final Catalog base;
    private final int originalDiagnostics;

    Verifier(Dialect dialect, DialectFrontend frontend, String text, Catalog scriptCatalog, Catalog base,
             int originalDiagnostics) {
        this.dialect = dialect;
        this.frontend = frontend;
        this.text = text;
        this.scriptCatalog = scriptCatalog;
        this.base = base;
        this.originalDiagnostics = originalDiagnostics;
    }

    /** The outcome, or null when the rewrite must be dropped. */
    Outcome verify(RewriteContext ctx, Candidate c) {
        int start = ctx.statement().range().start().offset();
        String original = text.substring(start, ctx.statement().range().end().offset());
        String rewritten;
        try {
            rewritten = RewriteSuggester.apply(original, c.edits(), start);
        } catch (IllegalArgumentException e) {
            return null;
        }
        AiCheck.Result r = AiCheck.check(dialect, original, rewritten, Map.of(), scriptCatalog);
        if (r.label() == AiCheck.Label.INVALID_SQL) {
            if (parsesAlone(original)) {
                return null;                        // the edits broke a statement that parses on its own
            }
            String plainOriginal = standalone(original);
            if (plainOriginal != null && parsesAlone(plainOriginal)) {
                r = AiCheck.check(dialect, plainOriginal, standalone(rewritten), Map.of(), scriptCatalog);
            } else {
                r = inScript(c);
            }
            if (r == null || r.label() == AiCheck.Label.INVALID_SQL) {
                return null;
            }
        }
        if (r.label() == AiCheck.Label.STRUCTURE_CHANGED && c.safety() != Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES
                && !r.differences().stream().allMatch(d -> c.expectedDifferences().stream().anyMatch(d::startsWith))) {
            return null;                            // declared equivalent, but the structure moved: do not offer it
        }
        return new Outcome(rewritten, r.label().name(), r.differences());
    }

    private boolean parsesAlone(String statement) {
        String s = statement.strip();
        String terminated = s.endsWith(";") || s.endsWith("/") ? s : s + ";";
        return frontend.parse(SourceText.of(terminated), ParseOptions.STRICT).status() == ScriptStatus.SUCCESS;
    }

    /**
     * A PL/pgSQL body statement in plain SQL form: {@code PERFORM} becomes {@code SELECT} and the
     * {@code INTO [STRICT] targets} of a query is removed; null when that does not apply. Body statements are
     * only checked loosely within their script, so they are checked in this form instead.
     */
    String standalone(String statement) {
        if (dialect != Dialect.POSTGRESQL) {
            return null;
        }
        Sql sql = new Sql(dialect, statement, 0, statement.length());
        if (sql.size() == 0 || !(sql.word(0, "SELECT") || sql.word(0, "PERFORM") || sql.word(0, "WITH"))) {
            return null;
        }
        StringBuilder sb = new StringBuilder(statement);
        for (int i = sql.size() - 1; i >= 0; i--) {
            if (sql.depth[i] == 0 && sql.word(i, "INTO")) {
                int end = i + 1;
                while (end < sql.size() && !(sql.depth[end] == 0 && sql.wordIn(end, java.util.Set.of("FROM", "WHERE",
                        "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "UNION", "EXCEPT", "INTERSECT", "FOR"))
                        || sql.sym(end, ';'))) {
                    end++;
                }
                int to = end < sql.size() ? sql.tok(end).start() : statement.length();
                sb.replace(sql.tok(i).start(), to, "");
            }
        }
        if (sql.word(0, "PERFORM")) {
            sb.replace(sql.tok(0).start(), sql.tok(0).end(), "SELECT");
        }
        return sb.toString();
    }

    /** Checks the rewrite inside the whole script; null when the script no longer parses cleanly. */
    private AiCheck.Result inScript(Candidate c) {
        String script = RewriteSuggester.apply(text, c.edits());
        ScriptParse p = frontend.parse(SourceText.of(script), ParseOptions.STRICT);
        if (p.status() != ScriptStatus.SUCCESS) {
            return null;
        }
        Script s = ScriptIrBuilder.build(frontend, p, base.copy());
        if (p.diagnostics().size() + s.diagnostics().size() > originalDiagnostics) {
            return null;
        }
        AiCheck.Result r = AiCheck.check(dialect, text, script, Map.of(), base);
        return r.label() == AiCheck.Label.INVALID_SQL ? null : r;
    }
}
