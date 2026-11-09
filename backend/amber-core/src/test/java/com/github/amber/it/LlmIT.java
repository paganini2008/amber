package com.github.amber.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.amber.ai.AiCheck;
import com.github.amber.ai.AiContextBuilder;
import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.config.Env;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Optional real round trip to an OpenAI-compatible API, the way the app does it: Amber builds a masked
 * context from its analysis, the model answers, and every rewritten SQL goes through {@code ai.check}.
 * OpenAI case: needs {@code OPENAI_API_KEY} (environment or backend/.env); optional {@code AMBER_IT_OPENAI_BASE_URL}
 * and {@code AMBER_IT_OPENAI_MODEL}. Only synthetic test SQL, masked, is sent.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class LlmIT {

    private static final String SQL = """
            select * from (select m.member_id, m.display_name, f.amount
                             from lib.members m join lib.member_fees f on f.member_id = m.member_id
                            where f.period = '2026-10'
                              and m.member_id not in (select member_id from lib.fee_waivers)
                            order by f.amount desc)
             where rownum <= 20;
            """;

    /** OpenAI-compatible endpoint ({@code /chat/completions}), as the desktop app calls it. */
    @Test
    void openAiMaskedContextAnswerAndCheck() throws Exception {
        Env env = Env.get();
        String key = env.value("OPENAI_API_KEY");
        Assumptions.assumeTrue(key != null, "OPENAI_API_KEY is not set; skipped");
        String base = env.value("AMBER_IT_OPENAI_BASE_URL", "https://api.openai.com/v1").replaceAll("/+$", "");
        String model = env.value("AMBER_IT_OPENAI_MODEL", "gpt-4.1-mini");
        roundTrip(model, (json, body) -> {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertOk(response);
            return json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        });
    }

    /**
     * Ollama's native {@code /api/chat}, as the desktop app calls it. {@code AMBER_IT_OLLAMA_URL} (default
     * http://localhost:11434) and {@code AMBER_IT_OLLAMA_MODEL} (default: the first chat model the server lists).
     * Skipped when no Ollama answers.
     */
    @Test
    void ollamaMaskedContextAnswerAndCheck() throws Exception {
        Env env = Env.get();
        String base = env.value("AMBER_IT_OLLAMA_URL", "http://localhost:11434").replaceAll("/+$", "");
        ObjectMapper json = new ObjectMapper();
        JsonNode tags;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/tags")).timeout(Duration.ofSeconds(5))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            Assumptions.assumeTrue(r.statusCode() == 200, "Ollama at " + base + " answered " + r.statusCode() + "; skipped");
            tags = json.readTree(r.body()).path("models");
        } catch (IOException e) {
            Assumptions.abort("no Ollama at " + base + "; skipped");
            return;
        }
        String model = env.value("AMBER_IT_OLLAMA_MODEL");
        if (model == null) {
            for (JsonNode m : tags) {
                String name = m.path("name").asText();
                if (!name.contains("embed")) {
                    model = name;
                    break;
                }
            }
        }
        Assumptions.assumeTrue(model != null, "Ollama at " + base + " has no chat model; skipped");
        roundTrip(model, (mapper, body) -> {
            ObjectNode request = (ObjectNode) mapper.readTree(body);
            request.put("stream", false);
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                    .timeout(Duration.ofMinutes(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertOk(response);
            return mapper.readTree(response.body()).path("message").path("content").asText();
        });
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Sends a chat request body and returns the model's answer text. */
    private interface Chat {
        String send(ObjectMapper json, String body) throws Exception;
    }

    private static void assertOk(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), () -> "provider answered " + response.statusCode() + ": "
                + response.body().substring(0, Math.min(300, response.body().length())));
    }

    /** Masked context from the engine, one answer from the model, every suggested SQL checked by the engine. */
    private static void roundTrip(String model, Chat chat) throws Exception {
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        Script script = ScriptIrBuilder.build(f, f.parse(SourceText.of(SQL)));
        List<Statement> steps = Lifecycles.steps(script);
        Catalog catalog = Catalog.of(List.of());
        var ctx = AiContextBuilder.build(script, steps, List.of(0), Findings.of(script, catalog), catalog, 4000, true,
                "Keep the top-20 semantics; lib.member_fees has about 5 million rows.");
        for (String real : List.of("member_fees", "fee_waivers", "display_name", "2026-10")) {
            assertFalse(ctx.user().contains(real), () -> "real name leaked into the prompt: " + real);
        }

        ObjectMapper json = new ObjectMapper();
        String body = json.writeValueAsString(Map.of("model", model, "temperature", 0.2, "messages", List.of(
                Map.of("role", "system", "content", ctx.system()), Map.of("role", "user", "content", ctx.user()))));
        String answer = chat.send(json, body);
        System.out.println("[LlmIT] " + model + " answered " + answer.length() + " characters");
        assertFalse(answer.isBlank(), model + " answered nothing");

        JsonNode parsed = firstJson(json, answer);
        Assumptions.assumeTrue(parsed != null, "the model did not answer with JSON; nothing to check");
        JsonNode list = parsed.isArray() ? parsed : parsed.path("suggestions");
        for (JsonNode s : list) {
            String sql = s.path("rewrittenSql").asText("");
            if (sql.isBlank()) {
                continue;
            }
            var r = AiCheck.check(Dialect.ORACLE, SQL, sql, ctx.mask(), catalog);
            System.out.println("[LlmIT] " + model + ": " + s.path("title").asText() + " -> " + r.label() + " " + r.differences());
            assertFalse(r.sql().contains("T1.") && ctx.mask().containsKey("T1"), "masked names are restored");
        }
    }

    /** The first complete JSON object or array in the text (models add prose, fences or think blocks). */
    private static JsonNode firstJson(ObjectMapper json, String text) {
        String t = text.replaceAll("(?s)<think>.*?</think>", "");
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{' || c == '[') {
                try {
                    return json.reader().readTree(json.getFactory().createParser(t.substring(i)));
                } catch (Exception e) {
                    // not JSON from here; try the next opening bracket
                }
            }
        }
        return null;
    }
}
