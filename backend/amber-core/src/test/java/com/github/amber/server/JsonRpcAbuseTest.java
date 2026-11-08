package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abuse of the JSON-RPC server: malformed lines, wrong and missing params, unknown methods, many
 * concurrent requests, cancellation storms and input that ends while requests are in flight. Every
 * request with an id must get exactly one well-formed answer, and the server must keep serving.
 * The 50 MB line runs in {@link JsonRpcAbuseIT}.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class JsonRpcAbuseTest {

    private static final ObjectMapper MAPPER = Main.mapper();
    private static final String ORACLE = "INSERT INTO t2 (a) SELECT a FROM t1 WHERE b = 1;\nDROP TABLE t1;\n";

    /** A server on pipes: lines are written while it runs, answers collected as they come. */
    static final class Session implements AutoCloseable {
        final PipedOutputStream toServer = new PipedOutputStream();
        final List<JsonNode> answers = new CopyOnWriteArrayList<>();
        final Thread serving;
        final ByteArrayOutputStream log = new ByteArrayOutputStream();

        Session() throws IOException {
            PipedInputStream in = new PipedInputStream(toServer, 1 << 20);
            OutputStream out = new LineCollector(answers);
            JsonRpcServer server = new JsonRpcServer(MAPPER, Methods.handlers(MAPPER), new PrintStream(log, true));
            serving = new Thread(() -> {
                try {
                    server.serve(in, out);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, "abuse-server");
            serving.setDaemon(true);
            serving.start();
        }

        void send(String line) throws IOException {
            toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            toServer.flush();
        }

        /** Waits until {@code n} answers arrived (or fails after the timeout). */
        List<JsonNode> await(int n, long seconds) throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (answers.size() < n && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertTrue(answers.size() >= n, "expected " + n + " answers, got " + answers.size() + ": " + answers);
            return answers;
        }

        JsonNode byId(Object id) {
            String key = MAPPER.valueToTree(id).toString();
            List<JsonNode> found = answers.stream().filter(a -> a.path("id").toString().equals(key)).toList();
            assertEquals(1, found.size(), () -> "answers for id " + key + ": " + found);
            return found.get(0);
        }

        @Override
        public void close() throws IOException {
            toServer.close();
        }
    }

    /** Splits the server's output into lines and parses each; a line that is not JSON fails the test. */
    static final class LineCollector extends OutputStream {
        private final List<JsonNode> answers;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineCollector(List<JsonNode> answers) {
            this.answers = answers;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (b == '\n') {
                var answer = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(line.toString(StandardCharsets.UTF_8));
                if (line.size() > 100_000) {
                    answer.putObject("result").put("large", true);     // keep big analyses out of the heap
                }
                answers.add(answer);
                line.reset();
            } else {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }
    }

    private static int code(JsonNode answer) {
        return answer.path("error").path("code").asInt(0);
    }

    @Test
    void malformedLinesGetParseOrRequestErrorsAndServingContinues() throws Exception {
        String[] bad = {"{", "not json", "[1,2]", "\"text\"", "null", "42", "{}", "{\"jsonrpc\":\"2.0\"}",
                "{\"id\":7}", "{\"method\":null,\"id\":8}", "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"analyze\",\"params\":",
                "\u0000\u0001", "{\"id\":10,\"method\":\"analyze\",\"params\":{\"text\":\"\\ud800\"}}  trailing",
                "\uFEFF{\"id\":11,\"method\":\"engine.info\"}"};
        try (Session s = new Session()) {
            for (String line : bad) {
                s.send(line);
            }
            s.send("{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"engine.info\"}");
            List<JsonNode> answers = s.await(bad.length + 1, 30);
            assertEquals("Amber", s.byId(100).path("result").path("name").asText());
            for (JsonNode a : answers) {
                assertEquals("2.0", a.path("jsonrpc").asText());
                assertTrue(a.has("result") || a.has("error"), a::toString);
            }
            long parseErrors = answers.stream().filter(a -> code(a) == JsonRpcServer.PARSE_ERROR).count();
            long requestErrors = answers.stream().filter(a -> code(a) == JsonRpcServer.INVALID_REQUEST).count();
            assertTrue(parseErrors >= 4, answers::toString);
            assertTrue(requestErrors >= 5, answers::toString);
            // a request whose JSON is cut off still gets its id back, so the client can settle it
            assertEquals(JsonRpcServer.PARSE_ERROR, code(s.byId(9)));
        }
    }

    @Test
    void wrongParamsAreInvalidParamsNotInternalErrors() throws Exception {
        Map<Integer, String> calls = new HashMap<>();
        calls.put(1, "{\"method\":\"analyze\"}");
        calls.put(2, "{\"method\":\"analyze\",\"params\":{}}");
        calls.put(3, "{\"method\":\"analyze\",\"params\":{\"dialect\":\"klingon\",\"text\":\"x\"}}");
        calls.put(4, "{\"method\":\"analyze\",\"params\":{\"dialect\":5,\"text\":\"x\"}}");
        calls.put(5, "{\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"text\":{\"a\":1}}}");
        calls.put(6, "{\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"allowFallback\":\"perhaps\",\"text\":\"x\"}}");
        calls.put(7, "{\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"path\":\"/no/such/file.sql\"}}");
        calls.put(8, "{\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"path\":\"/no/such/file.sql\",\"encoding\":\"no-such-charset\"}}");
        calls.put(9, "{\"method\":\"analyze\",\"params\":[1,2,3]}");
        calls.put(10, "{\"method\":\"analyze\",\"params\":\"oracle\"}");
        calls.put(11, "{\"method\":\"references\",\"params\":{\"dialect\":\"oracle\",\"text\":\"" + esc(ORACLE) + "\"}}");
        calls.put(12, "{\"method\":\"ai.buildContext\",\"params\":{\"dialect\":\"oracle\",\"text\":\"" + esc(ORACLE)
                + "\",\"statements\":[null]}}");
        calls.put(13, "{\"method\":\"ai.buildContext\",\"params\":{\"dialect\":\"oracle\",\"text\":\"" + esc(ORACLE)
                + "\",\"statements\":[99]}}");
        calls.put(14, "{\"method\":\"ai.buildContext\",\"params\":{\"dialect\":\"oracle\",\"text\":\"" + esc(ORACLE)
                + "\",\"tokenBudget\":1e12}}");
        calls.put(15, "{\"method\":\"ai.check\",\"params\":{}}");
        calls.put(16, "{\"method\":\"ai.maskText\",\"params\":{\"mask\":{\"T1\":null}}}");
        calls.put(17, "{\"method\":\"rewrite.apply\",\"params\":{\"text\":\"abc\",\"edits\":[{\"start\":-1,\"end\":2}]}}");
        calls.put(18, "{\"method\":\"rewrite.apply\",\"params\":{\"text\":\"abc\",\"edits\":[{\"start\":2,\"end\":99}]}}");
        calls.put(19, "{\"method\":\"rewrite.apply\",\"params\":{\"text\":\"abc\",\"edits\":[{\"start\":2,\"end\":1}]}}");
        calls.put(20, "{\"method\":\"format\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select 1 from dual;\",\"start\":5,\"end\":500}}");
        calls.put(21, "{\"method\":\"format\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select 1 from dual;\",\"keywordCase\":\"SHOUTING\"}}");
        calls.put(22, "{\"method\":\"format\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select 1 from dual;\",\"indentSize\":-4}}");
        calls.put(23, "{\"method\":\"diff\",\"params\":{\"dialect\":\"oracle\",\"before\":{},\"after\":{\"text\":\"x\"}}}");
        calls.put(24, "{\"method\":\"diff\",\"params\":{\"dialect\":\"oracle\",\"before\":\"x\",\"after\":\"y\"}}");
        calls.put(25, "{\"method\":\"doc.generate\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select 1 from dual;\","
                + "\"save\":\"/no/such/dir/doc.md\"}}");
        calls.put(26, "{\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select 1 from dual;\","
                + "\"metadataPacks\":[\"/no/such/pack.json\"]}}");
        calls.put(27, "{\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select a from t;\","
                + "\"metadata\":[{\"name\":null},{\"columns\":[null]},{}]}}");
        calls.put(28, "{\"method\":\"metadata.import\",\"params\":{\"dialect\":\"oracle\",\"packs\":[\"/no/such/pack.json\"]}}");
        calls.put(29, "{\"method\":\"db.test\",\"params\":{}}");
        calls.put(30, "{\"method\":\"db.loadMetadata\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select 1 from t\"}}");
        calls.put(31, "{\"method\":\"references\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select 1 from t;\",\"object\":\"   \"}}");
        calls.put(32, "{\"method\":\"format\",\"params\":{\"dialect\":\"oracle\",\"text\":null}}");
        try (Session s = new Session()) {
            for (var e : calls.entrySet()) {
                ObjectMapper m = MAPPER;
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(e.getValue());
                node.put("jsonrpc", "2.0");
                node.put("id", e.getKey());
                node.put("extra", "ignored");
                s.send(node.toString());
            }
            s.await(calls.size(), 60);
            List<String> internal = new ArrayList<>();
            for (int id : calls.keySet()) {
                JsonNode a = s.byId(id);
                if (code(a) == JsonRpcServer.INTERNAL_ERROR) {
                    internal.add(id + ": " + a.path("error").path("message").asText());
                }
                if (a.has("error")) {
                    assertFalse(a.path("error").path("message").asText().isBlank(), a::toString);
                }
            }
            assertTrue(internal.isEmpty(), "internal errors for bad params: " + internal);
            assertEquals(JsonRpcServer.INVALID_PARAMS, code(s.byId(7)));
            assertTrue(s.byId(7).path("error").path("message").asText().contains("/no/such/file.sql"));
            assertEquals(JsonRpcServer.INVALID_PARAMS, code(s.byId(17)));
            assertEquals(JsonRpcServer.INVALID_PARAMS, code(s.byId(25)));
        }
    }

    @Test
    void unknownMethodsAndNotifications() throws Exception {
        try (Session s = new Session()) {
            s.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"no.such\"}");
            s.send("{\"jsonrpc\":\"2.0\",\"method\":\"no.such.notification\"}");
            s.send("{\"jsonrpc\":\"2.0\",\"method\":\"analyze\"}");                       // failing notification: no answer
            s.send("{\"jsonrpc\":\"2.0\",\"method\":\"engine.info\"}");                   // notification: no answer
            s.send("{\"jsonrpc\":\"2.0\",\"id\":\"str-id\",\"method\":\"engine.info\"}");
            s.send("{\"jsonrpc\":\"2.0\",\"id\":2.5,\"method\":\"engine.info\"}");
            s.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"$/cancel\"}");
            s.send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"engine.info\",\"params\":null}");
            s.await(4, 30);
            Thread.sleep(300);
            assertEquals(JsonRpcServer.METHOD_NOT_FOUND, code(s.byId(1)));
            assertEquals("Amber", s.byId("str-id").path("result").path("name").asText());
            assertNotNull(s.byId(2.5).get("result"));
            assertNotNull(s.byId(4).get("result"));
            assertEquals(4, s.answers.size(), () -> "notifications must not be answered: " + s.answers);
        }
    }

    @Test
    void twoHundredConcurrentRequestsEachAnsweredOnce() throws Exception {
        try (Session s = new Session()) {
            String big = esc(ORACLE.repeat(200));
            for (int i = 0; i < 200; i++) {
                String method = switch (i % 5) {
                    case 0 -> "{\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"text\":\"" + big + "\"}}";
                    case 1 -> "{\"method\":\"format\",\"params\":{\"dialect\":\"oracle\",\"text\":\"" + big + "\"}}";
                    case 2 -> "{\"method\":\"engine.info\"}";
                    case 3 -> "{\"method\":\"doc.generate\",\"params\":{\"dialect\":\"sqlserver\",\"text\":\"select 1;\"}}";
                    default -> "{\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"text\":\"select from;\"}}";
                };
                s.send(method.replaceFirst("\\{", "{\"jsonrpc\":\"2.0\",\"id\":" + i + ","));
            }
            s.await(200, 120);
            Thread.sleep(200);
            assertEquals(200, s.answers.size());
            for (int i = 0; i < 200; i++) {
                JsonNode a = s.byId(i);
                assertTrue(a.has("result"), a::toString);
            }
        }
    }

    @Test
    void cancellationStormAnswersEveryRequestExactlyOnce() throws Exception {
        try (Session s = new Session()) {
            String slow = esc(ORACLE.repeat(500));
            int n = 60;
            for (int i = 0; i < n; i++) {
                s.send("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\","
                        + "\"text\":\"" + slow + "\"}}");
                if (i % 2 == 0) {
                    s.send("{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":{\"id\":" + i + "}}");
                    s.send("{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":{\"id\":" + i + "}}");   // twice
                }
            }
            String[] junk = {"{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":{\"id\":424242}}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":{\"id\":\"nope\"}}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":{\"id\":null}}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":{}}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":[1]}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\",\"params\":\"1\"}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"$/cancel\"}"};
            for (int k = 0; k < 50; k++) {
                s.send(junk[k % junk.length]);
            }
            s.send("{\"jsonrpc\":\"2.0\",\"id\":\"after\",\"method\":\"engine.info\"}");
            s.await(n + 1, 120);
            Thread.sleep(500);
            assertEquals(n + 1, s.answers.size(), () -> "answers: " + s.answers.size());
            for (int i = 0; i < n; i++) {
                JsonNode a = s.byId(i);
                if (i % 2 == 0) {
                    assertTrue(a.has("result") || code(a) == JsonRpcServer.CANCELLED, a::toString);
                } else {
                    assertTrue(a.has("result"), a::toString);
                }
            }
            assertNotNull(s.byId("after").get("result"));
            String log = s.log.toString(StandardCharsets.UTF_8);
            assertFalse(log.contains("Exception"), log);
        }
    }

    @Test
    void requestsInFlightWhenInputEndsAreStillAnswered() throws Exception {
        StringBuilder in = new StringBuilder();
        String text = esc(ORACLE.repeat(500));
        for (int i = 0; i < 20; i++) {
            in.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i).append(",\"method\":\"analyze\",\"params\":{\"dialect\":")
                    .append("\"oracle\",\"text\":\"").append(text).append("\"}}\n");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonRpcServer(MAPPER, Methods.handlers(MAPPER), new PrintStream(new ByteArrayOutputStream()))
                .serve(new ByteArrayInputStream(in.toString().getBytes(StandardCharsets.UTF_8)), out);
        // serve() must not return before the requests it accepted are answered: the engine exits right after it
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(20, lines.length);
        for (String l : lines) {
            assertTrue(MAPPER.readTree(l).has("result"), l);
        }
    }

    @Test
    void docSaveIntoMissingFolderIsInvalidParams(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        try (Session s = new Session()) {
            Path ok = dir.resolve("doc.md");
            s.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"doc.generate\",\"params\":{\"dialect\":\"oracle\","
                    + "\"text\":\"select 1 from dual;\",\"save\":" + MAPPER.writeValueAsString(ok.toString()) + "}}");
            s.await(1, 30);
            assertTrue(Files.exists(ok));
        }
    }

    /** A result that cannot be serialized, a StackOverflowError and an OutOfMemoryError: each answered -32603. */
    @Test
    void handlerFailuresAreAnsweredAsInternalErrors() throws Exception {
        java.util.Map<String, JsonRpcServer.Handler> handlers = new java.util.HashMap<>(Methods.handlers(MAPPER));
        handlers.put("test.selfReference", p -> new SelfReference());
        handlers.put("test.overflow", p -> {
            throw new StackOverflowError();
        });
        handlers.put("test.oom", p -> {
            throw new OutOfMemoryError("test");
        });
        StringBuilder in = new StringBuilder();
        String[] methods = {"test.selfReference", "test.overflow", "test.oom", "engine.info"};
        for (int i = 0; i < methods.length; i++) {
            in.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i).append(",\"method\":\"").append(methods[i]).append("\"}\n");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonRpcServer(MAPPER, handlers, new PrintStream(new ByteArrayOutputStream()))
                .serve(new ByteArrayInputStream(in.toString().getBytes(StandardCharsets.UTF_8)), out);
        Map<Integer, JsonNode> byId = new HashMap<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
            JsonNode a = MAPPER.readTree(l);
            assertNull(byId.put(a.path("id").asInt(), a), "answered twice: " + a);
        }
        assertEquals(4, byId.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(JsonRpcServer.INTERNAL_ERROR, code(byId.get(i)), byId.get(i)::toString);
        }
        assertTrue(byId.get(3).has("result"));
    }

    /** Jackson refuses to serialize an object that returns itself. */
    public static final class SelfReference {
        public SelfReference getSelf() {
            return this;
        }
    }

    static String esc(String s) {
        try {
            String q = MAPPER.writeValueAsString(s);
            return q.substring(1, q.length() - 1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
