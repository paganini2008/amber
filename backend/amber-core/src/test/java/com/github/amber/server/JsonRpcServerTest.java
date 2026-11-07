package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the server through stdin/stdout like the desktop app does.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class JsonRpcServerTest {

    private final ObjectMapper mapper = Main.mapper();

    private List<JsonNode> exchange(String... lines) throws Exception {
        String input = String.join("\n", lines) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonRpcServer(mapper, Methods.handlers(mapper), new PrintStream(new ByteArrayOutputStream()))
                .serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        // serve() returns when input ends; give in-flight requests a moment to answer
        List<JsonNode> responses = new ArrayList<>();
        for (int i = 0; i < 100 && responses.size() < expectedResponses(lines); i++) {
            responses.clear();
            for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
                if (!l.isBlank()) {
                    responses.add(mapper.readTree(l));
                }
            }
            Thread.sleep(50);
        }
        return responses;
    }

    private static int expectedResponses(String[] lines) {
        int n = 0;
        for (String l : lines) {
            if (!l.contains("$/cancel")) {
                n++;
            }
        }
        return n;
    }

    private static JsonNode byId(List<JsonNode> responses, int id) {
        return responses.stream().filter(r -> r.path("id").asInt(-1) == id).findFirst()
                .orElseThrow(() -> new AssertionError("no response " + id + " in " + responses));
    }

    @Test
    void answersInfoAndAnalyze() throws Exception {
        List<JsonNode> responses = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"engine.info\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"name\":\"a.sql\","
                        + "\"text\":\"create table t2 as select a from t1;\\ndrop table t1;\"}}");
        JsonNode info = byId(responses, 1).get("result");
        assertEquals("Amber", info.get("name").asText());
        assertEquals(8, info.get("dialects").size());

        JsonNode result = byId(responses, 2).get("result");
        assertEquals("SUCCESS", result.get("status").asText());
        assertEquals(2, result.get("statements").size());
        assertEquals("CREATE_TABLE_AS", result.get("statements").get(0).get("kind").asText());
        assertEquals("Create table t2 from t1", result.get("statements").get(0).get("summary").asText());
        JsonNode t1 = result.get("lifecycles").get(1);
        assertEquals("T1", t1.get("key").asText());
        assertEquals("SOURCE", t1.get("role").asText());
        assertEquals(2, t1.get("events").size());
    }

    @Test
    void strictFailureReturnsDiagnosticsOnly() throws Exception {
        JsonNode result = byId(exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"analyze\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"select from where;\"}}"), 7).get("result");
        assertEquals("FAILED", result.get("status").asText());
        assertEquals(0, result.get("statements").size());
        assertTrue(result.get("diagnostics").size() > 0);
        assertEquals(1, result.get("diagnostics").get(0).get("range").get("start").get("line").asInt());
    }

    @Test
    void reportsProtocolErrors() throws Exception {
        List<JsonNode> responses = exchange(
                "not json",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"nope\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"analyze\",\"params\":{\"dialect\":\"klingon\",\"text\":\"x\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"analyze\"}",
                "[1,2]",
                "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":{\"id\":99}}");
        assertEquals(JsonRpcServer.METHOD_NOT_FOUND, byId(responses, 3).get("error").get("code").asInt());
        assertEquals(JsonRpcServer.INVALID_PARAMS, byId(responses, 4).get("error").get("code").asInt());
        assertEquals(JsonRpcServer.INVALID_PARAMS, byId(responses, 5).get("error").get("code").asInt());
        assertTrue(responses.stream().anyMatch(r -> r.path("error").path("code").asInt() == JsonRpcServer.PARSE_ERROR));
        assertTrue(responses.stream().anyMatch(r -> r.path("error").path("code").asInt() == JsonRpcServer.INVALID_REQUEST));
    }

    @Test
    void aiContextAndCheck() throws Exception {
        List<JsonNode> responses = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ai.buildContext\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"select a from orders where b = 'x';\",\"tokenBudget\":2000}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ai.check\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"original\":\"select a from orders\",\"rewritten\":\"select C1 from T1\","
                        + "\"mask\":{\"T1\":\"orders\",\"C1\":\"a\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ai.buildContext\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"select a from orders;\",\"statements\":[5]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ai.check\",\"params\":{\"dialect\":\"postgresql\"}}");
        JsonNode ctx = byId(responses, 1).get("result");
        assertTrue(ctx.get("system").asText().contains("JSON"));
        assertTrue(!ctx.get("user").asText().contains("orders"), ctx.toString());
        assertTrue(ctx.get("mask").size() > 0);
        JsonNode check = byId(responses, 2).get("result");
        assertEquals("STRUCTURE_PRESERVED", check.get("label").asText(), check.toString());
        assertEquals("select a from orders", check.get("sql").asText());
        assertEquals(-32602, byId(responses, 3).get("error").get("code").asInt());
        assertEquals(-32602, byId(responses, 4).get("error").get("code").asInt());
        List<JsonNode> more = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ai.maskText\",\"params\":{\"text\":\"Is orders.amount nullable?\","
                        + "\"mask\":{\"T1\":\"orders\",\"C1\":\"amount\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ai.buildContext\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"select a from orders;\",\"notes\":\"orders is huge\"}}");
        assertEquals("Is T1.C1 nullable?", byId(more, 5).get("result").get("text").asText());
        String user = byId(more, 6).get("result").get("user").asText();
        assertTrue(user.contains("Notes from the developer") && !user.contains("orders is huge"), user);
    }

    @Test
    void referencesAndHandoverDoc() throws Exception {
        String text = "create table t1 (a int);\\ninsert into t1 select a from src;\\nselect a from t1;";
        List<JsonNode> r = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"references\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"" + text + "\",\"object\":\"T1\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"references\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"" + text + "\",\"table\":\"src\",\"column\":\"a\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"doc.generate\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"name\":\"n.sql\",\"text\":\"" + text + "\",\"format\":\"html\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"references\",\"params\":{\"dialect\":\"postgresql\",\"text\":\"select 1\"}}");
        JsonNode obj = byId(r, 1).get("result");
        assertEquals(3, obj.get("occurrences").size(), obj.toString());
        assertEquals(1, obj.get("definition").get("range").get("start").get("line").asInt());
        assertEquals(1, byId(r, 2).get("result").get("occurrences").size());
        JsonNode doc = byId(r, 3).get("result");
        assertEquals("html", doc.get("format").asText());
        assertTrue(doc.get("text").asText().contains("n.sql — handover notes"));
        assertEquals(-32602, byId(r, 4).get("error").get("code").asInt());
    }

    @Test
    void formatDiffAndRewrites() throws Exception {
        List<JsonNode> r = exchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"format\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"select a,b from t where x=1 and y=2;\",\"keywordCase\":\"upper\",\"indentSize\":2}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"diff\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"before\":{\"text\":\"delete from t where k = 1;\"},\"after\":{\"text\":\"delete from t;\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"rewrite.suggest\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"select v.id from v left join p on p.id = v.pid where p.kind = 'x';\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"rewrite.apply\",\"params\":{\"text\":\"abc\","
                        + "\"edits\":[{\"start\":1,\"end\":2,\"replacement\":\"X\"}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"format\",\"params\":{\"dialect\":\"postgresql\","
                        + "\"text\":\"select 1; select 2;\",\"start\":0,\"end\":3}}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"diff\",\"params\":{\"dialect\":\"postgresql\"}}");
        JsonNode f = byId(r, 1).get("result");
        assertTrue(f.get("changed").asBoolean());
        assertTrue(f.get("text").asText().startsWith("SELECT"), f.toString());
        assertEquals("HIGH", byId(r, 2).get("result").get("summary").get("risk").asText());
        JsonNode rw = byId(r, 3).get("result").get("rewrites");
        assertEquals("AMB-S002", rw.get(0).get("rule").asText(), rw.toString());
        assertEquals("EQUIVALENT", rw.get(0).get("safety").asText());
        assertEquals("aXc", byId(r, 4).get("result").get("text").asText());
        assertTrue(byId(r, 5).get("result").get("text").asText().endsWith("select 2;"), "only the first statement formatted");
        assertEquals(-32602, byId(r, 6).get("error").get("code").asInt());
    }
}
