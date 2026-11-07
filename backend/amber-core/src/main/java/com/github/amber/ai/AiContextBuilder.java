package com.github.amber.ai;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.findings.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the prompt context for AI-assisted optimization from Amber's own analysis: structure,
 * lineage, findings and table metadata of the chosen scope. The AI reasons over this; it never parses.
 * Large scopes are trimmed to a token budget, and whatever is left out is listed.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class AiContextBuilder {

    /** What the model is asked to return (see docs/design.md §10.4). */
    public static final String SYSTEM_PROMPT = """
            You are a senior database engineer helping a developer maintain legacy SQL. You get one or more
            SQL statements together with an analysis produced by a parser (structure, column lineage, risks
            and table metadata). Propose concrete optimizations that keep the result the same: same output
            columns in the same order, same target tables, same rows. Prefer simple, reviewable changes.
            Use the same SQL dialect as the input. Never invent tables or columns that are not in the context.
            Answer only with JSON of this shape:
            {"suggestions":[{"title":"…","target":{"statement":<id>},"rationale":"…","expectedEffect":"…",
            "risk":"…","rewrittenSql":"<the complete rewritten statement>"}]}
            Return an empty list if there is nothing worth changing.""";

    public record Context(String system, String user, int tokenEstimate, List<Integer> statements,
                          List<String> trimmed, Map<String, String> mask) {
    }

    private AiContextBuilder() {
    }

    /**
     * @param ids        statement ids (step indexes) in scope; empty means the whole script
     * @param steps      all statements in source order (ids index this list)
     * @param tokenBudget approximate maximum tokens of the user message
     * @param mask       replace names and literals with aliases
     */
    public static Context build(Script script, List<Statement> steps, List<Integer> ids, List<Finding> findings,
                                Catalog catalog, int tokenBudget, boolean mask) {
        return build(script, steps, ids, findings, catalog, tokenBudget, mask, null);
    }

    /**
     * @param notes the developer's own notes for the model (constraints, data volumes, goals); appended to
     *              the user message, masked like the context; never trimmed by the budget
     */
    public static Context build(Script script, List<Statement> steps, List<Integer> ids, List<Finding> findings,
                                Catalog catalog, int tokenBudget, boolean mask, String notes) {
        Masking masking = new Masking(script.dialect());
        List<Statement> scope = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            script.statements().forEach(scope::add);
        } else {
            ids.forEach(i -> scope.add(steps.get(i)));
        }
        Set<Statement> withFindings = new LinkedHashSet<>();
        findings.forEach(f -> withFindings.add(f.statement()));

        if (mask) {
            for (Statement top : scope) {
                top.forEach(st -> {
                    for (ObjectRef r : st.refs()) {
                        r.name().parts().forEach(p -> masking.object(p.text()));
                    }
                    if (st.lineage() != null) {
                        for (ColumnEdge e : st.lineage().edges()) {
                            masking.column(e.from().column());
                            masking.column(e.to().column());
                            if (e.from().ownerKind() == ColumnNode.OwnerKind.CTE || e.from().ownerKind() == ColumnNode.OwnerKind.SUBQUERY) {
                                masking.object(e.from().owner());
                            }
                        }
                    }
                    st.ctes().forEach(masking::object);
                });
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Dialect: ").append(script.dialect().displayName()).append("\n\n");
        List<Integer> included = new ArrayList<>();
        List<String> trimmed = new ArrayList<>();
        int budgetChars = Math.max(2000, tokenBudget * 4);
        Set<String> tables = new LinkedHashSet<>();
        for (Statement top : scope) {
            for (Statement st : flatten(top)) {
                int id = steps.indexOf(st);
                String section = section(script, st, id, findings, masking, mask, tables, catalog);
                boolean full = scope.size() == 1 || withFindings.contains(st) || (ids != null && ids.contains(id));
                if (!full) {
                    section = summary(st, id, masking, mask);
                }
                if (sb.length() + section.length() > budgetChars) {
                    trimmed.add("statement " + id + " (line " + st.range().start().line() + ")");
                    continue;
                }
                sb.append(section).append('\n');
                included.add(id);
            }
        }
        String meta = metadata(tables, catalog, masking, mask);
        if (!meta.isEmpty()) {
            if (sb.length() + meta.length() <= budgetChars) {
                sb.append(meta);
            } else {
                trimmed.add("table metadata");
            }
        }
        if (notes != null && !notes.isBlank()) {
            String n = mask ? Masking.maskText(notes.strip(), masking.mapping()) : notes.strip();
            sb.append("\n## Notes from the developer\n").append(n).append('\n')
              .append("Take these notes into account; they describe facts and constraints Amber cannot see.\n");
        }
        String user = sb.toString();
        return new Context(SYSTEM_PROMPT, user, (SYSTEM_PROMPT.length() + user.length()) / 4, included, trimmed,
                mask ? masking.mapping() : Map.of());
    }

    private static List<Statement> flatten(Statement top) {
        List<Statement> out = new ArrayList<>();
        top.forEach(s -> {
            if (!s.kind().isContainer()) {
                out.add(s);
            }
        });
        if (out.isEmpty()) {
            out.add(top);
        }
        return out;
    }

    private static String summary(Statement st, int id, Masking m, boolean mask) {
        String text = "Statement " + id + " (" + st.kind() + "): " + st.summary();
        return "- " + (mask ? m.mask(text) : text) + "\n";
    }

    private static String section(Script script, Statement st, int id, List<Finding> findings, Masking m,
                                  boolean mask, Set<String> tables, Catalog catalog) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Statement ").append(id).append(" (").append(st.kind()).append(", line ")
                .append(st.range().start().line()).append(")\n");
        String sql = script.source().slice(st.range());
        sb.append("```sql\n").append(mask ? m.mask(sql) : sql).append("\n```\n");
        List<String> reads = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        for (ObjectRef r : st.refs()) {
            String n = mask ? m.mask(r.name().toString()) : r.name().toString();
            if (r.kind() == ObjectKind.CTE) {
                continue;
            }
            (r.access() == Access.READ ? reads : writes).add(n + (r.access() == Access.READ ? "" : " (" + r.access() + ")"));
            if (r.kind() == ObjectKind.TABLE || r.kind() == ObjectKind.VIEW) {
                tables.add(r.name().key() + "|" + r.name().simpleKey());
            }
        }
        if (!reads.isEmpty()) {
            sb.append("Reads: ").append(String.join(", ", new LinkedHashSet<>(reads))).append('\n');
        }
        if (!writes.isEmpty()) {
            sb.append("Writes: ").append(String.join(", ", new LinkedHashSet<>(writes))).append('\n');
        }
        if (!st.ctes().isEmpty()) {
            sb.append("CTEs: ").append(mask ? m.mask(String.join(", ", st.ctes())) : String.join(", ", st.ctes())).append('\n');
        }
        if (st.lineage() != null && !st.lineage().outputs().isEmpty()) {
            sb.append("Output columns and their sources:\n");
            Map<String, List<String>> sources = new LinkedHashMap<>();
            for (ColumnEdge e : st.lineage().edges()) {
                if (e.isDirect()) {
                    sources.computeIfAbsent(e.to().toString(), k -> new ArrayList<>())
                            .add(e.from() + " [" + e.transform() + "]");
                }
            }
            for (ColumnNode out : st.lineage().outputs()) {
                String line = "- " + out + " <- " + String.join(", ", sources.getOrDefault(out.toString(), List.of("(constant)")));
                sb.append(mask ? m.mask(line) : line).append('\n');
            }
            Set<String> indirect = new LinkedHashSet<>();
            for (ColumnEdge e : st.lineage().edges()) {
                if (!e.isDirect()) {
                    indirect.add(e.from() + " (" + e.role() + ")");
                }
            }
            if (!indirect.isEmpty()) {
                String line = "Rows decided by: " + String.join(", ", indirect);
                sb.append(mask ? m.mask(line) : line).append('\n');
            }
        }
        for (Finding f : findings) {
            if (f.statement() == st) {
                String line = "Risk " + f.rule() + " (" + f.severity() + "): " + f.title() + " - " + f.message();
                sb.append(mask ? m.mask(line) : line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String metadata(Set<String> tables, Catalog catalog, Masking m, boolean mask) {
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            String[] k = t.split("\\|");
            TableMeta meta = catalog.meta(k[0], k[1]).orElse(null);
            if (meta == null) {
                continue;
            }
            if (sb.isEmpty()) {
                sb.append("### Table metadata\n");
            }
            String line = "- " + meta.qualifiedName() + ": columns " + meta.columns().stream()
                    .map(c -> c.name() + (c.type() == null ? "" : " " + c.type())).toList()
                    + (meta.primaryKey().isEmpty() ? "" : "; primary key " + meta.primaryKey())
                    + (meta.indexes().isEmpty() ? "" : "; indexes " + meta.indexes().stream()
                    .map(ix -> (ix.unique() ? "unique " : "") + ix.columns()).toList())
                    + (meta.rowEstimate() >= 0 ? "; ~" + meta.rowEstimate() + " rows" : "");
            if (mask) {
                ObjectName.parse(com.github.amber.core.Dialect.ORACLE, meta.qualifiedName()).parts()
                        .forEach(p -> m.object(p.text()));
                meta.columns().forEach(c -> m.column(c.name()));
            }
            sb.append(mask ? m.mask(line) : line).append('\n');
        }
        return sb.toString();
    }
}
