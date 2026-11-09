package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared machinery of the robustness tests: calls the engine's JSON-RPC handlers exactly as the server
 * does (params as JSON, result serialized with the server's mapper), under a watchdog that reports hangs,
 * and validates every range of a result against the text it refers to.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class Robust {

    static final ObjectMapper MAPPER = Main.mapper();
    static final Map<String, JsonRpcServer.Handler> HANDLERS = Methods.handlers(MAPPER);
    /** Methods owned by the rules/rewrite work: their problems are reported, never failed here. */
    static final Set<String> FOREIGN = Set.of("rewrite.suggest", "rewrite.optimize");

    /** Worker threads get the server's 64 MB stack so results match what the engine does. */
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(null, r, "amber-robust-" + THREADS.incrementAndGet(), 64L * 1024 * 1024);
        t.setDaemon(true);
        return t;
    });

    private Robust() {
    }

    /** One problem found: what was run on which input, and what went wrong. */
    record Problem(String subject, String op, String symptom, boolean foreign) {
        @Override
        public String toString() {
            return (foreign ? "[reported] " : "") + subject + " :: " + op + " :: " + symptom;
        }
    }

    /** Thread-safe list of problems plus the slowest call seen. */
    static final class Recorder {
        private final List<Problem> problems = Collections.synchronizedList(new ArrayList<>());
        private volatile long slowestMs;
        private volatile String slowest = "";
        private final AtomicInteger calls = new AtomicInteger();

        private volatile boolean deferTiming;
        private volatile boolean timingOnly;
        private final Set<String> slowCandidates = ConcurrentHashMap.newKeySet();

        /** While set, time-budget problems only mark their subject for a rerun without contention. */
        void deferTiming(boolean defer) {
            this.deferTiming = defer;
        }

        /** While set (during such a rerun), only time-budget problems are recorded; the others were already. */
        void timingOnly(boolean only) {
            this.timingOnly = only;
        }

        List<String> slowCandidates() {
            return List.copyOf(new TreeSet<>(slowCandidates));
        }

        void add(String subject, String op, String symptom) {
            boolean timing = symptom.startsWith("SLOW") || symptom.startsWith("took");
            if (timing && deferTiming) {
                slowCandidates.add(subject);
                return;
            }
            if (timingOnly && !timing) {
                return;
            }
            problems.add(new Problem(subject, op, symptom, FOREIGN.stream().anyMatch(op::startsWith)));
        }

        synchronized void timing(String what, long ms) {
            calls.incrementAndGet();
            if (ms > slowestMs) {
                slowestMs = ms;
                slowest = what;
            }
        }

        List<Problem> own() {
            synchronized (problems) {
                return problems.stream().filter(p -> !p.foreign()).toList();
            }
        }

        List<Problem> foreign() {
            synchronized (problems) {
                return problems.stream().filter(Problem::foreign).toList();
            }
        }

        int calls() {
            return calls.get();
        }

        String slowest() {
            return slowestMs + " ms (" + slowest + ")";
        }
    }

    /** Result of one call: the JSON result, or the failure; {@code hung} when the watchdog gave up. */
    record Outcome(JsonNode json, Throwable error, long millis, boolean hung, long jsonChars) {

        Outcome(JsonNode json, Throwable error, long millis, boolean hung) {
            this(json, error, millis, hung, 0);
        }

        boolean ok() {
            return error == null && !hung;
        }
    }

    /** Runs {@code method} like the server: params from JSON, result through the server's mapper. */
    static Outcome call(String method, JsonNode params, Duration watchdog) {
        JsonRpcServer.Handler handler = HANDLERS.get(method);
        if (handler == null) {
            return new Outcome(null, new IllegalStateException("no method " + method), 0, false);
        }
        long t0 = System.nanoTime();
        long[] size = new long[1];
        Future<JsonNode> f = WORKERS.submit(() -> {
            String json = MAPPER.writeValueAsString(handler.handle(params));
            size[0] = json.length();
            return MAPPER.readTree(json);
        });
        try {
            JsonNode json = f.get(watchdog.toMillis(), TimeUnit.MILLISECONDS);
            return new Outcome(json, null, (System.nanoTime() - t0) / 1_000_000, false, size[0]);
        } catch (TimeoutException e) {
            f.cancel(true);
            return new Outcome(null, null, (System.nanoTime() - t0) / 1_000_000, true);
        } catch (ExecutionException e) {
            return new Outcome(null, e.getCause(), (System.nanoTime() - t0) / 1_000_000, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Runs any task under the watchdog (used for direct, non-RPC calls). */
    static Outcome run(Callable<?> task, Duration watchdog) {
        long t0 = System.nanoTime();
        Future<?> f = WORKERS.submit(task);
        try {
            f.get(watchdog.toMillis(), TimeUnit.MILLISECONDS);
            return new Outcome(null, null, (System.nanoTime() - t0) / 1_000_000, false);
        } catch (TimeoutException e) {
            f.cancel(true);
            return new Outcome(null, null, (System.nanoTime() - t0) / 1_000_000, true);
        } catch (ExecutionException e) {
            return new Outcome(null, e.getCause(), (System.nanoTime() - t0) / 1_000_000, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static ObjectNode params(Dialect dialect, String text, boolean fallback) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("dialect", dialect.id());
        p.put("name", "fuzz.sql");
        p.put("text", text);
        p.put("allowFallback", fallback);
        return p;
    }

    /** Short description of a failure: class, message and the first frames of Amber's own code. */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName()).append(": ");
        String msg = String.valueOf(e.getMessage());
        sb.append(msg.length() > 200 ? msg.substring(0, 200) + "…" : msg);
        int shown = 0;
        for (StackTraceElement el : e.getStackTrace()) {
            if (el.getClassName().startsWith("com.github.amber") && shown < 3) {
                sb.append(" @ ").append(el.getClassName().replace("com.github.amber.", ""))
                        .append('.').append(el.getMethodName()).append(':').append(el.getLineNumber());
                shown++;
            }
        }
        if (e.getCause() != null && e.getCause() != e) {
            sb.append(" <- ").append(describe(e.getCause()));
        }
        return sb.toString();
    }

    // ----- range validation -------------------------------------------------------------------

    /** The texts ranges of a result may refer to: {@code main}, or {@code before}/{@code after} in a diff. */
    record Texts(SourceText main, SourceText before, SourceText after) {
        static Texts of(String text) {
            return new Texts(SourceText.of(text), null, null);
        }

        static Texts diff(String before, String after) {
            return new Texts(null, SourceText.of(before), SourceText.of(after));
        }
    }

    /**
     * Checks every range in {@code json} ({@code {start:{offset,line,column},end:{…}}}): offsets inside
     * the text and ordered, line and column consistent with the offset. Returns the first violation or null.
     */
    static String checkRanges(JsonNode json, Texts texts) {
        List<String> out = new ArrayList<>(1);
        walk(json, texts.main(), texts, "$", out);
        return out.isEmpty() ? null : out.get(0);
    }

    private static void walk(JsonNode node, SourceText text, Texts texts, String path, List<String> out) {
        if (!out.isEmpty() || node == null) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size() && out.isEmpty(); i++) {
                walk(node.get(i), text, texts, path + "[" + i + "]", out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode s = node.get("start");
        JsonNode e = node.get("end");
        if (s != null && e != null && s.isObject() && e.isObject() && s.has("offset")) {
            String bad = checkRange(s, e, text);
            if (bad != null) {
                out.add(path + ": " + bad);
            }
            return;
        }
        var it = node.fields();
        while (it.hasNext() && out.isEmpty()) {
            var f = it.next();
            SourceText t = text;
            if (f.getKey().startsWith("before") && texts.before() != null) {
                t = texts.before();
            } else if (f.getKey().startsWith("after") && texts.after() != null) {
                t = texts.after();
            }
            walk(f.getValue(), t, texts, path + "." + f.getKey(), out);
        }
    }

    private static String checkRange(JsonNode s, JsonNode e, SourceText text) {
        int so = s.get("offset").asInt();
        int eo = e.get("offset").asInt();
        if (so < 0 || eo < so) {
            return "unordered range [" + so + ", " + eo + ")";
        }
        if (text == null) {
            return null;
        }
        if (eo > text.length()) {
            return "range [" + so + ", " + eo + ") beyond text length " + text.length();
        }
        for (JsonNode p : List.of(s, e)) {
            int off = p.get("offset").asInt();
            var pos = text.positionAt(off);
            if (p.has("line") && (p.get("line").asInt() != pos.line() || p.get("column").asInt() != pos.column())) {
                return "offset " + off + " says " + p.get("line").asInt() + ":" + p.get("column").asInt()
                        + " but the text has " + pos.line() + ":" + pos.column();
            }
        }
        return null;
    }

    /**
     * Structural invariants of an {@code analyze} result: statement ids are 0..stepCount-1 depth first,
     * siblings in source order, children inside their parent; lifecycle, lineage and finding references
     * point at existing statements and nodes.
     */
    static String checkAnalysis(JsonNode r) {
        int steps = r.path("stepCount").asInt();
        int[] next = {0};
        String bad = checkStatements(r.path("statements"), -1, Integer.MAX_VALUE, next);
        if (bad != null) {
            return bad;
        }
        if (next[0] != steps) {
            return "stepCount " + steps + " but " + next[0] + " statements";
        }
        for (JsonNode lc : r.path("lifecycles")) {
            for (JsonNode ev : lc.path("events")) {
                if (ev.path("step").asInt() < 0 || ev.path("step").asInt() >= steps) {
                    return "lifecycle " + lc.path("key").asText() + " event at step " + ev.path("step");
                }
            }
        }
        int nodes = r.path("lineage").path("nodes").size();
        for (JsonNode edge : r.path("lineage").path("edges")) {
            int from = edge.path("from").asInt();
            int to = edge.path("to").asInt();
            int st = edge.path("statement").asInt();
            if (from < 0 || from >= nodes || to < 0 || to >= nodes || st < 0 || st >= steps) {
                return "lineage edge " + edge;
            }
        }
        for (JsonNode f : r.path("findings")) {
            int st = f.path("statement").asInt();
            if (st < 0 || st >= steps) {
                return "finding " + f.path("rule").asText() + " at statement " + st;
            }
        }
        return null;
    }

    private static String checkStatements(JsonNode list, int parentStart, int parentEnd, int[] next) {
        int prev = -1;
        for (JsonNode st : list) {
            int id = st.path("id").asInt();
            if (id != next[0]) {
                return "statement id " + id + " where " + next[0] + " was expected (depth-first order)";
            }
            next[0]++;
            JsonNode range = st.path("range");
            int s = range.path("start").path("offset").asInt();
            int e = range.path("end").path("offset").asInt();
            if (s < prev) {
                return "statement " + id + " starts at " + s + " before its previous sibling (" + prev + ")";
            }
            if (s < parentStart || e > parentEnd) {
                return "statement " + id + " [" + s + ", " + e + ") outside its parent [" + parentStart + ", "
                        + parentEnd + ")";
            }
            prev = s;
            String bad = checkStatements(st.path("children"), s, e, next);
            if (bad != null) {
                return bad;
            }
        }
        return null;
    }
}
