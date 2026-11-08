package com.github.amber.dialect.findings.rules;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Findings;
import com.github.amber.dialect.ir.ScriptIrBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Helpers for the extra-rule tests: parse strictly, build the IR, run every finding rule (the extra rules
 * in strict mode first, so a failure shows), and build table metadata.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class RulesTestSupport {

    private RulesTestSupport() {
    }

    static Script script(Dialect d, String sql) {
        DialectFrontend f = DialectFrontends.get(d);
        ScriptParse p = f.parse(SourceText.of(sql));
        assertEquals(ScriptStatus.SUCCESS, p.status(), () -> d + ": " + p.diagnostics());
        return ScriptIrBuilder.build(f, p);
    }

    /** All findings (built-in and extra rules) of a script, with metadata. */
    static List<Finding> findings(Dialect d, String sql, TableMeta... tables) {
        Script s = script(d, sql);
        Catalog c = Catalog.of(Arrays.asList(tables));
        ExtraRules.of(s, c, true);                       // strict: a rule failure fails the test
        return Findings.of(s, c);
    }

    static List<Finding> rule(List<Finding> all, String id) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : all) {
            if (f.rule().equals(id)) {
                out.add(f);
            }
        }
        return out;
    }

    static List<Finding> rule(Dialect d, String sql, String id, TableMeta... tables) {
        return rule(findings(d, sql, tables), id);
    }

    static Finding one(Dialect d, String sql, String id, TableMeta... tables) {
        List<Finding> list = rule(d, sql, id, tables);
        assertEquals(1, list.size(), () -> "expected one " + id + " in " + d + ": " + list);
        Finding f = list.get(0);
        assertTrue(!f.title().isBlank() && !f.message().isBlank() && !f.suggestion().isBlank(), f::toString);
        return f;
    }

    static void none(Dialect d, String sql, String id, TableMeta... tables) {
        List<Finding> list = rule(d, sql, id, tables);
        assertEquals(List.of(), list, () -> "expected no " + id + " in " + d);
    }

    /**
     * Table metadata. Columns as {@code "name type"} ({@code "!"} at the end = NOT NULL); {@code pk} the
     * primary key; {@code indexes} each a comma-separated column list ({@code "u:"} prefix = unique).
     */
    static TableMeta table(String schema, String name, List<String> pk, List<String> indexes, String... columns) {
        return table(schema, name, pk, indexes, -1, columns);
    }

    static TableMeta table(String schema, String name, List<String> pk, List<String> indexes, long rows,
                           String... columns) {
        List<TableMeta.ColumnMeta> cols = new ArrayList<>();
        for (String c : columns) {
            String[] p = c.split(" ", 2);
            boolean notNull = p[1].endsWith("!");
            cols.add(new TableMeta.ColumnMeta(p[0], notNull ? p[1].substring(0, p[1].length() - 1) : p[1], !notNull));
        }
        List<TableMeta.IndexMeta> ix = new ArrayList<>();
        int n = 0;
        for (String i : indexes) {
            boolean unique = i.startsWith("u:");
            ix.add(new TableMeta.IndexMeta("ix" + (++n), List.of((unique ? i.substring(2) : i).split(",")), unique));
        }
        return new TableMeta(null, schema, name, "TABLE", cols, pk, List.of(), ix, rows, "test-pack");
    }

    static int line(Finding f) {
        return f.range().start().line();
    }
}
