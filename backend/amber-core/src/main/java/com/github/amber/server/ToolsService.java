package com.github.amber.server;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.refs.References;
import com.github.amber.diff.DiffResult;
import com.github.amber.diff.ScriptDiff;
import com.github.amber.docgen.HandoverDoc;
import com.github.amber.er.ErModel;
import com.github.amber.er.ErModelBuilder;
import com.github.amber.format.FormatOptions;
import com.github.amber.format.Formatter;
import com.github.amber.rewrite.Edit;
import com.github.amber.rewrite.Optimizer;
import com.github.amber.rewrite.RewriteSuggester;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maintenance tools on top of one analysis: {@code references} (find usages / go to definition) and
 * {@code doc.generate} (a handover document of the script).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class ToolsService {

    /** Give {@code object} (a table, view, temp table, CTE, procedure…) or {@code table} + {@code column}. */
    record ReferencesRequest(String dialect, String text, String path, String encoding, String name,
                             Boolean allowFallback, List<String> metadataPacks, List<String> ddlFiles,
                             List<TableMeta> metadata, String object, String table, String column) {
    }

    record OccurrenceDto(int statement, String access, Dtos.Range range, String line, String via) {
        static OccurrenceDto of(References.Occurrence o) {
            return o == null ? null : new OccurrenceDto(o.statement(), o.access(), Dtos.Range.of(o.range()), o.line(),
                    o.via());
        }
    }

    record ReferencesResult(String name, String kind, OccurrenceDto definition, List<OccurrenceDto> occurrences) {
    }

    /** {@code format}: {@code markdown} (default) or {@code html}; {@code save}: optional file path to write. */
    record DocRequest(String dialect, String text, String path, String encoding, String name, Boolean allowFallback,
                      List<String> metadataPacks, List<String> ddlFiles, List<TableMeta> metadata, String format,
                      String save, Dtos.RulesDto rules) {
    }

    record DocResult(String format, String text, String saved) {
    }

    /** One side of a diff: the script text or a file. */
    record DiffSide(String text, String path, String encoding, String name) {
    }

    record DiffRequest(String dialect, DiffSide before, DiffSide after, List<String> metadataPacks,
                       List<String> ddlFiles, List<TableMeta> metadata, Dtos.RulesDto rules) {
    }

    /** {@code statements}: step ids to look at; empty or absent means the whole script. */
    record RewriteRequest(String dialect, String text, String path, String encoding, String name, Boolean allowFallback,
                          List<String> metadataPacks, List<String> ddlFiles, List<TableMeta> metadata,
                          List<Integer> statements, Dtos.RulesDto rules) {
    }

    /** {@code rewrite.optimize}: apply every safe rewrite per statement (edge-case ones with includeEdgeCases). */
    record OptimizeRequest(String dialect, String text, String path, String encoding, String name,
                           Boolean allowFallback, List<String> metadataPacks, List<String> ddlFiles,
                           List<TableMeta> metadata, List<Integer> statements, Boolean includeEdgeCases,
                           Dtos.RulesDto rules) {
    }

    record AppliedDto(String id, String rule, String title, String safety) {
    }

    record SkippedDto(String id, String rule, String title, String reason) {
    }

    record CheckDto(String label, List<String> differences) {
    }

    record OptimizedStatementDto(int statement, String original, String optimized, List<AppliedDto> applied,
                                 List<SkippedDto> skipped, String safety, CheckDto check, List<String> additionalDdl) {
    }

    record OptimizeResult(List<OptimizedStatementDto> statements, String optimizedText) {
    }

    record EditDto(Dtos.Range range, String replacement) {
    }

    record RewriteDto(String id, String rule, String title, String explanation, int statement, List<EditDto> edits,
                      String rewrittenStatement, String safety, String safetyNote, String check,
                      List<String> checkDifferences, String conflictsWith) {
    }

    record RewriteResult(List<RewriteDto> rewrites) {
    }

    /** Applies edits (offsets into {@code text}) and returns the new script text; nothing is saved. */
    record ApplyRequest(String text, List<ApplyEdit> edits) {
    }

    record ApplyEdit(int start, int end, String replacement) {
    }

    record ApplyResult(String text) {
    }

    /** {@code start}/{@code end}: optional 0-based offsets; only the statements they overlap are formatted. */
    record FormatRequest(String dialect, String text, String path, String encoding, Integer indentSize,
                         String keywordCase, Integer maxLineWidth, String commaStyle, Integer start, Integer end) {
    }

    record FormatResultDto(String text, boolean changed, List<String> warnings, List<Dtos.Range> skipped) {
    }

    /**
     * {@code er.model}: the inferred entity-relationship model of the script. {@code includeDataFlow} (default
     * false) adds DATA_FLOW relationships; {@code includeNameMatch} (default true) adds NAME_MATCH ones.
     */
    record ErModelRequest(String dialect, String text, String path, String encoding, String name,
                          Boolean allowFallback, List<String> metadataPacks, List<String> ddlFiles,
                          List<TableMeta> metadata, Boolean includeDataFlow, Boolean includeNameMatch) {
    }

    private final AnalysisService analysis;

    ToolsService(AnalysisService analysis) {
        this.analysis = analysis;
    }

    ErModel erModel(ErModelRequest req) throws IOException {
        if (req == null || req.dialect() == null) {
            throw new IllegalArgumentException("'dialect' is required");
        }
        var frontend = DialectFrontends.get(Dialect.fromId(req.dialect()));
        var source = AnalysisService.source(new AnalysisService.Request(req.dialect(), req.text(), req.path(),
                req.encoding(), req.name(), req.allowFallback()));
        var parse = frontend.parse(source, Boolean.TRUE.equals(req.allowFallback())
                ? ParseOptions.FALLBACK : ParseOptions.STRICT);
        if (parse.status() == ScriptStatus.FAILED) {
            throw new IllegalArgumentException("The script does not parse; fix the syntax errors or allow fallback");
        }
        var catalog = MetadataService.catalog(req.dialect(), req.metadataPacks(), req.ddlFiles(), req.metadata());
        var options = new ErModelBuilder.Options(Boolean.TRUE.equals(req.includeDataFlow()),
                !Boolean.FALSE.equals(req.includeNameMatch()),
                ErModelBuilder.Options.DEFAULTS.maxOccurrences());
        return ErModelBuilder.build(frontend, parse, catalog, options);
    }

    ReferencesResult references(ReferencesRequest req) throws IOException {
        var a = analysis.run(new AnalysisService.Request(req.dialect(), req.text(), req.path(), req.encoding(),
                req.name(), req.allowFallback(), req.metadataPacks(), req.ddlFiles(), req.metadata()));
        if (a.script() == null) {
            throw new IllegalArgumentException("The script does not parse; fix the syntax errors or allow fallback");
        }
        References.Result r;
        if (req.object() != null && !req.object().isBlank()) {
            r = References.ofObject(a.script(), req.object());
        } else if (req.table() != null && req.column() != null) {
            r = References.ofColumn(a.script(), req.table(), req.column());
        } else {
            throw new IllegalArgumentException("Give 'object', or 'table' and 'column'");
        }
        return new ReferencesResult(r.name(), r.kind(), OccurrenceDto.of(r.definition()),
                r.occurrences().stream().map(OccurrenceDto::of).toList());
    }

    DiffResult diff(DiffRequest req) throws IOException {
        if (req == null || req.dialect() == null || req.before() == null || req.after() == null) {
            throw new IllegalArgumentException("'dialect', 'before' and 'after' are required");
        }
        var catalog = MetadataService.catalog(req.dialect(), req.metadataPacks(), req.ddlFiles(), req.metadata());
        return ScriptDiff.compare(Dialect.fromId(req.dialect()),
                side(req.dialect(), req.before(), "before.sql"), side(req.dialect(), req.after(), "after.sql"), catalog,
                Dtos.RulesDto.config(req.rules()));
    }

    private static SourceText side(String dialect, DiffSide s, String fallbackName)
            throws IOException {
        return AnalysisService.source(new AnalysisService.Request(dialect, s.text(), s.path(), s.encoding(),
                s.name() == null && s.path() == null ? fallbackName : s.name(), false));
    }

    RewriteResult rewrites(RewriteRequest req) throws IOException {
        if (req == null || req.dialect() == null) {
            throw new IllegalArgumentException("'dialect' is required");
        }
        var source = AnalysisService.source(new AnalysisService.Request(req.dialect(), req.text(), req.path(),
                req.encoding(), req.name(), req.allowFallback()));
        var catalog = MetadataService.catalog(req.dialect(), req.metadataPacks(), req.ddlFiles(), req.metadata());
        var rules = Dtos.RulesDto.config(req.rules());
        var list = RewriteSuggester.suggest(Dialect.fromId(req.dialect()),
                source.text(), catalog, req.statements(), Boolean.TRUE.equals(req.allowFallback()), rules::enabled);
        return new RewriteResult(list.stream().map(r -> new RewriteDto(r.id(), r.rule(), r.title(), r.explanation(),
                r.statement(), r.edits().stream().map(e -> new EditDto(Dtos.Range.of(e.range()), e.replacement())).toList(),
                r.rewrittenStatement(), r.safety().name(), r.safetyNote(), r.check(), r.checkDifferences(),
                r.conflictsWith())).toList());
    }

    OptimizeResult optimize(OptimizeRequest req) throws IOException {
        if (req == null || req.dialect() == null) {
            throw new IllegalArgumentException("'dialect' is required");
        }
        var rules = Dtos.RulesDto.config(req.rules());
        boolean fallback = Boolean.TRUE.equals(req.allowFallback());
        var a = analysis.run(new AnalysisService.Request(req.dialect(), req.text(), req.path(), req.encoding(),
                req.name(), req.allowFallback(), req.metadataPacks(), req.ddlFiles(), req.metadata()));
        Map<Integer, List<String>> ddl = new TreeMap<>();
        if (a.script() != null) {
            var steps = a.script().flatten();
            Map<Statement, Integer> ids = new IdentityHashMap<>();
            for (int i = 0; i < steps.size(); i++) {
                ids.put(steps.get(i), i);
            }
            try {
                for (var f : Findings.of(a.script(), a.catalog(), rules)) {
                    var x = f.suggested();
                    if (x != null && x.kind() == Finding.SqlKind.ADDITIONAL_DDL
                            && ids.containsKey(f.statement())) {
                        ddl.computeIfAbsent(ids.get(f.statement()), k -> new ArrayList<>()).add(x.sql());
                    }
                }
            } catch (RuntimeException e) {
                ddl.clear();                         // hints only
            }
        }
        var r = Optimizer.optimize(a.frontend().dialect(), a.parse().source().text(),
                a.catalog(), req.statements(), Boolean.TRUE.equals(req.includeEdgeCases()), rules::enabled, fallback, ddl);
        return new OptimizeResult(r.statements().stream().map(s -> new OptimizedStatementDto(s.statement(),
                s.original(), s.optimized(),
                s.applied().stream().map(x -> new AppliedDto(x.id(), x.rule(), x.title(), x.safety().name())).toList(),
                s.skipped().stream().map(x -> new SkippedDto(x.id(), x.rule(), x.title(), x.reason())).toList(),
                s.safety() == null ? null : s.safety().name(),
                s.check() == null ? null : new CheckDto(s.check().label(), s.check().differences()),
                s.additionalDdl())).toList(), r.optimizedText());
    }

    ApplyResult apply(ApplyRequest req) {
        if (req == null || req.text() == null || req.edits() == null) {
            throw new IllegalArgumentException("'text' and 'edits' are required");
        }
        var src = new SourceText("edit.sql", req.text());
        for (ApplyEdit e : req.edits()) {
            if (e == null || e.start() < 0 || e.end() < e.start() || e.end() > req.text().length()) {
                throw new IllegalArgumentException("Edit " + (e == null ? "null" : "[" + e.start() + ", " + e.end() + ")")
                        + " is outside the text (length " + req.text().length() + ")");
            }
        }
        var edits = req.edits().stream().map(e -> new Edit(src.range(e.start(), e.end()),
                e.replacement() == null ? "" : e.replacement())).toList();
        return new ApplyResult(RewriteSuggester.apply(req.text(), edits));
    }

    FormatResultDto format(FormatRequest req) throws IOException {
        if (req == null || req.dialect() == null) {
            throw new IllegalArgumentException("'dialect' is required");
        }
        var src = AnalysisService.source(new AnalysisService.Request(req.dialect(), req.text(), req.path(),
                req.encoding(), null, false));
        var o = FormatOptions.DEFAULTS;
        if (req.indentSize() != null) {
            o = o.withIndentSize(req.indentSize());
        }
        if (req.maxLineWidth() != null) {
            o = o.withMaxLineWidth(req.maxLineWidth());
        }
        if (req.keywordCase() != null) {
            o = o.withKeywordCase(FormatOptions.KeywordCase.valueOf(
                    req.keywordCase().toUpperCase(Locale.ROOT)));
        }
        if (req.commaStyle() != null) {
            o = o.withCommaStyle(FormatOptions.CommaStyle.valueOf(
                    req.commaStyle().toUpperCase(Locale.ROOT)));
        }
        var d = Dialect.fromId(req.dialect());
        var r = req.start() != null
                ? Formatter.formatRange(d, src.text(), req.start(),
                        req.end() == null ? req.start() : req.end(), o)
                : Formatter.format(d, src.text(), o);
        return new FormatResultDto(r.text(), r.changed(), r.warnings(), r.skipped().stream().map(Dtos.Range::of).toList());
    }

    DocResult document(DocRequest req) throws IOException {
        var a = analysis.run(new AnalysisService.Request(req.dialect(), req.text(), req.path(), req.encoding(),
                req.name(), req.allowFallback(), req.metadataPacks(), req.ddlFiles(), req.metadata()));
        if (a.script() == null) {
            throw new IllegalArgumentException("The script does not parse; fix the syntax errors or allow fallback");
        }
        var rules = Dtos.RulesDto.config(req.rules());
        List<Finding> findings;
        try {
            findings = Findings.of(a.script(), a.catalog(), rules);
        } catch (RuntimeException e) {
            findings = List.of();
        }
        var diagnostics = new ArrayList<>(a.parse().diagnostics());
        diagnostics.addAll(a.script().diagnostics());
        String md = HandoverDoc.markdown(a.script(), findings, diagnostics);
        boolean html = "html".equalsIgnoreCase(req.format());
        String text = html ? HandoverDoc.toHtml(HandoverDoc.title(a.script().source().name()), md) : md;
        String saved = null;
        if (req.save() != null && !req.save().isBlank()) {
            Files.writeString(Path.of(req.save()), text, StandardCharsets.UTF_8);
            saved = req.save();
        }
        return new DocResult(html ? "html" : "markdown", text, saved);
    }
}
