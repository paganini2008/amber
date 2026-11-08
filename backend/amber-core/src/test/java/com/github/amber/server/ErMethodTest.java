package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol shape of {@code er.model}: entities, relationships, Mermaid text, stats; metadata params,
 * data-flow and name-match switches, fallback parsing and errors.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ErMethodTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, JsonRpcServer.Handler> HANDLERS = Methods.handlers(MAPPER);

    private static JsonNode call(String params) throws Exception {
        Object r = HANDLERS.get("er.model").handle(MAPPER.readTree(params));
        return MAPPER.readTree(MAPPER.writeValueAsString(r));
    }

    @Test
    void resultShape() throws Exception {
        JsonNode r = call("""
                {"dialect":"oracle","text":"select o.id from orders o join customers c on c.id = o.customer_id;\\ninsert into hist select * from orders;",
                 "metadata":[{"name":"customers","kind":"TABLE","columns":[{"name":"id","type":"NUMBER","nullable":false}],
                              "primaryKey":["id"],"rowEstimate":-1,"source":"pg-prod"}],
                 "includeDataFlow":true}
                """);
        JsonNode customers = r.get("entities").get(1);
        assertEquals("CUSTOMERS", customers.get("key").asText());
        assertEquals("customers", customers.get("name").asText());
        assertEquals("TABLE", customers.get("kind").asText());
        assertFalse(customers.get("createdInScript").asBoolean());
        assertEquals("pg-prod", customers.get("metadataSource").asText());
        JsonNode id = customers.get("columns").get(0);
        assertEquals("id", id.get("name").asText());
        assertEquals("NUMBER", id.get("type").asText());
        assertFalse(id.get("nullable").asBoolean());
        assertTrue(id.get("pk").asBoolean());
        assertTrue(id.get("unique").asBoolean());
        assertTrue(id.get("joined").asBoolean());
        assertEquals("METADATA", id.get("source").asText());

        JsonNode rel = r.get("relationships").get(0);
        assertEquals("R1", rel.get("id").asText());
        assertEquals("ORDERS", rel.get("from").get("entity").asText());
        assertEquals("customer_id", rel.get("from").get("columns").get(0).asText());
        assertEquals("CUSTOMERS", rel.get("to").get("entity").asText());
        assertEquals("MANY_TO_ONE", rel.get("cardinality").asText());
        assertEquals("JOIN", rel.get("evidence").asText());
        assertEquals("INFERRED", rel.get("confidence").asText());
        assertEquals(1, rel.get("count").asInt());
        JsonNode occ = rel.get("occurrences").get(0);
        assertEquals(0, occ.get("statement").asInt());
        assertEquals(1, occ.get("range").get("start").get("line").asInt());
        assertTrue(occ.get("range").get("start").has("offset") && occ.get("range").get("end").has("column"));
        assertTrue(r.get("relationships").toString().contains("DATA_FLOW"));
        assertTrue(r.get("mermaid").asText().startsWith("erDiagram"));
        assertEquals(2, r.get("stats").get("statements").asInt());
        assertEquals(1, r.get("stats").get("joinEqualities").asInt());
    }

    @Test
    void switchesFilesAndErrors(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("s.sql");
        Files.writeString(script, "create table customers (id number primary key);\n"
                + "create table orders (id number primary key, customer_id number);\n");
        JsonNode on = call("{\"dialect\":\"oracle\",\"path\":" + MAPPER.writeValueAsString(script.toString()) + "}");
        assertEquals("NAME_MATCH", on.get("relationships").get(0).get("evidence").asText());
        JsonNode off = call("{\"dialect\":\"oracle\",\"path\":" + MAPPER.writeValueAsString(script.toString())
                + ",\"includeNameMatch\":false}");
        assertEquals(0, off.get("relationships").size());

        JsonNode fallback = call("{\"dialect\":\"oracle\",\"allowFallback\":true,"
                + "\"text\":\"select * from a x join b y on x.k = y.k;\\nselect from where;\\n\"}");
        assertEquals(1, fallback.get("relationships").size());
        assertThrows(IllegalArgumentException.class, () -> call("{\"dialect\":\"oracle\",\"text\":\"select from where;\"}"));
        assertThrows(IllegalArgumentException.class, () -> call("{\"text\":\"select 1 from dual;\"}"));
    }
}
