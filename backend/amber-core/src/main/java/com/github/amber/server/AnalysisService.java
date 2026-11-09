package com.github.amber.server;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.LineageGraph;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceDecoder;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.findings.RuleConfig;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import com.github.amber.rewrite.FindingFixes;
import com.github.amber.server.Dtos.DiagnosticDto;
import com.github.amber.server.Dtos.LifecycleDto;
import com.github.amber.server.Dtos.StatementDto;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code analyze} method: parse, build IR, compute lifecycles.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class AnalysisService {

    /**
     * @param metadataPacks paths of metadata packs to use
     * @param ddlFiles      DDL files whose CREATE statements describe tables
     * @param metadata      table metadata passed inline (e.g. from {@code db.loadMetadata})
     */
    public record Request(String dialect, String text, String path, String encoding, String name,
                          Boolean allowFallback, List<String> metadataPacks, List<String> ddlFiles,
                          List<TableMeta> metadata, Dtos.RulesDto rules) {

        public Request(String dialect, String text, String path, String encoding, String name, Boolean allowFallback) {
            this(dialect, text, path, encoding, name, allowFallback, null, null, null, null);
        }

        public Request(String dialect, String text, String path, String encoding, String name, Boolean allowFallback,
                       List<String> metadataPacks, List<String> ddlFiles,
                       List<TableMeta> metadata) {
            this(dialect, text, path, encoding, name, allowFallback, metadataPacks, ddlFiles, metadata, null);
        }
    }

    public record Result(String status, String dialect, String name, int lineCount, long elapsedMs,
                         List<DiagnosticDto> diagnostics, List<StatementDto> statements,
                         List<LifecycleDto> lifecycles, int stepCount, Dtos.LineageDto lineage,
                         List<Dtos.FindingDto> findings) {
    }

    /** A parsed and analyzed script, kept for follow-up requests about the same text. */
    public record Analysis(DialectFrontend frontend, ScriptParse parse, Script script,
                           Catalog catalog) {
    }

    public Result analyze(Request req) throws IOException {
        long started = System.nanoTime();
        Analysis a = run(req);
        ScriptParse parse = a.parse();
        List<DiagnosticDto> diagnostics = parse.diagnostics().stream().map(DiagnosticDto::of).toList();
        long ms = (System.nanoTime() - started) / 1_000_000;
        String dialect = a.frontend().dialect().id();
        if (a.script() == null) {
            return new Result(parse.status().name(), dialect, parse.source().name(), parse.source().lineCount(), ms,
                    diagnostics, List.of(), List.of(), 0, new Dtos.LineageDto(List.of(), List.of()), List.of());
        }
        List<Statement> steps = Lifecycles.steps(a.script());
        List<DiagnosticDto> all = new ArrayList<>(a.script().diagnostics().stream().map(DiagnosticDto::of).toList());
        var rules = Dtos.RulesDto.config(req.rules());
        List<Finding> findings;
        try {
            findings = findings(a, rules, steps);
        } catch (RuntimeException e) {
            findings = List.of();                 // findings are hints; never fail the analysis
            all.add(new DiagnosticDto("WARNING", "findings", "Findings could not be computed: " + e, null, null, List.of()));
        }
        return new Result(parse.status().name(), dialect, parse.source().name(), parse.source().lineCount(), ms,
                all, Dtos.statements(a.script().statements(), steps),
                Lifecycles.of(a.script()).stream().map(LifecycleDto::of).toList(), steps.size(),
                Dtos.lineage(LineageGraph.of(a.script()), steps),
                Dtos.findings(findings, steps));
    }

    /**
     * Findings of an analysis with the user's rule settings, each with its optimized SQL when one can be
     * built (the extra rules attach theirs; rules with a quick fix get the verified rewrite).
     */
    static List<Finding> findings(Analysis a,
                                                                    RuleConfig rules,
                                                                    List<Statement> steps) {
        var list = Findings.of(a.script(), a.catalog(), rules);
        return FindingFixes.attach(a.frontend().dialect(), a.parse().source().text(),
                a.catalog(), a.parse().status() != ScriptStatus.SUCCESS, list, steps, rules::enabled);
    }

    /** Parses and builds the IR; the script is {@code null} when strict parsing failed. */
    public Analysis run(Request req) throws IOException {
        if (req == null || req.dialect() == null) {
            throw new IllegalArgumentException("'dialect' is required");
        }
        DialectFrontend frontend = DialectFrontends.get(Dialect.fromId(req.dialect()));
        SourceText source = source(req);
        ParseOptions options = Boolean.TRUE.equals(req.allowFallback()) ? ParseOptions.FALLBACK : ParseOptions.STRICT;
        ScriptParse parse = frontend.parse(source, options);
        var catalog = MetadataService.catalog(req.dialect(), req.metadataPacks(), req.ddlFiles(), req.metadata());
        Script script = parse.status() == ScriptStatus.FAILED ? null : ScriptIrBuilder.build(frontend, parse, catalog.copy());
        return new Analysis(frontend, parse, script, catalog);
    }

    static SourceText source(Request req) throws IOException {
        if (req.text() != null) {
            return new SourceText(req.name() == null ? "untitled.sql" : req.name(), req.text());
        }
        if (req.path() == null) {
            throw new IllegalArgumentException("Give 'text' or 'path'");
        }
        Path path = Path.of(req.path());
        byte[] bytes = Files.readAllBytes(path);
        String text = req.encoding() == null
                ? SourceDecoder.decode(bytes).text()
                : SourceDecoder.decode(bytes, Charset.forName(req.encoding())).text();
        return new SourceText(req.name() == null ? path.getFileName().toString() : req.name(), text);
    }
}
