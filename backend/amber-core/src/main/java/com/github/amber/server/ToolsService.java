package com.github.amber.server;

import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.refs.References;
import com.github.amber.docgen.HandoverDoc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                      String save) {
    }

    record DocResult(String format, String text, String saved) {
    }

    /** One side of a diff: the script text or a file. */
    record DiffSide(String text, String path, String encoding, String name) {
    }

    record DiffRequest(String dialect, DiffSide before, DiffSide after, List<String> metadataPacks,
                       List<String> ddlFiles, List<TableMeta> metadata) {
    }

    /** {@code statements}: step ids to look at; empty or absent means the whole script. */
    record RewriteRequest(String dialect, String text, String path, String encoding, String name, Boolean allowFallback,
                          List<String> metadataPacks, List<String> ddlFiles, List<TableMeta> metadata,
                          List<Integer> statements) {
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

    private final AnalysisService analysis;

    ToolsService(AnalysisService analysis) {
        this.analysis = analysis;
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

    com.github.amber.diff.DiffResult diff(DiffRequest req) throws IOException {
        if (req == null || req.dialect() == null || req.before() == null || req.after() == null) {
            throw new IllegalArgumentException("'dialect', 'before' and 'after' are required");
        }
        var catalog = MetadataService.catalog(req.dialect(), req.metadataPacks(), req.ddlFiles(), req.metadata());
        return com.github.amber.diff.ScriptDiff.compare(com.github.amber.core.Dialect.fromId(req.dialect()),
                side(req.dialect(), req.before(), "before.sql"), side(req.dialect(), req.after(), "after.sql"), catalog);
    }

    private static com.github.amber.core.source.SourceText side(String dialect, DiffSide s, String fallbackName)
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
        var list = com.github.amber.rewrite.RewriteSuggester.suggest(com.github.amber.core.Dialect.fromId(req.dialect()),
                source.text(), catalog, req.statements(), Boolean.TRUE.equals(req.allowFallback()));
        return new RewriteResult(list.stream().map(r -> new RewriteDto(r.id(), r.rule(), r.title(), r.explanation(),
                r.statement(), r.edits().stream().map(e -> new EditDto(Dtos.Range.of(e.range()), e.replacement())).toList(),
                r.rewrittenStatement(), r.safety().name(), r.safetyNote(), r.check(), r.checkDifferences(),
                r.conflictsWith())).toList());
    }

    ApplyResult apply(ApplyRequest req) {
        if (req == null || req.text() == null || req.edits() == null) {
            throw new IllegalArgumentException("'text' and 'edits' are required");
        }
        var src = new com.github.amber.core.source.SourceText("edit.sql", req.text());
        var edits = req.edits().stream().map(e -> new com.github.amber.rewrite.Edit(src.range(e.start(), e.end()),
                e.replacement() == null ? "" : e.replacement())).toList();
        return new ApplyResult(com.github.amber.rewrite.RewriteSuggester.apply(req.text(), edits));
    }

    FormatResultDto format(FormatRequest req) throws IOException {
        if (req == null || req.dialect() == null) {
            throw new IllegalArgumentException("'dialect' is required");
        }
        var src = AnalysisService.source(new AnalysisService.Request(req.dialect(), req.text(), req.path(),
                req.encoding(), null, false));
        var o = com.github.amber.format.FormatOptions.DEFAULTS;
        if (req.indentSize() != null) {
            o = o.withIndentSize(req.indentSize());
        }
        if (req.maxLineWidth() != null) {
            o = o.withMaxLineWidth(req.maxLineWidth());
        }
        if (req.keywordCase() != null) {
            o = o.withKeywordCase(com.github.amber.format.FormatOptions.KeywordCase.valueOf(
                    req.keywordCase().toUpperCase(java.util.Locale.ROOT)));
        }
        if (req.commaStyle() != null) {
            o = o.withCommaStyle(com.github.amber.format.FormatOptions.CommaStyle.valueOf(
                    req.commaStyle().toUpperCase(java.util.Locale.ROOT)));
        }
        var d = com.github.amber.core.Dialect.fromId(req.dialect());
        var r = req.start() != null
                ? com.github.amber.format.Formatter.formatRange(d, src.text(), req.start(),
                        req.end() == null ? req.start() : req.end(), o)
                : com.github.amber.format.Formatter.format(d, src.text(), o);
        return new FormatResultDto(r.text(), r.changed(), r.warnings(), r.skipped().stream().map(Dtos.Range::of).toList());
    }

    DocResult document(DocRequest req) throws IOException {
        var a = analysis.run(new AnalysisService.Request(req.dialect(), req.text(), req.path(), req.encoding(),
                req.name(), req.allowFallback(), req.metadataPacks(), req.ddlFiles(), req.metadata()));
        if (a.script() == null) {
            throw new IllegalArgumentException("The script does not parse; fix the syntax errors or allow fallback");
        }
        List<com.github.amber.dialect.findings.Finding> findings;
        try {
            findings = Findings.of(a.script(), a.catalog());
        } catch (RuntimeException e) {
            findings = List.of();
        }
        var diagnostics = new java.util.ArrayList<>(a.parse().diagnostics());
        diagnostics.addAll(a.script().diagnostics());
        String md = HandoverDoc.markdown(a.script(), findings, diagnostics);
        boolean html = "html".equalsIgnoreCase(req.format());
        String text = html ? HandoverDoc.toHtml(a.script().source().name() + " — handover notes", md) : md;
        String saved = null;
        if (req.save() != null && !req.save().isBlank()) {
            Files.writeString(Path.of(req.save()), text, StandardCharsets.UTF_8);
            saved = req.save();
        }
        return new DocResult(html ? "html" : "markdown", text, saved);
    }
}
