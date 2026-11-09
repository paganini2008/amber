package com.github.amber.docgen;

import com.github.amber.core.analysis.ExecutionOrder;
import com.github.amber.core.analysis.Lifecycles;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.i18n.Messages;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.QueryBlock;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.LineageGraph;
import com.github.amber.dialect.findings.Finding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A handover document for a legacy SQL script, generated from Amber's analysis: what the script does
 * step by step, which objects it reads, builds and writes, where the target columns come from, the risks
 * found, and the logical order of its most complex statements. Written as Markdown (with Mermaid
 * diagrams); {@link #toHtml} renders the same content as a self-contained HTML page. Headings, labels and
 * sentences are in the language of {@link Messages#current()}; names, SQL and rule ids are not translated.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class HandoverDoc {

    private static final int MAX_OUTLINE = 400;
    private static final int MAX_FLOW_EDGES = 80;
    private static final int MAX_KEY_STATEMENTS = 3;

    private final Script script;
    private final List<Statement> steps;
    private final List<Finding> findings;
    private final List<Diagnostic> diagnostics;
    private final StringBuilder md = new StringBuilder();

    private HandoverDoc(Script script, List<Finding> findings, List<Diagnostic> diagnostics) {
        this.script = script;
        this.steps = Lifecycles.steps(script);
        this.findings = findings;
        this.diagnostics = diagnostics;
    }

    /** Markdown handover document of the script. */
    public static String markdown(Script script, List<Finding> findings, List<Diagnostic> diagnostics) {
        HandoverDoc d = new HandoverDoc(script, findings == null ? List.of() : findings,
                diagnostics == null ? List.of() : diagnostics);
        d.write();
        return d.md.toString();
    }

    private void write() {
        String name = script.source().name();
        md.append("# ").append(title(name)).append("\n\n");
        md.append(t("doc.intro")).append("\n\n");
        overview();
        outline();
        objects();
        flow();
        lineage();
        risks();
        keyStatements();
        problems();
    }

    private void overview() {
        md.append("## ").append(t("doc.overview")).append("\n\n");
        Map<StatementKind.Category, Integer> byCategory = new EnumMap<>(StatementKind.Category.class);
        steps.forEach(s -> byCategory.merge(s.kind().category(), 1, Integer::sum));
        List<ObjectLifecycle> lifecycles = Lifecycles.of(script);
        long temp = lifecycles.stream().filter(ObjectLifecycle::createdInScript).count();
        md.append("| | |\n|---|---|\n");
        row(t("doc.overview.dialect"), script.dialect().displayName());
        row(t("doc.overview.lines"), String.valueOf(script.source().lineCount()));
        row(t("doc.overview.statements"), t("doc.withDetail", steps.size(), String.join(t("doc.separator"),
                byCategory.entrySet().stream().map(e -> t("doc.count", e.getValue(), word("doc.category.", e.getKey())))
                        .toList())));
        row(t("doc.overview.objects"), t("doc.overview.objects.value", lifecycles.size(), temp));
        row(t("doc.overview.sources"), names(lifecycles, ObjectLifecycle.Role.SOURCE));
        row(t("doc.overview.targets"), names(lifecycles, ObjectLifecycle.Role.TARGET));
        row(t("doc.overview.findings"), findings.isEmpty() ? t("doc.none") : t("doc.withDetail", findings.size(), severityCounts()));
        md.append('\n');
    }

    private String names(List<ObjectLifecycle> lifecycles, ObjectLifecycle.Role role) {
        List<String> n = lifecycles.stream().filter(l -> l.role() == role).map(l -> "`" + l.name() + "`").toList();
        if (n.isEmpty()) {
            return t("doc.none");
        }
        return n.size() > 12 ? t("doc.more", String.join(", ", n.subList(0, 12)), n.size() - 12) : String.join(", ", n);
    }

    private String severityCounts() {
        Map<Finding.Severity, Integer> c = new EnumMap<>(Finding.Severity.class);
        findings.forEach(f -> c.merge(f.severity(), 1, Integer::sum));
        return String.join(t("doc.separator"), c.entrySet().stream().map(e -> t("doc.count", e.getValue(), word("doc.severity.", e.getKey()))).toList());
    }

    private void outline() {
        md.append("## ").append(t("doc.outline")).append("\n\n");
        int[] count = {0};
        for (Statement st : script.statements()) {
            outline(st, 0, count);
        }
        if (count[0] >= MAX_OUTLINE) {
            md.append("\n").append(t("doc.outline.cut", MAX_OUTLINE)).append("\n");
        }
        md.append('\n');
    }

    private void outline(Statement st, int depth, int[] count) {
        if (count[0]++ >= MAX_OUTLINE) {
            return;
        }
        md.append("   ".repeat(depth)).append(depth == 0 ? "1. " : "- ")
          .append(escape(st.summary().isBlank() ? st.kind().name() : st.summary()))
          .append(t("doc.outline.line", lines(st))).append('\n');
        for (Statement c : st.children()) {
            outline(c, depth + 1, count);
        }
    }

    private void objects() {
        List<ObjectLifecycle> lifecycles = Lifecycles.of(script);
        if (lifecycles.isEmpty()) {
            return;
        }
        md.append("## ").append(t("doc.objects")).append("\n\n").append(t("doc.objects.header")).append("\n|---|---|---|---|---|---|\n");
        for (ObjectLifecycle l : lifecycles) {
            String events = String.join(", ", l.events().stream()
                    .map(e -> word("doc.access.", e.access()) + " @" + e.range().start().line()).distinct().toList());
            md.append("| `").append(l.name()).append("` | ").append(word("doc.object.", l.kind())).append(" | ")
              .append(word("doc.role.", l.role())).append(" | ").append(l.createdInScript() ? t("doc.yes") : "")
              .append(" | ").append(l.droppedInScript() ? t("doc.yes") : "").append(" | ").append(escape(events)).append(" |\n");
        }
        md.append("\n").append(t("doc.objects.roles")).append("\n\n");
    }

    /** Object-level data flow: for every statement, the objects it reads feed the objects it writes. */
    private void flow() {
        Set<String> edges = new LinkedHashSet<>();
        for (Statement st : steps) {
            if (!st.children().isEmpty()) {
                continue;
            }
            Set<String> reads = new TreeSet<>();
            Set<String> writes = new TreeSet<>();
            for (ObjectRef r : st.refs()) {
                if (r.kind() == ObjectKind.CTE) {
                    continue;
                }
                if (r.access() == Access.READ) {
                    reads.add(r.name().toString());
                } else if (r.access() == Access.INSERT || r.access() == Access.UPDATE || r.access() == Access.MERGE
                        || (r.access() == Access.CREATE && !reads.isEmpty())) {
                    writes.add(r.name().toString());
                }
            }
            for (String from : reads) {
                for (String to : writes) {
                    if (!from.equalsIgnoreCase(to)) {
                        edges.add(from + "\u0000" + to);
                    }
                }
            }
        }
        if (edges.isEmpty()) {
            return;
        }
        md.append("## ").append(t("doc.flow")).append("\n\n```mermaid\nflowchart LR\n");
        Map<String, String> ids = new LinkedHashMap<>();
        int n = 0;
        for (String e : edges) {
            if (n++ >= MAX_FLOW_EDGES) {
                break;
            }
            String[] p = e.split("\u0000");
            md.append("  ").append(node(ids, p[0])).append(" --> ").append(node(ids, p[1])).append('\n');
        }
        md.append("```\n\n");
        if (edges.size() > MAX_FLOW_EDGES) {
            md.append(t("doc.flow.showing", MAX_FLOW_EDGES, edges.size())).append("\n\n");
        }
    }

    private static String node(Map<String, String> ids, String name) {
        String id = ids.get(name);
        if (id != null) {
            return id;
        }
        id = "n" + ids.size();
        ids.put(name, id);
        return id + "[\"" + name.replace("\"", "#quot;") + "\"]";
    }

    /** Where every column written to a target object comes from (base columns through CTEs and temp tables). */
    private void lineage() {
        LineageGraph graph = LineageGraph.of(script);
        Map<String, List<ColumnNode>> targets = new LinkedHashMap<>();
        Set<String> targetKeys = new TreeSet<>();
        Lifecycles.of(script).stream().filter(l -> l.role() == ObjectLifecycle.Role.TARGET)
                .forEach(l -> targetKeys.add(l.name().key()));
        for (ColumnNode n : graph.nodes()) {
            if (!n.isRows() && targetKeys.contains(n.ownerKey().toUpperCase(Locale.ROOT))) {
                targets.computeIfAbsent(n.owner(), k -> new ArrayList<>()).add(n);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        md.append("## ").append(t("doc.lineage")).append("\n\n");
        for (Map.Entry<String, List<ColumnNode>> t : targets.entrySet()) {
            md.append("### `").append(t.getKey()).append("`\n\n").append(t("doc.lineage.header")).append("\n|---|---|---|\n");
            for (ColumnNode col : t.getValue()) {
                Set<String> base = new TreeSet<>();
                Set<String> how = new TreeSet<>();
                for (LineageGraph.Edge e : graph.upstream(col, false)) {
                    ColumnEdge ce = e.edge();
                    if (ce.to().equals(col) && ce.transform() != null) {
                        how.add(word("doc.transform.", ce.transform()));
                    }
                    if (ce.from().ownerKind() == ColumnNode.OwnerKind.TABLE || ce.from().ownerKind() == ColumnNode.OwnerKind.VIEW) {
                        base.add(ce.from().owner() + "." + ce.from().column());
                    }
                }
                md.append("| `").append(col.column()).append("` | ")
                  .append(base.isEmpty() ? t("doc.lineage.unknown") : escape(String.join(", ", base)))
                  .append(" | ").append(String.join(", ", how)).append(" |\n");
            }
            md.append('\n');
        }
    }

    private void risks() {
        if (findings.isEmpty()) {
            return;
        }
        md.append("## ").append(t("doc.risks")).append("\n\n");
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparing(Finding::severity).thenComparingInt(f -> f.range().start().offset()));
        for (Finding f : sorted) {
            md.append("- **").append(word("doc.severityLabel.", f.severity())).append("** `").append(f.rule()).append("` ")
              .append(t("doc.risks.line", f.range().start().line())).append(" — ").append(escape(f.title())).append(": ")
              .append(escape(f.message())).append(" _").append(escape(f.suggestion())).append("_")
              .append(f.confidence().name().equals("INFERRED") ? t("doc.risks.possible") : "").append('\n');
        }
        md.append('\n');
    }

    /** The most complex statements, with their nested structure and logical order. */
    private void keyStatements() {
        List<Statement> withQueries = new ArrayList<>(steps.stream().filter(s -> !s.executionOrder().isEmpty()).toList());
        if (withQueries.isEmpty()) {
            return;
        }
        withQueries.sort(Comparator.comparingInt((Statement s) -> -blocks(s.structure())));
        md.append("## ").append(t("doc.complex")).append("\n\n");
        for (Statement st : withQueries.subList(0, Math.min(MAX_KEY_STATEMENTS, withQueries.size()))) {
            md.append("### ").append(t("doc.complex.heading", escape(st.summary()), lines(st))).append("\n\n");
            int n = blocks(st.structure());
            md.append(t(n == 1 ? "doc.complex.blocks.one" : "doc.complex.blocks.other", n)).append("\n\n");
            for (ExecutionOrder.Step s : st.executionOrder()) {
                md.append("   ".repeat(s.depth())).append("1. **").append(s.phase()).append("**")
                  .append(s.detail() == null ? "" : " — " + escape(s.detail()))
                  .append(t("doc.complex.stepLine", s.range().start().line())).append("\n");
            }
            md.append('\n');
        }
    }

    private static int blocks(List<QueryBlock> list) {
        int n = 0;
        for (QueryBlock b : list) {
            n += 1 + blocks(b.children());
        }
        return n;
    }

    private void problems() {
        List<Diagnostic> errors = diagnostics.stream().filter(d -> d.severity().name().equals("ERROR")).toList();
        if (errors.isEmpty()) {
            return;
        }
        md.append("## ").append(t("doc.problems")).append("\n\n");
        errors.stream().limit(50).forEach(d -> md.append("- ").append(t("doc.problems.line", d.range().start().line()))
                .append(escape(d.message().length() > 160 ? d.message().substring(0, 157) + "…" : d.message())).append('\n'));
        md.append('\n');
    }

    /** The document title for a script name, also used as the HTML page title. */
    public static String title(String scriptName) {
        return t("doc.title", scriptName);
    }

    private static String t(String key, Object... args) {
        return Messages.t(key, args);
    }

    /** The word for an enum value ({@code prefix + name}); its lower-case name when no text exists. */
    private static String word(String prefix, Enum<?> value) {
        Messages m = Messages.current();
        String key = prefix + value.name();
        return m.has(key) ? m.text(key) : value.name().toLowerCase(Locale.ROOT);
    }

    private void row(String k, String v) {
        md.append("| ").append(k).append(" | ").append(v).append(" |\n");
    }

    private static String lines(Statement st) {
        int a = st.range().start().line();
        int b = st.range().end().line();
        return a == b ? String.valueOf(a) : a + "–" + b;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /** Self-contained HTML page of a document produced by {@link #markdown} (Mermaid blocks shown as code). */
    public static String toHtml(String title, String markdown) {
        return MarkdownHtml.page(title, markdown);
    }
}
