package com.github.amber.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.amber.server.Dtos.DiagnosticDto;
import com.github.amber.server.Dtos.LifecycleDto;
import com.github.amber.server.Dtos.StatementDto;

import java.io.PrintStream;
import java.util.List;

/**
 * Command-line analysis for CI and scripting: prints the outline, the object lifecycles and any
 * syntax errors, or the full protocol JSON with {@code --json}. Exit code 1 when parsing failed.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Cli {

    private Cli() {
    }

    static int analyze(ObjectMapper mapper, List<String> args, PrintStream out, PrintStream err) {
        String file = null;
        String dialect = null;
        boolean fallback = false;
        boolean json = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--dialect" -> dialect = i + 1 < args.size() ? args.get(++i) : null;
                case "--fallback" -> fallback = true;
                case "--json" -> json = true;
                default -> file = args.get(i);
            }
        }
        if (file == null || dialect == null) {
            err.println("Usage: engine.jar analyze FILE --dialect ID [--fallback] [--json]");
            return 2;
        }
        try {
            AnalysisService.Result r = new AnalysisService().analyze(
                    new AnalysisService.Request(dialect, null, file, null, null, fallback));
            if (json) {
                out.println(mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(r));
            } else {
                print(r, out);
            }
            return r.status().equals("FAILED") ? 1 : 0;
        } catch (Exception e) {
            err.println("amber: " + e.getMessage());
            return 2;
        }
    }

    /** {@code engine.jar doc FILE --dialect ID [--html] [--out PATH] [--fallback]}: a handover document. */
    static int doc(List<String> args, PrintStream out, PrintStream err) {
        String file = null;
        String dialect = null;
        String outPath = null;
        boolean fallback = false;
        boolean html = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--dialect" -> dialect = i + 1 < args.size() ? args.get(++i) : null;
                case "--out" -> outPath = i + 1 < args.size() ? args.get(++i) : null;
                case "--fallback" -> fallback = true;
                case "--html" -> html = true;
                default -> file = args.get(i);
            }
        }
        if (file == null || dialect == null) {
            err.println("Usage: engine.jar doc FILE --dialect ID [--html] [--out PATH] [--fallback]");
            return 2;
        }
        try {
            var r = new ToolsService(new AnalysisService()).document(new ToolsService.DocRequest(dialect, null, file,
                    null, null, fallback, null, null, null, html ? "html" : "markdown", outPath, null));
            if (r.saved() == null) {
                out.print(r.text());
            } else {
                out.println("Wrote " + r.saved());
            }
            return 0;
        } catch (Exception e) {
            err.println("amber: " + e.getMessage());
            return 2;
        }
    }

    private static void print(AnalysisService.Result r, PrintStream out) {
        out.printf("%s  %s  %d lines  %s  %d ms%n", r.name(), r.dialect(), r.lineCount(), r.status(), r.elapsedMs());
        for (DiagnosticDto d : r.diagnostics()) {
            out.printf("  %s %d:%d %s%n", d.severity(), d.range().start().line(), d.range().start().column(), d.message());
        }
        if (!r.statements().isEmpty()) {
            out.println();
            out.println("Outline");
            r.statements().forEach(s -> outline(s, "  ", out));
            out.println();
            out.println("Objects");
            for (LifecycleDto l : r.lifecycles()) {
                out.printf("  %-40s %-14s %-12s %s%n", l.name(), l.kind(), l.role(),
                        String.join(" ", l.events().stream().map(e -> e.step() + ":" + e.access()
                                + (e.via() == null ? "" : "(via " + e.via() + ")")).toList()));
            }
            if (!r.findings().isEmpty()) {
                out.println();
                out.println("Findings");
                for (Dtos.FindingDto f : r.findings()) {
                    out.printf("  %-7s %s  L%-5d %s%n", f.severity(), f.rule(), f.range().start().line(), f.title());
                }
            }
        }
    }

    private static void outline(StatementDto s, String indent, PrintStream out) {
        out.printf("%s%3d  L%-5d %-20s %s%n", indent, s.id(), s.range().start().line(), s.kind(), s.summary());
        for (StatementDto c : s.children()) {
            outline(c, indent + "  ", out);
        }
    }
}
