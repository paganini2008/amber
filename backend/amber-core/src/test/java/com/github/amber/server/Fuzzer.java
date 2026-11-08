package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.amber.core.Dialect;
import com.github.amber.format.FormatContract;
import com.github.amber.format.FormatOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Runs every engine operation on one mutant and records what breaks: {@code analyze} (IR, lifecycles,
 * lineage, findings, structure, execution order) strict and with fallback, {@code references} for objects
 * and a column, {@code doc.generate}, {@code ai.buildContext}, {@code ai.check}, {@code format} (whole and
 * selection, plus the formatter's contract), {@code diff} against the original and the rewrite methods.
 * Allowed failures are only the documented {@link IllegalArgumentException}s for a script that does not
 * parse strictly.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class Fuzzer {

    /** Per operation: 5 s for files up to 100 KB, proportionally more above. */
    static final long OP_BUDGET_MS = 5_000;
    /**
     * All of Amber's own operations on one mutant (the rewrite methods are timed separately): 30 s for
     * files up to 40 KB, proportionally more above. Every operation parses the script again, as the
     * stateless server does, so this is about ten parses.
     */
    static final long FILE_BUDGET_MS = 30_000;
    /**
     * Budgets are measured on a developer machine. Shared CI runners (two slow cores, noisy neighbours) need more
     * room: {@code AMBER_FUZZ_BUDGET_SCALE}, else 2.5 when {@code CI=true} (set by GitHub Actions), else 1. Hangs
     * and quadratic blow-ups still fail: they exceed any of these by far, and the watchdog stops them.
     */
    static final double MACHINE_SCALE = machineScale(System.getenv());

    static double machineScale(java.util.Map<String, String> env) {
        String explicit = env.get("AMBER_FUZZ_BUDGET_SCALE");
        if (explicit != null && !explicit.isBlank()) {
            try {
                return Math.max(1.0, Double.parseDouble(explicit.trim()));
            } catch (NumberFormatException e) {
                // fall through to the default
            }
        }
        return "true".equalsIgnoreCase(env.get("CI")) ? 2.5 : 1.0;
    }
    private static final String NOT_PARSED = "The script does not parse";

    private final Robust.Recorder rec;

    Fuzzer(Robust.Recorder rec) {
        this.rec = rec;
    }

    /** Runs the operations on {@code mutant} of {@code original}; {@code subject} identifies it for the report. */
    void run(String subject, Dialect dialect, String original, String mutant, long seed) {
        double scale = Math.max(1.0, mutant.length() / 100_000.0) * MACHINE_SCALE;
        long opBudget = (long) (OP_BUDGET_MS * scale);
        Duration watchdog = Duration.ofMillis(Math.max(60_000, opBudget * 6));
        Random rnd = new Random(seed ^ 0x5DEECE66DL);
        long total = 0;
        for (boolean fallback : new boolean[]{false, true}) {
            String mode = fallback ? "fallback" : "strict";
            ObjectNode p = Robust.params(dialect, mutant, fallback);
            Robust.Outcome a = call(subject, "analyze/" + mode, "analyze", p, opBudget, watchdog,
                    Robust.Texts.of(mutant), false);
            total += a.millis();
            if (!a.ok()) {
                continue;
            }
            String invariant = Robust.checkAnalysis(a.json());
            if (invariant != null) {
                rec.add(subject, "analyze/" + mode, "invariant: " + invariant);
            }
            for (JsonNode d : a.json().path("diagnostics")) {
                if ("findings".equals(d.path("code").asText())) {
                    rec.add(subject, "rewrite.findings/" + mode, d.path("message").asText());   // reported only
                }
            }
            boolean parsed = !"FAILED".equals(a.json().path("status").asText());
            if (fallback && !parsed) {
                rec.add(subject, "analyze/" + mode, "status FAILED although fallback is allowed");
            }
            List<ObjectNode> calls = new ArrayList<>();
            for (String name : objectNames(a.json(), rnd, mutant)) {
                calls.add(with(p, "references", "object", name));
            }
            JsonNode column = column(a.json());
            if (column != null) {
                ObjectNode q = with(p, "references", "table", column.path("owner").asText());
                q.put("column", column.path("column").asText());
                calls.add(q);
            }
            calls.add(with(p, "doc.generate", "format", fallback ? "html" : "markdown"));
            ObjectNode ctx = with(p, "ai.buildContext", "notes", "fuzz");
            ctx.put("tokenBudget", fallback ? 6000 : 400);
            calls.add(ctx);
            calls.add(with(p, "rewrite.suggest", null, null));
            if (Robust.HANDLERS.containsKey("rewrite.optimize")) {
                calls.add(with(p, "rewrite.optimize", null, null));
            }
            if (Robust.HANDLERS.containsKey("er.model")) {
                ObjectNode er = with(p, "er.model", null, null);
                er.put("includeDataFlow", fallback);
                calls.add(er);
            }
            for (ObjectNode q : calls) {
                String method = q.remove("$method").asText();
                if (!parsed && !method.equals("references")) {
                    continue;                                   // the documented "does not parse" path is checked once
                }
                long ms = call(subject, method + "/" + mode, method, q, opBudget, watchdog, Robust.Texts.of(mutant),
                        !parsed).millis();
                total += Robust.FOREIGN.contains(method) ? 0 : ms;
            }
        }

        ObjectNode f = Robust.MAPPER.createObjectNode();
        f.put("dialect", dialect.id());
        f.put("text", mutant);
        total += call(subject, "format", "format", f, opBudget, watchdog, Robust.Texts.of(mutant), false).millis();
        ObjectNode fr = f.deepCopy();
        int s = mutant.isEmpty() ? 0 : rnd.nextInt(mutant.length() + 1);
        int e = s + (mutant.length() == s ? 0 : rnd.nextInt(Math.min(2_000, mutant.length() - s) + 1));
        fr.put("start", s);
        fr.put("end", e);
        fr.put("keywordCase", "lower");
        fr.put("commaStyle", "leading");
        fr.put("indentSize", 2);
        total += call(subject, "format[" + s + "," + e + ")", "format", fr, opBudget, watchdog, Robust.Texts.of(mutant),
                false).millis();
        Robust.Outcome contract = Robust.run(() -> {
            String bad = FormatContract.check(dialect, mutant, FormatOptions.DEFAULTS);
            if (bad == null) {
                bad = FormatContract.checkRange(dialect, mutant, s, e);
            }
            if (bad != null) {
                rec.add(subject, "format.contract", bad);
            }
            return null;
        }, watchdog);
        report(subject, "format.contract", contract, opBudget * 4, false);
        total += contract.millis();

        ObjectNode diff = Robust.MAPPER.createObjectNode();
        diff.put("dialect", dialect.id());
        diff.putObject("before").put("text", original).put("name", "before.sql");
        diff.putObject("after").put("text", mutant).put("name", "after.sql");
        total += call(subject, "diff", "diff", diff, opBudget * 2, watchdog, Robust.Texts.diff(original, mutant), false)
                .millis();

        ObjectNode check = Robust.MAPPER.createObjectNode();
        check.put("dialect", dialect.id());
        check.put("original", original.length() > 20_000 ? original.substring(0, 20_000) : original);
        check.put("rewritten", mutant.length() > 20_000 ? mutant.substring(0, 20_000) : mutant);
        check.putObject("mask").put("T1", "orders");
        total += call(subject, "ai.check", "ai.check", check, opBudget, watchdog, new Robust.Texts(null, null, null), false)
                .millis();

        Dialect other = Dialect.values()[rnd.nextInt(Dialect.values().length)];
        if (other != dialect) {
            total += call(subject, "analyze/as-" + other.id(), "analyze", Robust.params(other, mutant, true), opBudget,
                    watchdog, Robust.Texts.of(mutant), false).millis();
        }
        long fileBudget = (long) (FILE_BUDGET_MS * Math.max(1.0, mutant.length() / 40_000.0) * MACHINE_SCALE);
        if (total > fileBudget) {
            rec.add(subject, "all operations", "took " + total + " ms, budget " + fileBudget + " ms");
        }
    }

    private Robust.Outcome call(String subject, String op, String method, ObjectNode params, long budget,
                                Duration watchdog, Robust.Texts texts, boolean mayRejectUnparsed) {
        Robust.Outcome o = Robust.call(method, params, watchdog);
        report(subject, op, o, budget, mayRejectUnparsed);
        if (o.ok()) {
            String bad = Robust.checkRanges(o.json(), texts);
            if (bad != null) {
                rec.add(subject, op, "bad range " + bad);
            }
        }
        return o;
    }

    private void report(String subject, String op, Robust.Outcome o, long budget, boolean mayRejectUnparsed) {
        rec.timing(subject + " " + op, o.millis());
        if (o.millis() > 1_000) {
            FuzzCampaign.trace("  " + o.millis() + " ms " + op + " " + subject);
        }
        if (o.hung()) {
            rec.add(subject, op, "HANG: no answer within " + o.millis() + " ms");
        } else if (o.error() != null) {
            Throwable e = o.error();
            String msg = String.valueOf(e.getMessage());
            boolean documented = e instanceof IllegalArgumentException
                    && (mayRejectUnparsed && msg.startsWith(NOT_PARSED)
                    || op.startsWith("references") && msg.startsWith("Not an object name"));   // a random word
            if (!documented) {
                rec.add(subject, op, Robust.describe(e));
            }
        } else if (o.millis() > budget) {
            rec.add(subject, op, "SLOW: " + o.millis() + " ms, budget " + budget + " ms");
        }
    }

    private static ObjectNode with(ObjectNode base, String method, String key, String value) {
        ObjectNode q = base.deepCopy();
        q.put("$method", method);
        if (key != null) {
            q.put(key, value);
        }
        return q;
    }

    /** Up to two objects of the script and one arbitrary word of the text. */
    private static List<String> objectNames(JsonNode analysis, Random rnd, String text) {
        List<String> names = new ArrayList<>();
        for (JsonNode lc : analysis.path("lifecycles")) {
            if (names.size() < 2) {
                names.add(lc.path("name").asText());
            }
        }
        String[] words = text.split("[\\s,;()]+", 200);
        if (words.length > 0) {
            String w = words[rnd.nextInt(words.length)];
            names.add(w.isBlank() ? "missing_object" : w);
        }
        return names;
    }

    private static JsonNode column(JsonNode analysis) {
        for (JsonNode n : analysis.path("lineage").path("nodes")) {
            String kind = n.path("ownerKind").asText();
            if ((kind.equals("TABLE") || kind.equals("TEMP_TABLE")) && !n.path("column").asText().startsWith("*")) {
                return n;
            }
        }
        return null;
    }

    // ----- inputs --------------------------------------------------------------------------------

    /** A file to mutate and the dialect it is written in. */
    record Input(String name, Dialect dialect, String text) {
    }

    static final Path CORPUS = Path.of("src/test/resources/corpus");
    static final Path SAMPLES = Path.of("../../ui/amber-desktop/src/renderer/src/samples");

    /** The desktop app's sample scripts (read only), one per dialect; empty when the UI tree is absent. */
    static List<Input> samples() {
        List<Input> out = new ArrayList<>();
        if (!Files.isDirectory(SAMPLES)) {
            return out;
        }
        try (Stream<Path> files = Files.list(SAMPLES)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".sql")).sorted().toList()) {
                String id = p.getFileName().toString().replaceFirst("-.*", "");
                out.add(new Input("samples/" + p.getFileName(), Dialect.fromId(id), Files.readString(p)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Every corpus file, with the dialect of its folder. */
    static List<Input> corpus() {
        List<Input> out = new ArrayList<>();
        for (Dialect d : Dialect.values()) {
            Path dir = CORPUS.resolve(d.id());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(Files::isRegularFile).filter(x -> !x.toString().endsWith(".errors"))
                        .filter(x -> !x.getFileName().toString().startsWith(".")).sorted().toList()) {
                    out.add(new Input(CORPUS.relativize(p).toString(), d,
                            com.github.amber.core.source.SourceDecoder.decode(Files.readAllBytes(p)).text()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** Seed of the {@code k}-th mutant of a file: stable across runs and machines. */
    static long seed(long base, String name, int k) {
        return base * 1_000_003L + name.hashCode() * 31L + k;
    }
}
