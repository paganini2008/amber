package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol shapes of the rule catalogue ({@code engine.rules}), rule settings on every method that computes
 * findings, the findings' optimized SQL and {@code rewrite.optimize}.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class RulesMethodsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, JsonRpcServer.Handler> HANDLERS = Methods.handlers(MAPPER);
    private static final String META = "\"metadata\":[{\"schema\":\"sales\",\"name\":\"orders\",\"kind\":\"TABLE\","
            + "\"columns\":[{\"name\":\"id\",\"type\":\"number\",\"nullable\":false},"
            + "{\"name\":\"status\",\"type\":\"varchar2(10)\",\"nullable\":true}],"
            + "\"primaryKey\":[\"id\"],\"rowEstimate\":-1}]";

    private static JsonNode call(String method, String params) throws Exception {
        Object r = HANDLERS.get(method).handle(params == null ? null : MAPPER.readTree(params));
        return MAPPER.valueToTree(r);
    }

    private static JsonNode finding(JsonNode findings, String rule) {
        for (JsonNode f : findings) {
            if (f.get("rule").asText().equals(rule)) {
                return f;
            }
        }
        return null;
    }

    @Test
    void ruleCatalogue() throws Exception {
        JsonNode rules = call("engine.rules", null).get("rules");
        assertEquals(27, rules.size());
        JsonNode p001 = rules.get(18);
        assertEquals("AMB-P001", p001.get("id").asText());
        assertEquals("INFO", p001.get("severity").asText());
        assertEquals("performance", p001.get("category").asText());
        assertTrue(p001.get("needsMetadata").asBoolean());
        assertFalse(p001.get("hasQuickFix").asBoolean());
        assertTrue(p001.has("title") && p001.has("description"));
    }

    @Test
    void analyzeWithRulesAndSuggestedSql() throws Exception {
        String text = "select distinct status, count(*) from sales.orders group by status;\\n"
                + "select * from sales.orders where status = 'NEW' union select * from sales.orders where id = 1;";
        JsonNode all = call("analyze", "{\"dialect\":\"oracle\",\"text\":\"" + text + "\"," + META + "}").get("findings");
        JsonNode s011 = finding(all, "AMB-S011");
        assertEquals("select status, count(*) from sales.orders group by status", s011.get("suggestedSql").asText());
        assertEquals("REWRITE_OF_STATEMENT", s011.get("suggestedSqlKind").asText());
        assertEquals("R1", s011.get("rewriteId").asText(), s011.toString());
        assertTrue(s011.get("suggestedSqlNote").asText().startsWith("Equivalent"));
        JsonNode p001 = finding(all, "AMB-P001");
        assertEquals("CREATE INDEX ix_orders_status ON sales.orders (status)", p001.get("suggestedSql").asText());
        assertEquals("ADDITIONAL_DDL", p001.get("suggestedSqlKind").asText());
        assertEquals("NOT_CHECKED", p001.get("verified").asText());
        assertTrue(finding(all, "AMB-S009").has("suggestedSql"));
        JsonNode configured = call("analyze", "{\"dialect\":\"oracle\",\"text\":\"" + text + "\"," + META
                + ",\"rules\":{\"disabled\":[\"AMB-S009\"],\"severity\":{\"AMB-P001\":\"low\"}}}").get("findings");
        assertEquals(null, finding(configured, "AMB-S009"));
        assertEquals("LOW", finding(configured, "AMB-P001").get("severity").asText());
        Exception e = assertThrows(IllegalArgumentException.class, () -> call("analyze",
                "{\"dialect\":\"oracle\",\"text\":\"select 1 from dual;\",\"rules\":{\"severity\":{\"AMB-S001\":\"X\"}}}"));
        assertTrue(e.getMessage().contains("Unknown severity"));
    }

    @Test
    void rulesOnTheOtherMethods() throws Exception {
        String text = "select a from t union select a from u;";
        String off = ",\"rules\":{\"disabled\":[\"AMB-S009\"]}";
        assertEquals(1, call("rewrite.suggest", "{\"dialect\":\"oracle\",\"text\":\"" + text + "\"}")
                .get("rewrites").size());
        assertEquals(0, call("rewrite.suggest", "{\"dialect\":\"oracle\",\"text\":\"" + text + "\"" + off + "}")
                .get("rewrites").size());
        String doc = call("doc.generate", "{\"dialect\":\"oracle\",\"text\":\"" + text + "\"" + off + "}")
                .get("text").asText();
        assertFalse(doc.contains("AMB-S009"), doc);
        JsonNode diff = call("diff", "{\"dialect\":\"oracle\",\"before\":{\"text\":\"select a from t;\"},"
                + "\"after\":{\"text\":\"" + text + "\"}" + off + "}");
        assertEquals(0, diff.get("findings").size(), diff.get("findings").toString());
        JsonNode ctx = call("ai.buildContext", "{\"dialect\":\"oracle\",\"text\":\"" + text + "\"" + off + "}");
        assertFalse(ctx.get("user").asText().contains("AMB-S009"));
    }

    @Test
    void optimize() throws Exception {
        String text = "select distinct status, count(*) from sales.orders group by status;\\n"
                + "select id from sales.orders where status = 'A' union select id from sales.orders where id = 2;\\n"
                + "select 1 from dual;";
        JsonNode r = call("rewrite.optimize", "{\"dialect\":\"oracle\",\"text\":\"" + text + "\"," + META + "}");
        JsonNode st = r.get("statements");
        assertEquals(2, st.size(), r.toString());
        JsonNode first = st.get(0);
        assertEquals(0, first.get("statement").asInt());
        assertEquals("select status, count(*) from sales.orders group by status", first.get("optimized").asText());
        assertEquals("AMB-S011", first.get("applied").get(0).get("rule").asText());
        assertEquals("EQUIVALENT", first.get("safety").asText());
        assertEquals("STRUCTURE_CHANGED", first.get("check").get("label").asText());
        assertEquals(0, first.get("additionalDdl").size());
        JsonNode second = st.get(1);
        assertTrue(second.get("additionalDdl").get(0).asText().startsWith("CREATE INDEX ix_orders_status"));
        assertEquals(0, second.get("applied").size());
        assertEquals("EDGE_CASE", second.get("skipped").get(0).get("reason").asText());
        assertTrue(r.get("optimizedText").asText().startsWith("select status, count(*)"));
        JsonNode edge = call("rewrite.optimize", "{\"dialect\":\"oracle\",\"text\":\"" + text
                + "\",\"includeEdgeCases\":true,\"statements\":[1],\"rules\":{\"disabled\":[\"AMB-P001\"]}}");
        assertEquals(1, edge.get("statements").size());
        assertTrue(edge.get("optimizedText").asText().contains("union all"));
        assertThrows(IllegalArgumentException.class, () -> call("rewrite.optimize", "{\"text\":\"x\"}"));
    }
}
