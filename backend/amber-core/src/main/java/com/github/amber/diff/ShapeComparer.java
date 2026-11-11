package com.github.amber.diff;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.ir.QueryBlock;
import com.github.amber.core.ir.Statement;
import com.github.amber.diff.DiffResult.Risk;
import com.github.amber.diff.DiffResult.ShapeChange;
import com.github.amber.diff.DiffResult.ShapeChangeKind;
import com.github.amber.diff.StatementAligner.P;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Query-shape impact of modified statements: the nested {@link QueryBlock} trees are flattened, aligned
 * by block kind and role, and compared block by block (sources, clauses, join count, set operators,
 * output columns and their order). CTE names and aliases are compared apart from the structure, so a
 * renamed CTE or alias is reported as a rename only.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class ShapeComparer {

    private ShapeComparer() {
    }

    /** A block with its path in the tree. */
    private record Flat(QueryBlock block, String path, String key) {
    }

    static List<ShapeChange> compare(DiffContext ctx) {
        List<ShapeChange> out = new ArrayList<>();
        for (P p : ctx.modified()) {
            compare(ctx, p, out);
        }
        return out;
    }

    private static void compare(DiffContext ctx, P p, List<ShapeChange> out) {
        Statement sb = p.a.st;
        Statement sa = p.b.st;
        Map<String, String> cteB = cteNames(sb);
        Map<String, String> cteA = cteNames(sa);
        List<Flat> fb = flatten(sb.structure(), cteB);
        List<Flat> fa = flatten(sa.structure(), cteA);
        Map<String, Integer> ids = new HashMap<>();
        int[] kb = fb.stream().mapToInt(f -> ids.computeIfAbsent(f.key(), k -> ids.size())).toArray();
        int[] ka = fa.stream().mapToInt(f -> ids.computeIfAbsent(f.key(), k -> ids.size())).toArray();
        int[] match = Sequences.match(kb, ka);
        boolean[] usedA = new boolean[fa.size()];
        for (int i = 0; i < fb.size(); i++) {
            if (match[i] >= 0) {
                usedA[match[i]] = true;
                block(ctx, p, fb.get(i), fa.get(match[i]), cteB, cteA, out);
            } else {
                Flat f = fb.get(i);
                add(ctx, p, out, ShapeChangeKind.BLOCK_REMOVED, f.path(), describe(f.block()),
                        blockMessage("diff.shape.blockRemoved", f), f, null, Risk.MEDIUM);
            }
        }
        for (int j = 0; j < fa.size(); j++) {
            if (!usedA[j]) {
                Flat f = fa.get(j);
                add(ctx, p, out, ShapeChangeKind.BLOCK_ADDED, f.path(), describe(f.block()),
                        blockMessage("diff.shape.blockAdded", f), null, f, Risk.MEDIUM);
            }
        }
        List<String> cb = sb.ctes();
        List<String> ca = sa.ctes();
        if (cb.size() == ca.size()) {
            for (int i = 0; i < cb.size(); i++) {
                if (!cb.get(i).equalsIgnoreCase(ca.get(i))) {
                    add(ctx, p, out, ShapeChangeKind.RENAMED, "CTE", cb.get(i) + " → " + ca.get(i),
                            Messages.t("diff.shape.cteRenamed", cb.get(i), ca.get(i)), null, null, Risk.LOW);
                }
            }
        }
    }

    private static void block(DiffContext ctx, P p, Flat b, Flat a, Map<String, String> cteB, Map<String, String> cteA,
                              List<ShapeChange> out) {
        QueryBlock qb = b.block();
        QueryBlock qa = a.block();
        String path = a.path();
        if (qb.kind() == QueryBlock.Kind.SET && !String.valueOf(qb.name()).equalsIgnoreCase(String.valueOf(qa.name()))) {
            add(ctx, p, out, ShapeChangeKind.SET_OPERATOR_CHANGED, path, qb.name() + " → " + qa.name(),
                    Messages.t("diff.shape.setOperator", path, qb.name(), qa.name()), b, a, Risk.MEDIUM);
        }
        Map<String, String> srcB = sources(qb, cteB);
        Map<String, String> srcA = sources(qa, cteA);
        for (Map.Entry<String, String> s : srcA.entrySet()) {
            if (!srcB.containsKey(s.getKey())) {
                add(ctx, p, out, ShapeChangeKind.SOURCE_ADDED, path, s.getValue(), Messages.t("diff.shape.sourceAdded", path, s.getValue()),
                        b, a, Risk.MEDIUM);
            }
        }
        for (Map.Entry<String, String> s : srcB.entrySet()) {
            if (!srcA.containsKey(s.getKey())) {
                add(ctx, p, out, ShapeChangeKind.SOURCE_REMOVED, path, s.getValue(), Messages.t("diff.shape.sourceRemoved", path,
                        s.getValue()), b, a, Risk.MEDIUM);
            }
        }
        aliases(ctx, p, qb, qa, path, b, a, out);
        int jb = joins(qb.clauses());
        int ja = joins(qa.clauses());
        if (jb != ja) {
            add(ctx, p, out, ShapeChangeKind.JOIN_COUNT_CHANGED, path, jb + " → " + ja,
                    Messages.t("diff.shape.joins", path, jb, ja), b, a, Risk.MEDIUM);
        }
        Set<String> clB = clauses(qb.clauses());
        Set<String> clA = clauses(qa.clauses());
        for (String c : clA) {
            if (!clB.contains(c)) {
                add(ctx, p, out, ShapeChangeKind.CLAUSE_ADDED, path, c, Messages.t("diff.shape.clauseAdded", path, c), b, a, clauseRisk(c));
            }
        }
        for (String c : clB) {
            if (!clA.contains(c)) {
                add(ctx, p, out, ShapeChangeKind.CLAUSE_REMOVED, path, c, Messages.t("diff.shape.clauseRemoved", path, c), b, a, clauseRisk(c));
            }
        }
        List<String> ob = upper(qb.outputs());
        List<String> oa = upper(qa.outputs());
        if (!sameOutputs(ob, oa)) {
            if (new TreeSet<>(ob).equals(new TreeSet<>(oa)) && ob.size() == oa.size()) {
                add(ctx, p, out, ShapeChangeKind.OUTPUT_ORDER_CHANGED, path, qb.outputs() + " → " + qa.outputs(),
                        Messages.t("diff.shape.outputsReordered", path), b, a, Risk.MEDIUM);
            } else {
                List<String> plus = new ArrayList<>(qa.outputs());
                plus.removeIf(o -> ob.stream().anyMatch(x -> sameOutput(x, outputKey(o))));
                List<String> minus = new ArrayList<>(qb.outputs());
                minus.removeIf(o -> oa.stream().anyMatch(x -> sameOutput(x, outputKey(o))));
                String detail = (plus.isEmpty() ? "" : "+" + String.join(", +", plus))
                        + (plus.isEmpty() || minus.isEmpty() ? "" : " ")
                        + (minus.isEmpty() ? "" : "-" + String.join(", -", minus));
                if (detail.isEmpty()) {
                    detail = qb.outputs() + " → " + qa.outputs();
                }
                add(ctx, p, out, ShapeChangeKind.OUTPUTS_CHANGED, path, detail, Messages.t("diff.shape.outputsChanged", path, detail),
                        b, a, Risk.MEDIUM);
            }
        }
    }

    private static void aliases(DiffContext ctx, P p, QueryBlock qb, QueryBlock qa, String path, Flat b, Flat a,
                                List<ShapeChange> out) {
        if (qb.sources().size() != qa.sources().size()) {
            return;
        }
        for (int i = 0; i < qb.sources().size(); i++) {
            QueryBlock.Source x = qb.sources().get(i);
            QueryBlock.Source y = qa.sources().get(i);
            boolean sameObject = String.valueOf(x.name()).equalsIgnoreCase(String.valueOf(y.name()))
                    || ("CTE".equals(x.kind()) && "CTE".equals(y.kind()));
            if (sameObject && !String.valueOf(x.alias()).equalsIgnoreCase(String.valueOf(y.alias()))) {
                add(ctx, p, out, ShapeChangeKind.RENAMED, path, x.alias() + " → " + y.alias(),
                        Messages.t("diff.shape.aliasRenamed", path, y.name(), x.alias(), y.alias()), b, a, Risk.LOW);
            }
        }
    }

    private static Risk clauseRisk(String clause) {
        return switch (clause) {
            case "GROUP BY", "HAVING", "DISTINCT", "WHERE", "LIMIT", "FETCH", "TOP", "OFFSET", "QUALIFY", "CONNECT BY",
                 "START WITH" -> Risk.MEDIUM;
            default -> Risk.LOW;
        };
    }

    private static void add(DiffContext ctx, P p, List<ShapeChange> out, ShapeChangeKind kind, String block,
                            String detail, String message, Flat b, Flat a, Risk risk) {
        String msg = Messages.t("diff.shape.message", p.b.label(), message);
        var br = b == null ? null : b.block().range();
        var ar = a == null ? null : a.block().range();
        out.add(new ShapeChange(kind, block, detail, msg, p.a.id, p.b.id, br, ar));
        ctx.reason(risk, "SHAPE_" + kind, msg, p.a.id, p.b.id, br == null ? p.a.st.range() : br,
                ar == null ? p.b.st.range() : ar);
    }

    // ----- helpers ---------------------------------------------------------------------------------

    /** CTE names of a statement mapped to their position, so renamed CTEs compare equal. */
    private static Map<String, String> cteNames(Statement st) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < st.ctes().size(); i++) {
            m.put(st.ctes().get(i).toUpperCase(Locale.ROOT), "CTE<" + (i + 1) + ">");
        }
        return m;
    }

    private static List<Flat> flatten(List<QueryBlock> roots, Map<String, String> ctes) {
        List<Flat> out = new ArrayList<>();
        for (QueryBlock b : roots) {
            flatten(b, "", ctes, out);
        }
        return out;
    }

    private static void flatten(QueryBlock b, String parent, Map<String, String> ctes, List<Flat> out) {
        String label = b.kind().name();
        if (b.kind() == QueryBlock.Kind.CTE && b.name() != null) {
            label += " " + ctes.getOrDefault(b.name().toUpperCase(Locale.ROOT), "?");
        }
        if (b.role() != null && b.kind() != QueryBlock.Kind.CTE) {
            label += " (" + b.role() + ")";
        }
        String key = parent.isEmpty() ? label : parent + " > " + label;
        out.add(new Flat(b, displayPath(key, ctes), key));
        for (QueryBlock c : b.children()) {
            flatten(c, key, ctes, out);
        }
    }

    /** Display path: CTE positions replaced by the CTE's own name. */
    private static String displayPath(String path, Map<String, String> ctes) {
        String out = path;
        for (Map.Entry<String, String> e : ctes.entrySet()) {
            out = out.replace(e.getValue(), e.getKey().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Sources of a block: comparison key (CTEs by position, derived tables alike) to display name. */
    private static Map<String, String> sources(QueryBlock b, Map<String, String> ctes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (QueryBlock.Source s : b.sources()) {
            String display = s.name() == null ? "(derived)" : s.name();
            String key;
            if ("CTE".equals(s.kind())) {
                key = ctes.getOrDefault(display.toUpperCase(Locale.ROOT), display);
            } else if ("DERIVED".equals(s.kind()) || "SUBQUERY".equals(s.kind())) {
                key = "(derived)";
            } else {
                key = display.toUpperCase(Locale.ROOT);
            }
            out.putIfAbsent(key, display);
        }
        return out;
    }

    private static int joins(List<String> clauses) {
        int n = 0;
        for (String c : clauses) {
            if (c.startsWith("JOIN")) {
                int x = c.indexOf('×');
                n += x < 0 ? 1 : Integer.parseInt(c.substring(x + 1).trim());
            }
        }
        return n;
    }

    private static Set<String> clauses(List<String> clauses) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : clauses) {
            if (!c.startsWith("JOIN")) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<String> upper(List<String> xs) {
        return xs.stream().map(ShapeComparer::outputKey).toList();
    }

    /**
     * An output column for comparison: upper case, and an unnamed expression without its layout (spaces and
     * line breaks outside quotes), so reformatting {@code SUBSTR(a,1,4)} as {@code SUBSTR(a, 1, 4)} is no change.
     */
    private static boolean sameOutputs(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!sameOutput(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Two output keys name the same column. Long unnamed expressions arrive cut at a fixed length with "…", and
     * the cut falls elsewhere once the layout changes, so a cut key matches by its common prefix.
     */
    static boolean sameOutput(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        boolean cutA = a.endsWith("…");
        boolean cutB = b.endsWith("…");
        if (!cutA && !cutB) {
            return false;
        }
        String x = cutA ? a.substring(0, a.length() - 1) : a;
        String y = cutB ? b.substring(0, b.length() - 1) : b;
        int n = Math.min(x.length(), y.length());
        return n >= 12 && x.substring(0, n).equals(y.substring(0, n));
    }

    static String outputKey(String output) {
        StringBuilder sb = new StringBuilder(output.length());
        char quote = 0;
        for (int i = 0; i < output.length(); i++) {
            char c = output.charAt(i);
            if (quote != 0) {
                sb.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(c);
            } else if (!Character.isWhitespace(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    private static String describe(QueryBlock b) {
        return b.kind() + (b.role() == null ? "" : " (" + b.role() + ")");
    }

    /** "{path} removed|added", with " on {sources}" when the block reads any. */
    private static String blockMessage(String key, Flat f) {
        QueryBlock b = f.block();
        if (b.sources().isEmpty()) {
            return Messages.t(key, f.path());
        }
        return Messages.t(key + "On", f.path(),
                String.join(", ", b.sources().stream().map(QueryBlock.Source::name).toList()));
    }
}
