package com.github.amber.server;

import com.github.amber.ai.AiCheck;
import com.github.amber.ai.AiContextBuilder;
import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.server.Dtos.DiagnosticDto;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The {@code ai.*} methods. The engine never talks to a model: it builds the prompt from its own
 * analysis ({@code ai.buildContext}) and checks what the model proposed ({@code ai.check}); the app
 * sends the prompt to the provider the user configured.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class AiService {

    static final int DEFAULT_TOKEN_BUDGET = 6000;

    /**
     * @param statements  statement ids in scope; empty or null means the whole script
     * @param tokenBudget approximate token limit of the user message
     * @param mask        replace object names, columns and literals with aliases (default true)
     */
    record ContextRequest(String dialect, String text, String path, String encoding, String name,
                          Boolean allowFallback, List<String> metadataPacks, List<String> ddlFiles,
                          List<TableMeta> metadata, List<Integer> statements, Integer tokenBudget, Boolean mask,
                          String notes) {
    }

    record ContextResult(String system, String user, int tokenEstimate, List<Integer> statements,
                         List<String> trimmed, Map<String, String> mask) {
    }

    /**
     * @param original  the statement(s) the suggestion is about, as in the script
     * @param rewritten the SQL the model proposed (may still use the aliases of {@code mask})
     * @param mask      alias → real name map returned by {@code ai.buildContext}
     */
    record CheckRequest(String dialect, String original, String rewritten, Map<String, String> mask,
                        List<String> metadataPacks, List<String> ddlFiles, List<TableMeta> metadata) {
    }

    record CheckResult(String label, String sql, List<String> differences, List<DiagnosticDto> diagnostics) {
    }

    /** A follow-up message the developer writes after the first answer, masked with the context's mapping. */
    record MaskTextRequest(String text, Map<String, String> mask) {
    }

    record MaskTextResult(String text) {
    }

    MaskTextResult maskText(MaskTextRequest req) {
        if (req == null || req.text() == null) {
            throw new IllegalArgumentException("'text' is required");
        }
        return new MaskTextResult(com.github.amber.ai.Masking.maskText(req.text(), req.mask()));
    }

    private final AnalysisService analysis;

    AiService(AnalysisService analysis) {
        this.analysis = analysis;
    }

    ContextResult buildContext(ContextRequest req) throws IOException {
        var a = analysis.run(new AnalysisService.Request(req.dialect(), req.text(), req.path(), req.encoding(),
                req.name(), req.allowFallback(), req.metadataPacks(), req.ddlFiles(), req.metadata()));
        if (a.script() == null) {
            throw new IllegalArgumentException("The script does not parse; fix the syntax errors or allow fallback");
        }
        List<Statement> steps = Lifecycles.steps(a.script());
        List<Integer> ids = req.statements() == null ? List.of() : req.statements();
        for (int id : ids) {
            if (id < 0 || id >= steps.size()) {
                throw new IllegalArgumentException("No statement " + id);
            }
        }
        List<Finding> findings;
        try {
            findings = Findings.of(a.script(), a.catalog());
        } catch (RuntimeException e) {
            findings = List.of();
        }
        int budget = req.tokenBudget() == null || req.tokenBudget() <= 0 ? DEFAULT_TOKEN_BUDGET : req.tokenBudget();
        var c = AiContextBuilder.build(a.script(), steps, ids, findings, a.catalog(), budget,
                !Boolean.FALSE.equals(req.mask()), req.notes());
        return new ContextResult(c.system(), c.user(), c.tokenEstimate(), c.statements(), c.trimmed(), c.mask());
    }

    CheckResult check(CheckRequest req) throws IOException {
        if (req.dialect() == null || req.original() == null || req.rewritten() == null) {
            throw new IllegalArgumentException("'dialect', 'original' and 'rewritten' are required");
        }
        var catalog = MetadataService.catalog(req.dialect(), req.metadataPacks(), req.ddlFiles(), req.metadata());
        var r = AiCheck.check(Dialect.fromId(req.dialect()), req.original(), req.rewritten(),
                req.mask() == null ? Map.of() : req.mask(), catalog);
        return new CheckResult(r.label().name(), r.sql(), r.differences(),
                r.diagnostics().stream().map(DiagnosticDto::of).toList());
    }
}
