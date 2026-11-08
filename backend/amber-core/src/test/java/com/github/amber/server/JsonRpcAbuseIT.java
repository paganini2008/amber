package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 50 MB request lines: garbage, a cut-off request, and valid requests carrying a 50 MB script. Each gets one
 * proper answer (with its id whenever the line has one) and the server keeps serving. Runs in
 * {@code mvn verify} (failsafe) because it needs a few hundred MB of heap.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class JsonRpcAbuseIT {

    private static final int SIZE = 50 * 1024 * 1024;

    @Test
    void fiftyMegabyteLines() throws Exception {
        try (JsonRpcAbuseTest.Session s = new JsonRpcAbuseTest.Session()) {
            String filler = "x".repeat(SIZE);
            s.send(filler);                                                               // not JSON at all
            s.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"text\":\""
                    + filler);                                                            // cut off
            String script = "-- " + filler + "\nselect a from t;\n";
            s.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"analyze\",\"params\":{\"dialect\":\"oracle\",\"text\":\""
                    + JsonRpcAbuseTest.esc(script) + "\"}}");
            s.send("{\"jsonrpc\":\"2.0\",\"method\":\"rewrite.apply\",\"params\":{\"text\":\"" + filler
                    + "\",\"edits\":[{\"start\":0,\"end\":1,\"replacement\":\"y\"}]},\"id\":3}");   // id after the text
            s.send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"engine.info\"}");
            s.await(5, 300);
            Thread.sleep(200);
            assertEquals(5, s.answers.size(), s.answers::toString);
            long parseErrors = s.answers.stream().filter(a -> a.path("error").path("code").asInt() == -32700).count();
            assertEquals(2, parseErrors, s.answers::toString);
            assertEquals(-32700, s.byId(1).path("error").path("code").asInt());
            JsonNode analysis = s.byId(2).path("result");
            assertTrue(analysis.has("large") || analysis.path("status").asText().equals("SUCCESS"), analysis::toString);
            assertTrue(s.byId(3).has("result"), () -> s.byId(3).toString());
            assertEquals("Amber", s.byId(4).path("result").path("name").asText());
        }
    }
}
