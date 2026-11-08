package com.github.amber.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions found by the robustness pass (mutation fuzzing and scale tests), checked through the
 * server's methods like the app calls them.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class RobustnessRegressionTest {

    private static final Duration WATCHDOG = Duration.ofSeconds(60);

    private static JsonNode analyze(Dialect d, String text, boolean fallback) {
        Robust.Outcome o = Robust.call("analyze", Robust.params(d, text, fallback), WATCHDOG);
        assertTrue(o.ok(), () -> o.hung() ? "hang" : Robust.describe(o.error()));
        assertNull(Robust.checkRanges(o.json(), Robust.Texts.of(text)));
        assertNull(Robust.checkAnalysis(o.json()));
        return o.json();
    }

    /** Fuzzer seed 20199832730319 of 91_etl_scd2_procedures.sql: NPE in the grammar's routine-body helper. */
    @Test
    void brokenRoutineBodyDoesNotThrow() throws Exception {
        String original = Files.readString(Path.of("src/test/resources/corpus/postgresql/conformance/91_etl_scd2_procedures.sql"));
        String mutant = Mutator.mutate(original, 20199832730319L).text();
        analyze(Dialect.POSTGRESQL, mutant, false);
        analyze(Dialect.POSTGRESQL, mutant, true);
        analyze(Dialect.POSTGRESQL, "CREATE FUNCTION f() RETURNS int AS LANGUAGE sql;\nSELECT 1;\n", true);
    }

    /** PL/pgSQL IF, CASE, LOOP, blocks and handlers used to cover only their first keyword. */
    @Test
    void plpgsqlContainersCoverTheirBodies() {
        String text = """
                CREATE FUNCTION f(n int) RETURNS int AS $$
                DECLARE i int := 0;
                BEGIN
                  IF n > 0 THEN
                    UPDATE t SET a = 1;
                  ELSE
                    DELETE FROM t;
                  END IF;
                  FOR r IN SELECT a FROM t LOOP
                    INSERT INTO u VALUES (r.a);
                  END LOOP;
                  CASE n WHEN 1 THEN UPDATE t SET a = 2; ELSE NULL; END CASE;
                  BEGIN
                    INSERT INTO u VALUES (1);
                  EXCEPTION WHEN others THEN
                    INSERT INTO log VALUES (2);
                  END;
                  RETURN i;
                END$$ LANGUAGE plpgsql;
                """;
        JsonNode r = analyze(Dialect.POSTGRESQL, text, false);
        JsonNode body = r.path("statements").get(0).path("children").get(0);
        StringBuilder kinds = new StringBuilder();
        for (JsonNode c : body.path("children")) {
            int s = c.path("range").path("start").path("offset").asInt();
            int e = c.path("range").path("end").path("offset").asInt();
            String slice = text.substring(s, e);
            kinds.append(c.path("kind").asText()).append(' ');
            switch (c.path("kind").asText()) {
                case "IF" -> assertTrue(slice.endsWith("END IF;"), slice);
                case "LOOP" -> assertTrue(slice.endsWith("END LOOP;"), slice);
                case "CASE" -> assertTrue(slice.endsWith("END CASE;"), slice);
                case "BLOCK" -> assertTrue(slice.endsWith("END;") && slice.contains("EXCEPTION"), slice);
                default -> {
                }
            }
        }
        assertTrue(kinds.toString().contains("IF LOOP CASE BLOCK"), kinds::toString);
    }

    /** U+2028, U+2029 and U+0085 used to become line breaks for the parser, shifting every later line. */
    @Test
    void unicodeLineSeparatorsDoNotShiftLines() {
        for (String sep : new String[]{" ", " ", "\u0085"}) {
            String text = "SELECT a" + sep + " FROM t;\nUPDATE t SET a = 1;\n";
            JsonNode r = analyze(Dialect.SQLSERVER, text, true);
            JsonNode update = r.path("statements").get(r.path("statements").size() - 1);
            assertEquals(2, update.path("range").path("start").path("line").asInt());
            assertEquals(2, SourceText.of(text).lineCount() - 1);
        }
    }

    /** A FROM list of 16,000 tables made alias lookup quadratic (seconds per statement). */
    @Test
    void longFromListIsLinear() {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM t0");
        for (int i = 1; i < 16_000; i++) {
            sql.append(", a").append(i % 10);
        }
        sql.append(";\n");
        long t0 = System.nanoTime();
        analyze(Dialect.MARIADB, sql.toString(), true);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 4_000, ms + " ms");
    }
}
