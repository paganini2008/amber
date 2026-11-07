package com.github.amber.diff;

import com.github.amber.core.Dialect;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.IndirectRole;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.diff.DiffResult.ControlFlowChange;
import com.github.amber.diff.DiffResult.Counts;
import com.github.amber.diff.DiffResult.Hunk;
import com.github.amber.diff.DiffResult.Reason;
import com.github.amber.diff.DiffResult.Risk;
import com.github.amber.diff.DiffResult.Side;
import com.github.amber.diff.DiffResult.StatementPair;
import com.github.amber.diff.DiffResult.Status;
import com.github.amber.diff.DiffResult.Summary;
import com.github.amber.diff.StatementAligner.P;
import com.github.amber.diff.Version.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Change impact check: compares two versions of a script structurally and answers "what did my change
 * really change?". Statements are aligned (unchanged, format-only, modified, added, removed, moved), and
 * the impact is reported on objects, column lineage, query shapes, findings and control flow, with a
 * risk level and the reasons for it. Nothing is executed.
 *
 * <pre>{@code
 * DiffResult r = ScriptDiff.compare(Dialect.ORACLE, oldText, newText, Catalog.of(List.of()));
 * }</pre>
 *
 * A version that fails strict parsing is compared with fallback parsing, and the result says so.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class ScriptDiff {

    private static final int MAX_HUNK_TEXT = 300;
    private static final Set<StatementKind> FILTERED_WRITES = EnumSet.of(StatementKind.DELETE, StatementKind.UPDATE,
            StatementKind.MERGE);
    private static final Set<StatementKind.Category> WRITING = EnumSet.of(StatementKind.Category.DML,
            StatementKind.Category.DDL);

    private ScriptDiff() {
    }

    /** Compares two script texts. */
    public static DiffResult compare(Dialect dialect, String before, String after, Catalog catalog) {
        return compare(dialect, new SourceText("before.sql", before), new SourceText("after.sql", after), catalog);
    }

    /**
     * Compares two scripts.
     *
     * @param catalog imported metadata (packs, DDL files, online); never modified, may be empty
     */
    public static DiffResult compare(Dialect dialect, SourceText before, SourceText after, Catalog catalog) {
        long started = System.nanoTime();
        Catalog cat = catalog == null ? Catalog.of(List.of()) : catalog;
        CompletableFuture<Version> vb = CompletableFuture.supplyAsync(() -> Version.analyze(dialect, before, cat));
        Version va = Version.analyze(dialect, after, cat);
        Version vbv;
        try {
            vbv = vb.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
        return compare(vbv, va, started);
    }

    static DiffResult compare(Version before, Version after, long started) {
        List<P> roots = StatementAligner.align(before.roots, after.roots);
        DiffContext ctx = new DiffContext(before, after, roots);
        statementRules(ctx);
        List<ControlFlowChange> flow = controlFlow(ctx);
        var objects = ObjectComparer.compare(ctx);
        var lineage = LineageComparer.compare(ctx);
        var shapes = ShapeComparer.compare(ctx);
        var findings = FindingComparer.compare(ctx);
        List<StatementPair> statements = roots.stream().map(p -> pair(ctx, p)).toList();
        Counts counts = counts(ctx.pairs);
        List<String> notes = notes(before, after);
        List<Reason> reasons = reasons(ctx, counts);
        Risk risk = reasons.stream().map(Reason::level).max(Comparator.naturalOrder()).orElse(Risk.LOW);
        int newFindings = (int) findings.stream().filter(f -> f.change().equals("NEW")).count();
        Summary summary = new Summary(risk, headline(counts, risk, reasons), counts, objects.size(),
                lineage.columns().size(), lineage.indirect().size(), shapes.size(), newFindings,
                findings.size() - newFindings, flow.size(), reasons, notes);
        long ms = (System.nanoTime() - started) / 1_000_000;
        return new DiffResult(after.dialect.id(), side(before), side(after), summary, statements, objects, lineage,
                shapes, findings, flow, ms);
    }

    // ----- statement-level risk rules --------------------------------------------------------------

    private static void statementRules(DiffContext ctx) {
        for (P p : ctx.pairs) {
            switch (p.status) {
                case MODIFIED -> modifiedRules(ctx, p);
                case ADDED -> addedRules(ctx, p.b);
                case REMOVED -> {
                    if (!p.a.container() && WRITING.contains(p.a.st.kind().category())) {
                        ctx.reason(Risk.MEDIUM, "STATEMENT_REMOVED", p.a.label() + " was removed", p.a.id, null);
                    }
                }
                case MOVED -> {
                    if (!p.crossLevel && WRITING.contains(p.b.st.kind().category())) {
                        ctx.reason(Risk.MEDIUM, "ORDER_CHANGED", p.b.label() + " now runs at another point of the script",
                                p.a.id, p.b.id);
                    }
                }
                default -> {
                }
            }
            if (p.status != Status.UNCHANGED && p.status != Status.FORMAT_ONLY
                    && ((p.a != null && p.a.st.kind() == StatementKind.UNPARSED)
                    || (p.b != null && p.b.st.kind() == StatementKind.UNPARSED))) {
                ctx.reason(Risk.MEDIUM, "UNPARSED_CHANGE", "A changed statement could not be parsed; its impact is unknown",
                        p.a == null ? null : p.a.id, p.b == null ? null : p.b.id);
            }
        }
    }

    private static void modifiedRules(DiffContext ctx, P p) {
        StatementKind k = p.b.st.kind();
        if (!FILTERED_WRITES.contains(k) || !FILTERED_WRITES.contains(p.a.st.kind())) {
            return;
        }
        int whereB = SqlTokens.countWord(p.a.tokens(), "WHERE");
        int whereA = SqlTokens.countWord(p.b.tokens(), "WHERE");
        Set<String> fb = filters(p.a);
        Set<String> fa = filters(p.b);
        Set<String> lost = new TreeSet<>(fb);
        lost.removeAll(fa);
        if (whereB > 0 && whereA == 0) {
            ctx.reason(Risk.HIGH, "FILTER_REMOVED", p.b.label() + " lost its WHERE clause"
                    + (fb.isEmpty() ? "" : " (filtered on " + String.join(", ", fb) + ")")
                    + ": it now affects every row", p.a.id, p.b.id);
        } else if (!lost.isEmpty()) {
            ctx.reason(Risk.HIGH, "FILTER_REMOVED", p.b.label() + " no longer filters on " + String.join(", ", lost),
                    p.a.id, p.b.id);
        }
    }

    /** Columns that filter the rows a statement writes. */
    private static Set<String> filters(Node n) {
        Set<String> out = new TreeSet<>();
        if (n.st.lineage() != null) {
            for (ColumnEdge e : n.st.lineage().edges()) {
                if (!e.isDirect() && e.role() == IndirectRole.FILTER) {
                    out.add((e.from().owner() + "." + e.from().column()).toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static void addedRules(DiffContext ctx, Node n) {
        StatementKind k = n.st.kind();
        boolean whereless = SqlTokens.countWord(n.tokens(), "WHERE") == 0;
        switch (k) {
            case DROP -> ctx.reason(createdIn(ctx.after, n.target) ? Risk.MEDIUM : Risk.HIGH, "DROP_ADDED",
                    "New " + n.label() + (createdIn(ctx.after, n.target) ? "" : ", an object the script does not create"),
                    null, n.id);
            case TRUNCATE -> ctx.reason(createdIn(ctx.after, n.target) ? Risk.MEDIUM : Risk.HIGH, "TRUNCATE_ADDED",
                    "New " + n.label(), null, n.id);
            case DELETE, UPDATE -> ctx.reason(whereless && !createdIn(ctx.after, n.target) ? Risk.HIGH : Risk.MEDIUM,
                    k + "_ADDED", "New " + n.label() + (whereless ? " without WHERE" : ""), null, n.id);
            default -> {
                if (WRITING.contains(k.category())) {
                    ctx.reason(Risk.MEDIUM, "STATEMENT_ADDED", "New " + n.label(), null, n.id);
                }
            }
        }
    }

    private static boolean createdIn(Version v, String key) {
        for (ObjectLifecycle l : v.lifecycles) {
            if (l.name().key().equals(key)) {
                return l.createdInScript();
            }
        }
        return false;
    }

    // ----- control flow ----------------------------------------------------------------------------

    private static List<ControlFlowChange> controlFlow(DiffContext ctx) {
        List<ControlFlowChange> out = new ArrayList<>();
        for (P p : ctx.pairs) {
            if (!p.crossLevel) {
                continue;
            }
            List<String> bp = p.a.path();
            List<String> ap = p.b.path();
            List<String> bs = bp.stream().map(ScriptDiff::stripLine).toList();
            List<String> as = ap.stream().map(ScriptDiff::stripLine).toList();
            String what = p.b.label();
            String msg;
            if (as.size() > bs.size() && as.subList(0, bs.size()).equals(bs)) {
                msg = what + " moved into " + String.join(" > ", ap.subList(bs.size(), ap.size()));
            } else if (bs.size() > as.size() && bs.subList(0, as.size()).equals(as)) {
                msg = what + " moved out of " + String.join(" > ", bp.subList(as.size(), bp.size()));
            } else {
                msg = what + " moved from " + (bp.isEmpty() ? "top level" : String.join(" > ", bp)) + " to "
                        + (ap.isEmpty() ? "top level" : String.join(" > ", ap));
            }
            out.add(new ControlFlowChange(p.b.st.kind().name(), p.b.targetDisplay, bp, ap, msg, p.a.id, p.b.id,
                    p.a.st.range(), p.b.st.range()));
            ctx.reason(Risk.MEDIUM, "CONTROL_FLOW_CHANGED", msg, p.a.id, p.b.id);
        }
        return out;
    }

    private static String stripLine(String pathElement) {
        int i = pathElement.lastIndexOf(" (line ");
        return i < 0 ? pathElement : pathElement.substring(0, i);
    }

    // ----- output ----------------------------------------------------------------------------------

    private static StatementPair pair(DiffContext ctx, P p) {
        Node any = p.b != null ? p.b : p.a;
        List<Hunk> hunks = p.status == Status.MODIFIED ? hunks(ctx, p) : List.of();
        return new StatementPair(p.status, any.st.kind().name(), any.targetDisplay,
                p.a == null ? null : p.a.id, p.b == null ? null : p.b.id,
                p.a == null ? null : p.a.st.range(), p.b == null ? null : p.b.st.range(),
                p.a == null ? null : p.a.st.summary(), p.b == null ? null : p.b.st.summary(),
                round(p.similarity), p.reordered, hunks, p.children.stream().map(c -> pair(ctx, c)).toList());
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    /** Token-level differences of a modified pair (children of containers excluded where they are paired). */
    static List<Hunk> hunks(DiffContext ctx, P p) {
        List<SqlTokens.Tok> ta = own(p.a);
        List<SqlTokens.Tok> tb = own(p.b);
        Map<String, Integer> ids = new HashMap<>();
        int[] xa = ta.stream().mapToInt(t -> ids.computeIfAbsent(t.norm(), k -> ids.size())).toArray();
        int[] xb = tb.stream().mapToInt(t -> ids.computeIfAbsent(t.norm(), k -> ids.size())).toArray();
        int[] match = Sequences.match(xa, xb);
        List<Hunk> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < xa.length || j < xb.length) {
            if (i < xa.length && match[i] == j) {
                i++;
                j++;
                continue;
            }
            int i0 = i;
            int j0 = j;
            while (i < xa.length && match[i] < 0) {
                i++;
            }
            int nextJ = i < xa.length ? match[i] : xb.length;
            j = Math.max(j, nextJ);
            out.add(hunk(ctx, ta, tb, i0, i, j0, j));
        }
        return out;
    }

    /** Tokens of a statement outside its children (children carry their own hunks). */
    private static List<SqlTokens.Tok> own(Node n) {
        if (n.children.isEmpty()) {
            return n.tokens();
        }
        List<SqlTokens.Tok> out = new ArrayList<>();
        int c = 0;
        for (SqlTokens.Tok t : n.tokens()) {
            while (c < n.children.size() && n.children.get(c).st.range().end().offset() <= t.start()) {
                c++;
            }
            boolean inside = c < n.children.size() && n.children.get(c).st.range().start().offset() <= t.start();
            if (!inside) {
                out.add(t);
            }
        }
        return out;
    }

    private static Hunk hunk(DiffContext ctx, List<SqlTokens.Tok> ta, List<SqlTokens.Tok> tb, int i0, int i1, int j0,
                             int j1) {
        TextRange br = span(ctx.before.source, ta, i0, i1, ctx.before.source.length());
        TextRange ar = span(ctx.after.source, tb, j0, j1, ctx.after.source.length());
        return new Hunk(br, ar, clip(ctx.before.source.slice(br)), clip(ctx.after.source.slice(ar)));
    }

    private static TextRange span(SourceText src, List<SqlTokens.Tok> t, int from, int to, int textEnd) {
        if (from < to) {
            return src.range(t.get(from).start(), t.get(to - 1).end());
        }
        int at = from < t.size() ? t.get(from).start() : (t.isEmpty() ? 0 : t.getLast().end());
        return src.range(Math.min(at, textEnd), Math.min(at, textEnd));
    }

    private static String clip(String s) {
        return s.length() <= MAX_HUNK_TEXT ? s : s.substring(0, MAX_HUNK_TEXT) + "…";
    }

    private static Counts counts(List<P> pairs) {
        int[] c = new int[Status.values().length];
        pairs.forEach(p -> c[p.status.ordinal()]++);
        return new Counts(c[Status.UNCHANGED.ordinal()], c[Status.FORMAT_ONLY.ordinal()], c[Status.MODIFIED.ordinal()],
                c[Status.ADDED.ordinal()], c[Status.REMOVED.ordinal()], c[Status.MOVED.ordinal()]);
    }

    private static Side side(Version v) {
        return new Side(v.source.name(), v.parse.status().name(), v.fallback, v.source.lineCount(), v.steps.size(),
                v.unparsed(), v.parse.diagnostics());
    }

    private static List<String> notes(Version before, Version after) {
        List<String> notes = new ArrayList<>();
        for (Version v : List.of(before, after)) {
            String which = v == before ? "The old version" : "The new version";
            if (v.fallback) {
                notes.add(which + " has syntax errors and was compared with fallback parsing; " + v.unparsed()
                        + " statement(s) were only scanned, so their impact is approximate.");
            }
            if (v.findingsError != null) {
                notes.add("Findings of " + which.toLowerCase(Locale.ROOT).substring(4) + " could not be computed: "
                        + v.findingsError);
            }
        }
        return notes;
    }

    private static List<Reason> reasons(DiffContext ctx, Counts counts) {
        List<Reason> all = new ArrayList<>(ctx.reasons);
        if (counts.formatOnly() > 0) {
            all.add(new Reason(Risk.LOW, "FORMAT_ONLY", counts.formatOnly()
                    + " statement(s) differ only in whitespace, comments or keyword case", null, null, null, null));
        }
        if (counts.modified() > 0 && all.stream().noneMatch(r -> r.level() != Risk.LOW)) {
            all.add(new Reason(Risk.LOW, "MODIFIED", counts.modified()
                    + " statement(s) modified without a structural impact Amber can see", null, null, null, null));
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Reason> unique = new ArrayList<>();
        for (Reason r : all) {
            if (seen.add(r.level() + "|" + r.code() + "|" + r.message() + "|" + r.beforeStatement() + "|"
                    + r.afterStatement())) {
                unique.add(r);
            }
        }
        unique.sort(Comparator.comparing(Reason::level).reversed());
        return unique;
    }

    private static String headline(Counts c, Risk risk, List<Reason> reasons) {
        int changed = c.modified() + c.added() + c.removed() + c.moved();
        if (changed == 0) {
            return c.formatOnly() > 0 ? "Formatting only: " + c.formatOnly() + " statement(s) reformatted; risk LOW"
                    : "No changes";
        }
        List<String> parts = new ArrayList<>();
        if (c.modified() > 0) {
            parts.add(c.modified() + " modified");
        }
        if (c.added() > 0) {
            parts.add(c.added() + " added");
        }
        if (c.removed() > 0) {
            parts.add(c.removed() + " removed");
        }
        if (c.moved() > 0) {
            parts.add(c.moved() + " moved");
        }
        String top = reasons.isEmpty() ? "" : ": " + reasons.getFirst().message();
        return changed + " statement(s) changed (" + String.join(", ", parts) + "); risk " + risk + top;
    }
}
