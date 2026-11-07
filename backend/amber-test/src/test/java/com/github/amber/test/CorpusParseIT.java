package com.github.amber.test;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.source.SourceDecoder;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.UnitParse;
import com.github.amber.dialect.UnitStatus;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Parses every file of the corpus with its dialect in strict mode and writes
 * {@code target/corpus-report.md}. Phase 1 records a baseline; pass-rate thresholds per dialect are
 * enforced once agreed. Files with a non-empty sibling {@code .errors} file are expected to fail
 * upstream.
 *
 * <p>Two corpora are read: {@code corpus/} (public, committed) and {@code corpus-private/}
 * (real business scripts, ignored by git, present only on developer machines).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class CorpusParseIT {

    private static final List<Path> CORPORA = List.of(
            Path.of("src/test/resources/corpus"),
            Path.of("src/test/resources/corpus-private"));
    /** Per-file parse timeout ({@code AMBER_CORPUS_TIMEOUT_SECONDS} in backend/.env, default 60). */
    private static final Duration TIMEOUT = Duration.ofSeconds(
            com.github.amber.core.config.Env.get().integer("AMBER_CORPUS_TIMEOUT_SECONDS", 60));
    private static final Map<Dialect, Stats> RESULTS = new ConcurrentSkipListMap<>();

    @TestFactory
    Stream<DynamicTest> corpus() {
        return DialectFrontends.all().values().stream()
                .map(frontend -> DynamicTest.dynamicTest(frontend.dialect().displayName(), () -> run(frontend)));
    }

    private void run(DialectFrontend frontend) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path corpus : CORPORA) {
            Path dir = corpus.resolve(frontend.dialect().id());
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> !p.toString().endsWith(".errors"))
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .sorted()
                            .forEach(files::add);
                }
            }
        }
        assertFalse(files.isEmpty(), "Empty corpus for " + frontend.dialect());

        Stats stats = new Stats();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            for (Path file : files) {
                boolean expectedFailure = isExpectedFailure(file);
                SourceText src = new SourceText(Path.of("src/test/resources").relativize(file).toString(),
                        SourceDecoder.decode(Files.readAllBytes(file)).text());
                Future<ScriptParse> future = pool.submit(() -> {
                    ScriptParse parse = frontend.parse(src);
                    if (parse.success()) {
                        var script = ScriptIrBuilder.build(frontend, parse);   // the IR must build for every parsed script
                        com.github.amber.dialect.findings.Findings.of(script);
                        com.github.amber.core.lineage.LineageGraph.of(script);
                    }
                    return parse;
                });
                try {
                    ScriptParse outcome = future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    stats.record(src, outcome, expectedFailure);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    stats.timeout(src);
                } catch (ExecutionException e) {
                    stats.crash(src, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
        RESULTS.put(frontend.dialect(), stats);
        System.out.printf("%-11s %s%n", frontend.dialect().displayName(), stats.summary());
    }

    private static boolean isExpectedFailure(Path file) throws IOException {
        Path errors = file.resolveSibling(file.getFileName() + ".errors");
        return Files.exists(errors) && Files.size(errors) > 0;
    }

    @AfterAll
    static void writeReport() throws IOException {
        StringBuilder md = new StringBuilder("# Corpus parse report\n\n");
        md.append("| Dialect | Files | Passed | Rate | Expected failures | Failed | Timeouts | Crashes | Units | Units via LL | Lines | Time |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        RESULTS.forEach((dialect, s) -> md.append(s.row(dialect.displayName())));
        RESULTS.forEach((dialect, s) -> {
            if (!s.failures.isEmpty()) {
                md.append("\n## ").append(dialect.displayName()).append(" failures\n\n");
                s.failures.forEach(f -> md.append("- ").append(f).append('\n'));
            }
        });
        Path out = Path.of("target/corpus-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println("Corpus report: " + out.toAbsolutePath());
    }

    private static final class Stats {
        int files, passed, expectedFailures, failed, timeouts, crashes, units, llUnits;
        long lines, nanos;
        final List<String> failures = new ArrayList<>();

        synchronized void record(SourceText src, ScriptParse outcome, boolean expectedFailure) {
            files++;
            lines += src.lineCount();
            nanos += outcome.elapsed().toNanos();
            for (UnitParse unit : outcome.units()) {
                if (unit.status() != UnitStatus.SKIPPED) {
                    units++;
                    if (unit.mode() == org.antlr.v4.runtime.atn.PredictionMode.LL) {
                        llUnits++;
                    }
                }
            }
            if (outcome.success()) {
                passed++;
            } else if (expectedFailure) {
                expectedFailures++;
            } else {
                failed++;
                Diagnostic first = outcome.diagnostics().getFirst();
                failures.add("`%s` %d:%d %s (%d errors)".formatted(src.name(), first.range().start().line(),
                        first.range().start().column(), first.message().replace('|', '/'), outcome.diagnostics().size()));
            }
        }

        synchronized void timeout(SourceText src) {
            files++;
            timeouts++;
            failures.add("`%s` timed out after %ds".formatted(src.name(), TIMEOUT.toSeconds()));
        }

        synchronized void crash(SourceText src, Throwable t) {
            files++;
            crashes++;
            failures.add("`%s` crashed: %s".formatted(src.name(), t));
        }

        double rate() {
            int considered = files - expectedFailures;
            return considered == 0 ? 0 : 100.0 * passed / considered;
        }

        String summary() {
            return "%d/%d passed (%.1f%%), %d expected failures, %d failed, %d timeouts, %d crashes, %d lines in %d ms"
                    .formatted(passed, files - expectedFailures, rate(), expectedFailures, failed, timeouts, crashes,
                            lines, nanos / 1_000_000);
        }

        String row(String name) {
            return "| %s | %d | %d | %.1f%% | %d | %d | %d | %d | %d | %d | %d | %d ms |\n".formatted(name, files, passed,
                    rate(), expectedFailures, failed, timeouts, crashes, units, llUnits, lines, nanos / 1_000_000);
        }
    }
}
