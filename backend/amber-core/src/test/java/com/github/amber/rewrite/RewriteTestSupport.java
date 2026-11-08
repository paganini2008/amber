package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.TableMeta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Helpers shared by the rewrite tests: run the suggester, pick rewrites by rule, build metadata.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class RewriteTestSupport {

    private RewriteTestSupport() {
    }

    static List<Rewrite> suggest(Dialect d, String sql) {
        return suggest(d, sql, new Catalog());
    }

    static List<Rewrite> suggest(Dialect d, String sql, Catalog catalog) {
        List<Rewrite> list = RewriteSuggester.suggest(d, sql, catalog, List.of());
        for (Rewrite r : list) {
            sane(sql, r);
        }
        return list;
    }

    /** Every rewrite must apply to the original text and reproduce its rewritten statement there. */
    static void sane(String sql, Rewrite r) {
        String applied = RewriteSuggester.apply(sql, r.edits());
        assertTrue(applied.contains(r.rewrittenStatement()), () -> "rewritten statement not in applied text: " + r);
        assertNotEquals(sql, applied);
        assertNotNull(r.check());
        assertTrue(r.check().equals("STRUCTURE_PRESERVED") || r.check().equals("STRUCTURE_CHANGED"), r::toString);
        assertTrue(!r.safetyNote().isBlank() && !r.explanation().isBlank() && !r.title().isBlank());
        for (int i = 1; i < r.edits().size(); i++) {
            assertTrue(r.edits().get(i - 1).end() <= r.edits().get(i).start(), "edits in order, not overlapping");
        }
    }

    static List<Rewrite> of(List<Rewrite> list, String rule) {
        List<Rewrite> out = new ArrayList<>();
        for (Rewrite r : list) {
            if (r.rule().equals(rule)) {
                out.add(r);
            }
        }
        return out;
    }

    static Rewrite only(List<Rewrite> list, String rule) {
        List<Rewrite> found = of(list, rule);
        assertEquals(1, found.size(), () -> "expected one " + rule + " in " + list);
        return found.get(0);
    }

    static void none(List<Rewrite> list, String rule) {
        assertEquals(List.of(), of(list, rule), () -> "expected no " + rule);
    }

    /** {@code "id number!"}: a column of that type, NOT NULL when the type ends with '!'. */
    static TableMeta table(String schema, String name, List<String> pk, String... columns) {
        List<TableMeta.ColumnMeta> cols = new ArrayList<>();
        for (String c : columns) {
            String[] p = c.split(" ", 2);
            boolean notNull = p[1].endsWith("!");
            cols.add(new TableMeta.ColumnMeta(p[0], notNull ? p[1].substring(0, p[1].length() - 1) : p[1], !notNull));
        }
        return new TableMeta(null, schema, name, "TABLE", cols, pk, List.of(), List.of(), -1, "pack-test");
    }

    static Catalog catalog(TableMeta... tables) {
        return Catalog.of(List.of(tables));
    }

    static String resource(String name) {
        try (InputStream in = RewriteTestSupport.class.getResourceAsStream("/rewrite/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("missing test resource " + name, e);
        }
    }
}
