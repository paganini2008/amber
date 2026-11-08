package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.amber.core.Dialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scale and extreme structure: generated scripts far beyond the corpus (100,000 lines, 500 procedures in
 * call chains 200 deep with recursion, 2,000 variables in one scope, 50 nested subqueries, 10,000 UNION ALL
 * branches, 5,000 CTEs, 5,000 select items, a 1 MB line, 20,000 tiny statements, a 10,000-character
 * identifier, 1,000 temp tables). Each runs through {@code analyze}, {@code references}, {@code diff}
 * (identical and fully different), {@code format} and {@code doc.generate} like the server does; time,
 * peak heap and JSON size are printed as a table and checked against guards so that a regression to
 * quadratic behaviour fails. The guards are generous: about 4x the time measured on the reference
 * machine (Apple M-series, 12 cores, JDK 25) and never below 10 s. The 100,000-line case runs diff of two
 * different versions, doc.generate and ai.buildContext only with {@code -Damber.scale.full=true}.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ScaleIT {

    /** Guard per operation and case, in ms. Measured values are noted per case below. */
    private static final Map<String, Long> GUARDS_MS = new LinkedHashMap<>();
    /**
     * Guard for the peak heap growth of any single operation (failsafe runs with -Xmx2g). Measured: 1,450 MB
     * for analyze of 100k lines (the peak includes garbage not yet collected); others stay under 1,000 MB.
     */
    private static final long HEAP_GUARD_MB = 1_900;
    private static final List<String> TABLE = new ArrayList<>();

    record Case(String name, Dialect dialect, IntFunction<String> generator, long guardMs) {
    }

    // Guards (ms): about 4x the slowest operation measured for the case (reference machine), at least 10 s.
    // A guard that fails after a change means a regression towards quadratic behaviour, not noise.
    static final long LINES_100K_GUARD = 40_000;      // measured: diff(different) 9.2 s, analyze 6.6 s
    static final long PROCS_GUARD = 20_000;           // measured: doc.generate 5.2 s (Oracle), 3.5 s (SQL Server)
    static final long VARS_GUARD = 10_000;            // measured: analyze 0.7 s
    static final long NESTED_GUARD = 10_000;          // measured: analyze 1.8 s
    static final long UNION_GUARD = 10_000;           // measured: analyze 1.2 s, diff 1.1 s
    static final long CTE_GUARD = 10_000;             // measured: diff(different) 1.5 s
    static final long ITEMS_GUARD = 10_000;           // measured: doc.generate 1.5 s
    static final long LINE_1MB_GUARD = 10_000;        // measured: analyze 1.1 s (was 7.5 s; format 13.4 s before the splitter fix)
    static final long TINY_GUARD = 16_000;            // measured: diff(different) 3.9 s (was 34 s before the FindingComparer fix)
    static final long IDENT_GUARD = 10_000;           // measured: 13 ms
    static final long TEMP_GUARD = 10_000;            // measured: diff(different) 1.1 s

    static List<Case> cases() {
        return List.of(
                new Case("100k lines (Oracle)", Dialect.ORACLE, ScaleIT::lines100k, LINES_100K_GUARD),
                new Case("500 procs, chains 200 deep, recursion (Oracle)", Dialect.ORACLE, ScaleIT::oracleProcs, PROCS_GUARD),
                new Case("500 procs, chains 200 deep, recursion (SQL Server)", Dialect.SQLSERVER, ScaleIT::tsqlProcs, PROCS_GUARD),
                new Case("2,000 variables in one scope (Oracle)", Dialect.ORACLE, ScaleIT::vars2000, VARS_GUARD),
                new Case("50 nested subqueries (PostgreSQL)", Dialect.POSTGRESQL, ScaleIT::nested50, NESTED_GUARD),
                new Case("10,000 UNION ALL branches (PostgreSQL)", Dialect.POSTGRESQL, ScaleIT::union10k, UNION_GUARD),
                new Case("5,000 CTEs (PostgreSQL)", Dialect.POSTGRESQL, ScaleIT::cte5000, CTE_GUARD),
                new Case("5,000 select items (Oracle)", Dialect.ORACLE, ScaleIT::items5000, ITEMS_GUARD),
                new Case("1 MB single line (MySQL)", Dialect.MYSQL, ScaleIT::line1mb, LINE_1MB_GUARD),
                new Case("20,000 tiny statements (SQL Server)", Dialect.SQLSERVER, ScaleIT::tiny20k, TINY_GUARD),
                new Case("10,000-char identifier (PostgreSQL)", Dialect.POSTGRESQL, ScaleIT::ident10k, IDENT_GUARD),
                new Case("1,000 temp tables (SQL Server)", Dialect.SQLSERVER, ScaleIT::temp1000, TEMP_GUARD));
    }

    @TestFactory
    Stream<DynamicTest> scale() {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> run(c)));
    }

    private void run(Case c) {
        String text = c.generator().apply(0);
        String other = c.generator().apply(1);
        List<String> problems = new ArrayList<>();
        Duration watchdog = Duration.ofMillis(c.guardMs() * 3);

        ObjectNode p = Robust.params(c.dialect(), text, true);
        Measured analyze = measure(() -> Robust.call("analyze", p, watchdog));
        check(c, "analyze", analyze, problems);
        JsonNode result = analyze.outcome().json();
        String object = null;
        JsonNode column = null;
        if (result != null) {
            String bad = Robust.checkRanges(result, Robust.Texts.of(text));
            if (bad == null) {
                bad = Robust.checkAnalysis(result);
            }
            if (bad != null) {
                problems.add("analyze: " + bad);
            }
            if ("FAILED".equals(result.path("status").asText())) {
                problems.add("analyze: generated script did not parse");
            }
            for (JsonNode lc : result.path("lifecycles")) {
                object = lc.path("name").asText();
                break;
            }
            for (JsonNode n : result.path("lineage").path("nodes")) {
                if (n.path("ownerKind").asText().equals("TABLE") && !n.path("column").asText().startsWith("*")) {
                    column = n;
                    break;
                }
            }
        }
        if (object != null) {
            ObjectNode q = p.deepCopy();
            q.put("object", object);
            check(c, "references(object)", measure(() -> Robust.call("references", q, watchdog)), problems);
        }
        if (column != null) {
            ObjectNode q = p.deepCopy();
            q.put("table", column.path("owner").asText());
            q.put("column", column.path("column").asText());
            check(c, "references(column)", measure(() -> Robust.call("references", q, watchdog)), problems);
        }
        check(c, "diff(identical)", measure(() -> Robust.call("diff", diff(c.dialect(), text, text), watchdog)), problems);
        boolean full = c.guardMs() < LINES_100K_GUARD || Boolean.getBoolean("amber.scale.full");
        if (full) {
            check(c, "diff(different)", measure(() -> Robust.call("diff", diff(c.dialect(), text, other), watchdog)),
                    problems);
        }
        ObjectNode f = Robust.MAPPER.createObjectNode();
        f.put("dialect", c.dialect().id());
        f.put("text", text);
        check(c, "format", measure(() -> Robust.call("format", f, watchdog)), problems);
        if (full) {                                   // the 100k-line case skips these by default (-Damber.scale.full)
            check(c, "doc.generate", measure(() -> Robust.call("doc.generate", p, watchdog)), problems);
            ObjectNode ctx = p.deepCopy();
            ctx.put("tokenBudget", 6000);
            check(c, "ai.buildContext", measure(() -> Robust.call("ai.buildContext", ctx, watchdog)), problems);
        }
        assertTrue(problems.isEmpty(), c.name() + ": " + problems);
    }

    private static ObjectNode diff(Dialect d, String before, String after) {
        ObjectNode q = Robust.MAPPER.createObjectNode();
        q.put("dialect", d.id());
        q.putObject("before").put("text", before);
        q.putObject("after").put("text", after);
        return q;
    }

    record Measured(Robust.Outcome outcome, long peakHeapMb) {
    }

    /** Runs {@code op} while a sampler records the peak heap above the level before it started. */
    private static Measured measure(java.util.function.Supplier<Robust.Outcome> op) {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        System.gc();
        long base = mem.getHeapMemoryUsage().getUsed();
        long[] peak = {base};
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak[0] = Math.max(peak[0], mem.getHeapMemoryUsage().getUsed());
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        Robust.Outcome o = op.get();
        sampler.interrupt();
        try {
            sampler.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Measured(o, Math.max(0, (peak[0] - base) >> 20));
    }

    private static void check(Case c, String op, Measured m, List<String> problems) {
        Robust.Outcome o = m.outcome();
        String status = o.hung() ? "HANG" : o.error() != null ? Robust.describe(o.error()) : "ok";
        synchronized (TABLE) {
            TABLE.add(String.format("| %s | %s | %,d | %,d | %,d | %s |", c.name(), op, o.millis(), m.peakHeapMb(),
                    o.jsonChars() / 1024, status));
        }
        if (!o.ok()) {
            problems.add(op + ": " + status);
        } else if (o.millis() > c.guardMs()) {
            problems.add(op + ": " + o.millis() + " ms > guard " + c.guardMs() + " ms");
        }
        if (m.peakHeapMb() > HEAP_GUARD_MB) {
            problems.add(op + ": peak heap +" + m.peakHeapMb() + " MB > guard " + HEAP_GUARD_MB + " MB");
        }
    }

    @AfterAll
    static void printTable() {
        System.out.println("| Case | Operation | Time (ms) | Peak heap (+MB) | JSON (KB) | Status |");
        System.out.println("|---|---|---:|---:|---:|---|");
        TABLE.forEach(System.out::println);
    }

    // ----- generators: variant 0 is the script, variant 1 the "fully different" version for diff ----------

    private static String n(int variant) {
        return variant == 0 ? "" : "x";
    }

    static String lines100k(int v) {
        StringBuilder sb = new StringBuilder(4_000_000);
        String x = n(v);
        int i = 0;
        int lines = 0;
        while (lines < 100_000) {
            sb.append("-- step ").append(i).append('\n')
                    .append("INSERT INTO ").append(x).append("stage_").append(i % 50).append(" (id, amount, note)\n")
                    .append("SELECT o.id,\n       o.amount * 2,\n       c.name\n  FROM ").append(x).append("orders o\n")
                    .append("  JOIN ").append(x).append("customers c ON c.id = o.customer_id\n")
                    .append(" WHERE o.status = 'NEW'\n   AND o.batch = ").append(i).append(";\n")
                    .append("UPDATE ").append(x).append("stage_").append(i % 50).append("\n   SET amount = amount + 1\n")
                    .append(" WHERE id IN (SELECT id FROM ").append(x).append("refunds WHERE batch = ").append(i)
                    .append(");\n\n");
            lines += 12;
            i++;
        }
        return sb.toString();
    }

    static String oracleProcs(int v) {
        StringBuilder sb = new StringBuilder();
        String x = n(v);
        for (int i = 0; i < 500; i++) {
            int group = i / 200;
            int next = (i + 1) % 200 == 0 || i == 499 ? group * 200 : i + 1;     // the last of a chain calls the first
            sb.append("CREATE OR REPLACE PROCEDURE ").append(x).append("p_").append(i).append("(p_n NUMBER) AS\n")
                    .append("  v_total NUMBER;\nBEGIN\n")
                    .append("  SELECT SUM(amount) INTO v_total FROM ").append(x).append("src_").append(i % 20)
                    .append(" WHERE id = p_n;\n")
                    .append("  INSERT INTO ").append(x).append("t_").append(i).append(" (n, total) VALUES (p_n, v_total);\n")
                    .append("  IF p_n > 0 THEN\n    ").append(x).append("p_").append(next).append("(p_n - 1);\n")
                    .append("  END IF;\nEND;\n/\n\n");
        }
        sb.append("BEGIN\n  ").append(x).append("p_0(10);\n  ").append(x).append("p_200(10);\n  ").append(x)
                .append("p_400(10);\nEND;\n/\n");
        return sb.toString();
    }

    static String tsqlProcs(int v) {
        StringBuilder sb = new StringBuilder();
        String x = n(v);
        for (int i = 0; i < 500; i++) {
            int group = i / 200;
            int next = (i + 1) % 200 == 0 || i == 499 ? group * 200 : i + 1;
            sb.append("CREATE PROCEDURE dbo.").append(x).append("p_").append(i).append(" @n INT AS\nBEGIN\n")
                    .append("  DECLARE @total INT;\n")
                    .append("  SELECT @total = SUM(amount) FROM dbo.").append(x).append("src_").append(i % 20)
                    .append(" WHERE id = @n;\n")
                    .append("  INSERT INTO dbo.").append(x).append("t_").append(i).append(" (n, total) VALUES (@n, @total);\n")
                    .append("  IF @n > 0 EXEC dbo.").append(x).append("p_").append(next).append(" @n = @n;\nEND\nGO\n\n");
        }
        sb.append("EXEC dbo.").append(x).append("p_0 @n = 10;\nEXEC dbo.").append(x).append("p_200 10;\nGO\n");
        return sb.toString();
    }

    static String vars2000(int v) {
        StringBuilder sb = new StringBuilder("DECLARE\n");
        String x = n(v);
        for (int i = 0; i < 2000; i++) {
            sb.append("  ").append(x).append("v").append(i).append(" NUMBER := ").append(i).append(";\n");
        }
        sb.append("BEGIN\n");
        for (int i = 1; i < 2000; i++) {
            sb.append("  ").append(x).append("v").append(i).append(" := ").append(x).append("v").append(i - 1)
                    .append(" + ").append(x).append("v").append(i).append(";\n");
        }
        sb.append("  INSERT INTO ").append(x).append("result_t (total) VALUES (").append(x).append("v1999);\nEND;\n/\n");
        return sb.toString();
    }

    static String nested50(int v) {
        String x = n(v);
        String q = "SELECT id, amount FROM " + x + "base_t WHERE id IN (SELECT id FROM " + x + "keys_t)";
        for (int i = 0; i < 50; i++) {
            q = "SELECT s" + i + ".id, s" + i + ".amount + 1 AS amount FROM (" + q + ") s" + i
                    + " WHERE s" + i + ".id NOT IN (SELECT id FROM " + x + "skip_" + i + ")";
        }
        return "INSERT INTO " + x + "out_t (id, amount)\n" + q + ";\n";
    }

    static String union10k(int v) {
        String x = n(v);
        StringBuilder sb = new StringBuilder("INSERT INTO " + x + "all_t (id, amount)\n");
        for (int i = 0; i < 10_000; i++) {
            if (i > 0) {
                sb.append("UNION ALL\n");
            }
            sb.append("SELECT id, amount FROM ").append(x).append("part_").append(i % 100).append(" WHERE k = ").append(i)
                    .append('\n');
        }
        return sb.append(";\n").toString();
    }

    static String cte5000(int v) {
        String x = n(v);
        StringBuilder sb = new StringBuilder("INSERT INTO " + x + "final_t (id, amount)\nWITH c0 AS (SELECT id, amount FROM "
                + x + "base_t)");
        for (int i = 1; i < 5000; i++) {
            sb.append(",\nc").append(i).append(" AS (SELECT id, amount + 1 AS amount FROM c").append(i - 1).append(')');
        }
        return sb.append("\nSELECT id, amount FROM c4999;\n").toString();
    }

    static String items5000(int v) {
        String x = n(v);
        StringBuilder cols = new StringBuilder();
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            if (i > 0) {
                cols.append(", ");
                items.append(",\n       ");
            }
            cols.append("c").append(i);
            items.append("s.a").append(i).append(" + t.b").append(i % 10);
        }
        return "INSERT INTO " + x + "wide_t (" + cols + ")\nSELECT " + items + "\n  FROM " + x + "src_t s JOIN " + x
                + "other_t t ON t.id = s.id;\n";
    }

    static String line1mb(int v) {
        String x = n(v);
        StringBuilder sb = new StringBuilder("INSERT INTO " + x + "log_t (id, msg, amount) VALUES ");
        int i = 0;
        while (sb.length() < 1_048_576) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(i).append(", 'message ").append(i).append("', ").append(i % 97).append(".5)");
            i++;
        }
        return sb.append(";\n").toString();
    }

    static String tiny20k(int v) {
        String x = n(v);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append(i % 2 == 0 ? "SELECT 1 FROM " + x + "t" + (i % 7) + ";\n" : "UPDATE " + x + "t" + (i % 7) + " SET a = 1;\n");
        }
        return sb.toString();
    }

    static String ident10k(int v) {
        String id = (v == 0 ? "a" : "b").repeat(10_000);
        return "CREATE TABLE " + id + " (\"" + id + "\" int, c int);\n"
                + "INSERT INTO " + id + " (\"" + id + "\", c) SELECT \"" + id + "\", c FROM src_" + id.substring(0, 50) + ";\n"
                + "SELECT \"" + id + "\" FROM " + id + " WHERE c = '" + id + "';\n";
    }

    static String temp1000(int v) {
        String x = n(v);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("CREATE TABLE #").append(x).append("t").append(i).append(" (id INT, amount INT);\n")
                    .append("INSERT INTO #").append(x).append("t").append(i).append(" (id, amount) SELECT id, amount FROM dbo.src")
                    .append(i % 10).append(i > 0 ? " UNION ALL SELECT id, amount FROM #" + x + "t" + (i - 1) : "")
                    .append(";\n")
                    .append(i > 0 ? "DROP TABLE #" + x + "t" + (i - 1) + ";\n" : "");
        }
        sb.append("INSERT INTO dbo.").append(x).append("final (id, amount) SELECT id, amount FROM #").append(x)
                .append("t999;\nDROP TABLE #").append(x).append("t999;\n");
        return sb.toString();
    }
}
