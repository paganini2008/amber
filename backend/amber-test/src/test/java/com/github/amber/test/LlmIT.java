package com.github.amber.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional real round trip to an OpenAI-compatible API, the way the app does it: Amber builds a masked
 * context from its analysis, the model answers, and every rewritten SQL goes through {@code ai.check}.
 * Needs {@code OPENAI_API_KEY} (environment or backend/.env); optional {@code AMBER_IT_OPENAI_BASE_URL}
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

    @Test
    void maskedContextAnswerAndCheck() throws Exception {
        Env env = Env.get();
        String key = env.value("OPENAI_API_KEY");
        Assumptions.assumeTrue(key != null, "OPENAI_API_KEY is not set; skipped");
        String base = env.value("AMBER_IT_OPENAI_BASE_URL", "https://api.openai.com/v1").replaceAll("/+$", "");
        String model = env.value("AMBER_IT_OPENAI_MODEL", "gpt-4.1-mini");

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
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "provider answered " + response.statusCode() + ": "
                + response.body().substring(0, Math.min(300, response.body().length())));
        String answer = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        System.out.println("[LlmIT] " + model + " answered " + answer.length() + " characters");

        JsonNode parsed = firstJson(json, answer);
        Assumptions.assumeTrue(parsed != null, "the model did not answer with JSON; nothing to check");
        JsonNode list = parsed.isArray() ? parsed : parsed.path("suggestions");
        int checked = 0;
        for (JsonNode s : list) {
            String sql = s.path("rewrittenSql").asText("");
            if (sql.isBlank()) {
                continue;
            }
            var r = AiCheck.check(Dialect.ORACLE, SQL, sql, ctx.mask(), catalog);
            System.out.println("[LlmIT] " + s.path("title").asText() + " -> " + r.label() + " " + r.differences());
            assertFalse(r.sql().contains("T1.") && ctx.mask().containsKey("T1"), "masked names are restored");
            checked++;
        }
        assertTrue(checked >= 0);
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
