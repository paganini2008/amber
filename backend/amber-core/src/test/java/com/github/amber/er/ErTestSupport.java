package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Helpers for the ER model tests: parse strictly, build the model with optional metadata, find
 * relationships by entity keys, and build table metadata.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class ErTestSupport {

    private ErTestSupport() {
    }

    static ErModel model(Dialect d, String sql, TableMeta... tables) {
        return model(d, sql, ErModelBuilder.Options.DEFAULTS, tables);
    }

    static ErModel model(Dialect d, String sql, ErModelBuilder.Options o, TableMeta... tables) {
        DialectFrontend f = DialectFrontends.get(d);
        ScriptParse p = f.parse(SourceText.of(sql));
        assertEquals(ScriptStatus.SUCCESS, p.status(), () -> d + ": " + p.diagnostics());
        return ErModelBuilder.build(f, p, Catalog.of(Arrays.asList(tables)), o);
    }

    static ErModel fallback(Dialect d, String sql, TableMeta... tables) {
        DialectFrontend f = DialectFrontends.get(d);
        ScriptParse p = f.parse(SourceText.of(sql), ParseOptions.FALLBACK);
        return ErModelBuilder.build(f, p, Catalog.of(Arrays.asList(tables)), ErModelBuilder.Options.DEFAULTS);
    }

    /** Relationships between two entity keys, in either direction. */
    static List<ErModel.Relationship> between(ErModel m, String a, String b) {
        List<ErModel.Relationship> out = new ArrayList<>();
        for (ErModel.Relationship r : m.relationships()) {
            if (r.from().entity().equalsIgnoreCase(a) && r.to().entity().equalsIgnoreCase(b)
                    || r.from().entity().equalsIgnoreCase(b) && r.to().entity().equalsIgnoreCase(a)) {
                out.add(r);
            }
        }
        return out;
    }

    static ErModel.Relationship one(ErModel m, String a, String b) {
        List<ErModel.Relationship> l = between(m, a, b);
        if (l.size() != 1) {
            fail("Expected one relationship " + a + " - " + b + ", got " + l + "\nall: " + m.relationships());
        }
        return l.get(0);
    }

    static ErModel.Entity entity(ErModel m, String key) {
        return m.entities().stream().filter(e -> e.key().equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new AssertionError("No entity " + key + " in " + m.entities().stream()
                        .map(ErModel.Entity::key).toList()));
    }

    static ErModel.Column column(ErModel m, String entity, String column) {
        return entity(m, entity).columns().stream().filter(c -> c.name().equalsIgnoreCase(column)).findFirst()
                .orElseThrow(() -> new AssertionError("No column " + column + " in " + entity(m, entity)));
    }

    static TableMeta table(String name, List<String> pk, String... columns) {
        List<TableMeta.ColumnMeta> cols = new ArrayList<>();
        for (String c : columns) {
            cols.add(new TableMeta.ColumnMeta(c, "INTEGER", !pk.contains(c)));
        }
        return new TableMeta(null, null, name, "TABLE", cols, pk, List.of(), List.of(), 1000, "test-pack");
    }

    static TableMeta withFk(TableMeta t, String name, List<String> cols, String refTable, List<String> refCols) {
        List<TableMeta.ForeignKeyMeta> fks = new ArrayList<>(t.foreignKeys());
        fks.add(new TableMeta.ForeignKeyMeta(name, cols, null, null, refTable, refCols));
        return t.withForeignKeys(fks);
    }

    static String str(ErModel.Relationship r) {
        return r.from().entity() + r.from().columns() + " -> " + r.to().entity() + r.to().columns() + " "
                + r.cardinality() + " " + r.evidence() + " " + r.confidence() + " x" + r.count();
    }
}
