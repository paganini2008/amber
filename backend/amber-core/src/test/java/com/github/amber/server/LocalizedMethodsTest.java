package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.amber.core.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The optional {@code locale} param: zh-CN and es change the prose of every localized method while ids,
 * SQL, ranges, codes and enum values stay identical to English; unknown or missing locales give English;
 * concurrent requests in different languages do not leak into each other.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class LocalizedMethodsTest {

    private static final ObjectMapper MAPPER = Main.mapper();
    private static final Map<String, JsonRpcServer.Handler> HANDLERS = Methods.handlers(MAPPER);

    /** Fields whose text is prose and may differ between languages; every other leaf must be identical. */
    private static final Set<String> PROSE = Set.of("summary", "beforeSummary", "afterSummary", "title", "message",
            "suggestion", "suggestedSqlNote", "description", "explanation", "safetyNote", "differences",
            "checkDifferences", "conflictsWith", "note", "notes", "headline", "mermaid", "detail");

    static final String SCRIPT = String.join("\n",
            "SELECT o.id, c.name FROM sales.orders o, sales.customers c;",
            "SELECT o.id FROM sales.orders o LEFT JOIN sales.customers c ON c.id = o.customer_id WHERE c.name = 'x';",
            "SELECT id FROM sales.orders WHERE status LIKE '%x' UNION SELECT id FROM sales.orders",
            "  WHERE id NOT IN (SELECT customer_id FROM sales.orders);",
            "SELECT id FROM sales.orders WHERE TRUNC(created) = DATE '2024-01-01';",
            "INSERT INTO sales.archive SELECT * FROM sales.orders;",
            "SELECT DISTINCT status FROM sales.orders GROUP BY status;",
            "SELECT * FROM (SELECT id FROM sales.orders ORDER BY id) x;",
            "SELECT o.id, (SELECT COUNT(*) FROM sales.customers c WHERE c.id = o.customer_id) AS n FROM sales.orders o;",
            "SELECT id FROM sales.orders WHERE id + 1 = 5;",
            "SELECT o.id FROM sales.orders o WHERE (SELECT COUNT(*) FROM sales.customers c WHERE c.id = o.customer_id) > 0;",
            "BEGIN",
            "  FOR r IN (SELECT id FROM sales.orders) LOOP",
            "    UPDATE sales.orders SET status = 'X' WHERE id = r.id;",
            "    COMMIT;",
            "  END LOOP;",
            "  EXECUTE IMMEDIATE 'DROP TABLE x';",
            "END;",
            "/",
            "SELECT o.id FROM sales.orders o LEFT JOIN sales.customers c ON c.id = o.customer_id WHERE c.name = 'x';",
            "");

    private static final String AFTER = String.join("\n",
            "SELECT o.id, c.name, c.city FROM sales.orders o JOIN sales.customers c ON c.id = o.customer_id;",
            "SELECT id FROM sales.orders WHERE status LIKE 'x%' UNION ALL SELECT id FROM sales.archive;",
            "INSERT INTO sales.archive (id, status) SELECT id, status FROM sales.orders WHERE status = 'X';",
            "DELETE FROM sales.customers WHERE id = 1;",
            "SELECT DISTINCT status FROM sales.orders GROUP BY status;",
            "");

    private static final String META = "\"metadata\":[{\"schema\":\"sales\",\"name\":\"orders\",\"kind\":\"TABLE\","
            + "\"columns\":[{\"name\":\"id\",\"type\":\"number\",\"nullable\":false},"
            + "{\"name\":\"status\",\"type\":\"varchar2(10)\",\"nullable\":true},"
            + "{\"name\":\"customer_id\",\"type\":\"number\",\"nullable\":true},"
            + "{\"name\":\"created\",\"type\":\"date\",\"nullable\":true}],"
            + "\"primaryKey\":[\"id\"],\"indexes\":[{\"name\":\"ix_created\",\"columns\":[\"created\"],\"unique\":false}],"
            + "\"rowEstimate\":-1}]";

    private static String text(String s) throws Exception {
        return MAPPER.writeValueAsString(s);
    }

    /** Params of each localized method, without {@code locale}. */
    static Map<String, String> requests() throws Exception {
        String script = "\"dialect\":\"oracle\",\"text\":" + text(SCRIPT) + ",\"allowFallback\":true";
        Map<String, String> r = new LinkedHashMap<>();
        r.put("analyze", "{" + script + "," + META + "}");
        r.put("doc.generate", "{" + script + "," + META + "}");
        r.put("doc.generate#html", "{" + script + ",\"format\":\"html\"}");
        r.put("rewrite.suggest", "{" + script + "," + META + "}");
        r.put("rewrite.optimize", "{" + script + "," + META + ",\"includeEdgeCases\":true}");
        r.put("rewrite.apply", "{\"text\":\"select 1 from dual\",\"edits\":[{\"start\":0,\"end\":6,\"replacement\":\"SELECT\"}]}");
        r.put("er.model", "{" + script + ",\"includeDataFlow\":true}");
        r.put("references", "{" + script + ",\"object\":\"sales.orders\"}");
        r.put("diff", "{\"dialect\":\"oracle\",\"before\":{\"text\":" + text(SCRIPT) + "},\"after\":{\"text\":"
                + text(AFTER) + "}}");
        r.put("ai.check", "{\"dialect\":\"oracle\",\"original\":\"SELECT id, status FROM sales.orders ORDER BY id\","
                + "\"rewritten\":\"SELECT id FROM sales.customers GROUP BY id\"}");
        r.put("ai.check#invalid", "{\"dialect\":\"oracle\",\"original\":\"SELECT id FROM sales.orders\","
                + "\"rewritten\":\"SELEC id FROM\"}");
        r.put("engine.rules", "{}");
        return r;
    }

    static JsonNode call(String method, String params, String locale) throws Exception {
        ObjectNode p = (ObjectNode) MAPPER.readTree(params);
        if (locale != null) {
            p.put("locale", locale);
        }
        Object result = HANDLERS.get(method.contains("#") ? method.substring(0, method.indexOf('#')) : method).handle(p);
        JsonNode n = MAPPER.valueToTree(result);
        stripTimes(n);
        return n;
    }

    private static void stripTimes(JsonNode n) {
        if (n instanceof ObjectNode o) {
            o.remove("elapsedMs");
        }
        for (JsonNode child : n) {
            stripTimes(child);
        }
    }

    /**
     * Compares two results leaf by leaf: the structure and every non-prose leaf must be equal; returns how many
     * prose leaves differ.
     */
    static int compare(String path, String field, JsonNode en, JsonNode other, List<String> problems) {
        if (en.getNodeType() != other.getNodeType() || en.size() != other.size()) {
            problems.add(path + ": shape differs");
            return 0;
        }
        int differing = 0;
        if (en.isObject()) {
            for (Iterator<String> it = en.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                if (!other.has(name)) {
                    problems.add(path + "." + name + " missing");
                    continue;
                }
                differing += compare(path + "." + name, name, en.get(name), other.get(name), problems);
            }
        } else if (en.isArray()) {
            for (int i = 0; i < en.size(); i++) {
                differing += compare(path + "[" + i + "]", field, en.get(i), other.get(i), problems);
            }
        } else if (!en.equals(other)) {
            if (PROSE.contains(field) || (path.startsWith("doc.generate") && field.equals("text"))) {
                differing++;
            } else {
                problems.add(path + ": " + en + " vs " + other);
            }
        }
        return differing;
    }

    @Test
    void chineseAndSpanishChangeOnlyTheProse() throws Exception {
        Set<String> unchanged = Set.of("rewrite.apply", "references");
        for (var e : requests().entrySet()) {
            JsonNode en = call(e.getKey(), e.getValue(), null);
            assertEquals(en, call(e.getKey(), e.getValue(), "en"), e.getKey() + ": en is the default");
            for (String locale : List.of("zh-CN", "es")) {
                JsonNode other = call(e.getKey(), e.getValue(), locale);
                List<String> problems = new ArrayList<>();
                int differing = compare(e.getKey(), "", en, other, problems);
                assertEquals(List.of(), problems, e.getKey() + " " + locale);
                if (unchanged.contains(e.getKey())) {
                    assertEquals(en, other, e.getKey() + " has no prose");
                } else {
                    assertTrue(differing > 0, e.getKey() + " " + locale + ": nothing translated");
                }
            }
        }
    }

    @Test
    void everyFindingAndSummaryIsTranslated() throws Exception {
        String params = requests().get("analyze");
        JsonNode en = call("analyze", params, null);
        JsonNode zh = call("analyze", params, "zh-CN");
        JsonNode es = call("analyze", params, "es");
        assertTrue(en.get("findings").size() >= 10, en.get("findings").toString());
        for (int i = 0; i < en.get("findings").size(); i++) {
            for (String f : List.of("title", "message", "suggestion")) {
                String e = en.get("findings").get(i).get(f).asText();
                assertNotEquals(e, zh.get("findings").get(i).get(f).asText(), "zh " + f + " " + e);
                assertNotEquals(e, es.get("findings").get(i).get(f).asText(), "es " + f + " " + e);
            }
        }
        assertEquals(Messages.of("zh-CN").text("rule.AMB-S001.title"), zh.get("findings").get(0).get("title").asText());
        assertEquals("Producto cartesiano", es.get("findings").get(0).get("title").asText());
        for (int i = 0; i < en.get("statements").size(); i++) {
            String summary = en.get("statements").get(i).get("summary").asText();
            assertNotEquals(summary, zh.get("statements").get(i).get("summary").asText());
            assertNotEquals(summary, es.get("statements").get(i).get("summary").asText());
        }
        assertTrue(zh.get("statements").get(0).get("summary").asText().startsWith(Messages.of("zh-CN").text("summary.SELECT", "", "").strip() + " "),
                zh.get("statements").get(0).toString());
        assertTrue(es.get("statements").get(0).get("summary").asText().startsWith("Consulta "));
    }

    @Test
    void engineRulesComeInTheRequestedLanguage() throws Exception {
        JsonNode zh = call("engine.rules", "{}", "zh-CN");
        JsonNode es = call("engine.rules", "{}", "es");
        assertEquals(Messages.of("zh-CN").text("rule.AMB-S001.title"), zh.get("rules").get(0).get("title").asText());
        assertEquals("Producto cartesiano", es.get("rules").get(0).get("title").asText());
        assertEquals("Cartesian product", call("engine.rules", "{}", null).get("rules").get(0).get("title").asText());
        assertEquals("Cartesian product", MAPPER.valueToTree(HANDLERS.get("engine.rules").handle(null))
                .get("rules").get(0).get("title").asText(), "no params at all");
    }

    @Test
    void unknownOrMalformedLocalesFallBackToEnglish() throws Exception {
        for (var e : requests().entrySet()) {
            JsonNode en = call(e.getKey(), e.getValue(), null);
            for (String locale : List.of("fr", "", "xx-YY", "zh-TW")) {
                assertEquals(en, call(e.getKey(), e.getValue(), locale), e.getKey() + " " + locale);
            }
            ObjectNode p = (ObjectNode) MAPPER.readTree(e.getValue());
            p.put("locale", 42);
            String method = e.getKey().replaceAll("#.*", "");
            JsonNode n = MAPPER.valueToTree(HANDLERS.get(method).handle(p));
            stripTimes(n);
            assertEquals(en, n, e.getKey() + " numeric locale");
        }
        assertEquals("zh-CN", Messages.of("zh_CN").tag());
    }

    @Test
    void errorsStayEnglish() throws Exception {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("locale", "zh-CN");
        try {
            HANDLERS.get("analyze").handle(p);
            fail("no dialect");
        } catch (IllegalArgumentException e) {
            assertEquals("'dialect' is required", e.getMessage());
        }
    }

    /** Many requests in two languages at once, on the same handlers and worker threads. */
    @Test
    void concurrentRequestsInDifferentLanguagesDoNotLeak() throws Exception {
        String params = requests().get("analyze");
        String diff = requests().get("diff");
        Map<String, JsonNode> expected = new HashMap<>();
        for (String l : List.of("zh-CN", "es", "en")) {
            expected.put("analyze/" + l, call("analyze", params, l));
            expected.put("diff/" + l, call("diff", diff, l));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String l = List.of("zh-CN", "es", "en").get(i % 3);
                String method = i % 2 == 0 ? "analyze" : "diff";
                Callable<String> task = () -> {
                    start.await();
                    JsonNode got = call(method, method.equals("analyze") ? params : diff, l);
                    return expected.get(method + "/" + l).equals(got) ? null : method + " " + l + " leaked";
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<String> f : futures) {
                assertEquals(null, f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("en", Messages.current().tag(), "the test thread is untouched");
    }

    /** The same through the JSON-RPC server: interleaved requests on its worker pool. */
    @Test
    void theServerAnswersEachRequestInItsOwnLanguage() throws Exception {
        String params = requests().get("analyze");
        List<String> lines = new ArrayList<>();
        int n = 12;
        for (int i = 0; i < n; i++) {
            ObjectNode p = (ObjectNode) MAPPER.readTree(params);
            String l = List.of("zh-CN", "es", "en").get(i % 3);
            p.put("locale", l);
            lines.add("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"analyze\",\"params\":" + p + "}");
        }
        lines.add("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"engine.rules\"}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonRpcServer(MAPPER, HANDLERS, new PrintStream(new ByteArrayOutputStream()))
                .serve(new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)), out);
        List<JsonNode> responses = new ArrayList<>();
        for (int wait = 0; wait < 200 && responses.size() < n + 1; wait++) {
            responses.clear();
            for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
                if (!l.isBlank()) {
                    responses.add(MAPPER.readTree(l));
                }
            }
            Thread.sleep(50);
        }
        assertEquals(n + 1, responses.size());
        for (JsonNode r : responses) {
            int id = r.get("id").asInt();
            String title = r.get("result").get(id == 99 ? "rules" : "findings").get(0).get("title").asText();
            String want = id == 99 ? "Cartesian product"
                    : List.of(Messages.of("zh-CN").text("rule.AMB-S001.title"), "Producto cartesiano", "Cartesian product").get(id % 3);
            assertEquals(want, title, "response " + id);
        }
        assertFalse(responses.isEmpty());
    }
}
