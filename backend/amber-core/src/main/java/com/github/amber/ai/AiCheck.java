package com.github.amber.ai;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.i18n.Messages;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.IndirectRole;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.ir.ScriptIrBuilder;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks SQL proposed by an AI against the original, structurally: it must parse strictly in the same
 * dialect, and its output columns, write targets, source tables, grouping and per-column lineage are
 * compared. The result never claims semantic equivalence, and the SQL is never executed.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class AiCheck {

    public enum Label {
        STRUCTURE_PRESERVED, STRUCTURE_CHANGED, INVALID_SQL
    }

    public record Result(Label label, String sql, List<String> differences, List<Diagnostic> diagnostics) {
    }

    private AiCheck() {
    }

    public static Result check(Dialect dialect, String original, String rewritten, Map<String, String> mask,
                               Catalog catalog) {
        String sql = Masking.unmask(dialect, rewritten, mask).strip();
        String parsed = terminated(sql);                    // models often drop the final ';' some grammars need
        DialectFrontend f = DialectFrontends.get(dialect);
        ScriptParse after = f.parse(SourceText.of(parsed), ParseOptions.STRICT);
        if (after.status() != ScriptStatus.SUCCESS) {
            return new Result(Label.INVALID_SQL, sql, List.of(Messages.t("ai.check.invalid", dialect.displayName())),
                    after.diagnostics());
        }
        ScriptParse before = f.parse(SourceText.of(terminated(original.strip())), ParseOptions.FALLBACK);
        Shape a = shape(ScriptIrBuilder.build(f, before, catalog.copy()));
        Shape b = shape(ScriptIrBuilder.build(f, after, catalog.copy()));
        List<String> diffs = new ArrayList<>();
        compare("ai.check.label.outputs", a.outputs, b.outputs, diffs);
        compare("ai.check.label.writes", a.writes, b.writes, diffs);
        compare("ai.check.label.sources", a.sources, b.sources, diffs);
        compare("ai.check.label.grouping", a.grouping, b.grouping, diffs);
        compare("ai.check.label.rowShaping", a.rowShaping, b.rowShaping, diffs);
        for (String out : a.lineage.keySet()) {
            if (b.lineage.containsKey(out) && !a.lineage.get(out).equals(b.lineage.get(out))) {
                diffs.add(b.lineage.get(out).isEmpty() && !a.lineage.get(out).isEmpty()
                        ? Messages.t("ai.check.lostSources", out, String.join(", ", a.lineage.get(out)))
                        : Messages.t("ai.check.lineage", out, a.lineage.get(out), b.lineage.get(out)));
            }
        }
        return new Result(diffs.isEmpty() ? Label.STRUCTURE_PRESERVED : Label.STRUCTURE_CHANGED, sql, diffs, List.of());
    }

    private static String terminated(String sql) {
        return sql.isEmpty() || sql.endsWith(";") || sql.endsWith("/") ? sql : sql + ";";
    }

    private record Shape(List<String> outputs, Set<String> writes, Set<String> sources, Set<String> grouping,
                         Map<String, Set<String>> lineage, List<String> rowShaping) {
    }

    private static Shape shape(Script script) {
        List<String> outputs = new ArrayList<>();
        Set<String> writes = new TreeSet<>();
        Set<String> sources = new TreeSet<>();
        Set<String> grouping = new TreeSet<>();
        Map<String, Set<String>> lineage = new LinkedHashMap<>();
        Set<String> created = new TreeSet<>();
        List<String> rowShaping = rowShaping(script);
        for (Statement st : script.flatten()) {
            for (ObjectRef r : st.refs()) {
                if (r.kind() == ObjectKind.CTE) {
                    continue;
                }
                if (r.access() == Access.READ) {
                    sources.add(r.name().key());
                } else {
                    writes.add(r.access() + " " + r.name().key());
                    if (r.access() == Access.CREATE) {
                        created.add(r.name().key());
                    }
                }
            }
            if (st.lineage() == null) {
                continue;
            }
            for (ColumnNode o : st.lineage().outputs()) {
                outputs.add(o.column().toUpperCase(Locale.ROOT));
                lineage.put(o.column().toUpperCase(Locale.ROOT), baseSources(st.lineage().edges(), o));
            }
            for (ColumnEdge e : st.lineage().edges()) {
                if (!e.isDirect() && e.role() == IndirectRole.GROUP && isBase(e.from())) {
                    grouping.add(e.from().ownerKey() + "." + e.from().column().toUpperCase(Locale.ROOT));
                }
            }
        }
        sources.removeAll(created);                 // temp tables built inside the rewrite are internal
        return new Shape(outputs, writes, sources, grouping, lineage, rowShaping);
    }

    /**
     * Words that decide which rows come back and in what order (a row limit makes ORDER BY matter), from
     * the tokens of the whole script so every grammar is covered; comments and strings are skipped.
     */
    private static List<String> rowShaping(Script script) {
        List<String> out = new ArrayList<>();
        var sc = new com.github.amber.dialect.split.SqlScanner(script.source().text(),
                com.github.amber.dialect.split.ScannerOptions.forDialect(script.dialect()));
        com.github.amber.dialect.split.ScanToken prev = null;
        for (var t = sc.next(); t != null; prev = t, t = sc.next()) {
            if (t.isWord("BY") && prev != null && prev.isWord("ORDER")) {
                out.add("ORDER BY");
            } else if (t.isWord("DISTINCT") || t.isWord("TOP") || t.isWord("LIMIT") || t.isWord("OFFSET")
                    || t.isWord("FETCH") || t.isWord("ROWNUM") || t.isWord("QUALIFY")) {
                out.add(t.text().toUpperCase(Locale.ROOT));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Base-table columns that feed {@code out} through direct edges (CTE and subquery hops followed). */
    private static Set<String> baseSources(List<ColumnEdge> edges, ColumnNode out) {
        Set<String> result = new TreeSet<>();
        Deque<ColumnNode> queue = new ArrayDeque<>();
        queue.add(out);
        Set<String> seen = new TreeSet<>();
        while (!queue.isEmpty()) {
            ColumnNode n = queue.poll();
            if (!seen.add(n.key())) {
                continue;
            }
            for (ColumnEdge e : edges) {
                if (e.isDirect() && e.to().key().equals(n.key())) {
                    if (isBase(e.from())) {
                        result.add(e.from().ownerKey() + "." + e.from().column().toUpperCase(Locale.ROOT));
                    } else {
                        queue.add(e.from());
                    }
                }
            }
        }
        return result;
    }

    private static boolean isBase(ColumnNode n) {
        return n.ownerKind() != ColumnNode.OwnerKind.CTE && n.ownerKind() != ColumnNode.OwnerKind.SUBQUERY
                && n.ownerKind() != ColumnNode.OwnerKind.RESULT;
    }

    private static void compare(String labelKey, Object a, Object b, List<String> diffs) {
        if (!a.equals(b)) {
            diffs.add(Messages.t("ai.check.difference", Messages.t(labelKey), a, b));
        }
    }

    /** English labels that start a difference, with their message keys (see {@link #localizedPrefix}). */
    private static final Map<String, String> LABELS = Map.of("Output columns", "ai.check.label.outputs",
            "Written objects", "ai.check.label.writes", "Source tables", "ai.check.label.sources",
            "Grouping columns", "ai.check.label.grouping", "Ordering and row limits", "ai.check.label.rowShaping",
            "Lineage of", "ai.check.label.lineage");

    /**
     * A difference prefix given in English (e.g. {@code "Ordering and row limits:"} or {@code "Lineage of"}),
     * in the current language, so code can recognize differences with {@code startsWith} in any language.
     * Prefixes that start with no known label come back unchanged.
     */
    public static String localizedPrefix(String english) {
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            if (english.startsWith(e.getKey())) {
                String rest = english.substring(e.getKey().length());
                if (rest.startsWith(":")) {
                    rest = Messages.t("ai.check.colon") + rest.substring(1);
                }
                return Messages.t(e.getValue()) + rest;
            }
        }
        return english;
    }
}
