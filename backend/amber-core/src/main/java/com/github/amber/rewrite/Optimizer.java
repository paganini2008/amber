package com.github.amber.rewrite;

import com.github.amber.ai.AiCheck;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.ir.ScriptIrBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * "Apply all" for rule-based rewrites: per statement, every non-conflicting rewrite is applied together, then
 * the suggester runs again on the new text (offsets shift, and one rewrite can enable another) until nothing
 * changes or {@value #MAX_PASSES} passes are done. Equivalent rewrites ({@link Safety#EQUIVALENT},
 * {@link Safety#EQUIVALENT_WITH_METADATA}) are applied by default; rewrites that change behavior in edge cases
 * only on request. The final statement is verified against the original (strict parse, structural check);
 * a statement whose combination does not verify keeps its original text and its rewrites are listed as
 * {@code UNVERIFIED}. Nothing is executed and the user's file is never changed.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class Optimizer {

    static final int MAX_PASSES = 4;

    /** A rewrite that was applied; {@code id} is the rewrite id of its pass ({@code 2:R1} for pass 2). */
    public record Applied(String id, String rule, String title, Safety safety) {
    }

    /** A rewrite that was not applied: {@code CONFLICT}, {@code EDGE_CASE}, {@code DISABLED} or {@code UNVERIFIED}. */
    public record Skipped(String id, String rule, String title, String reason) {
    }

    /** The structural check of the final statement against the original. */
    public record Check(String label, List<String> differences) {
    }

    /**
     * @param safety        the weakest safety of the applied rewrites, null when only DDL is suggested
     * @param additionalDdl statements to add (e.g. CREATE INDEX hints of AMB-P001), given by the caller
     */
    public record StatementResult(int statement, String original, String optimized, List<Applied> applied,
                                  List<Skipped> skipped, Safety safety, Check check, List<String> additionalDdl) {
    }

    /** {@code optimizedText}: the whole script with every optimized statement replaced. */
    public record Result(List<StatementResult> statements, String optimizedText) {
    }

    private Optimizer() {
    }

    /**
     * Optimizes the statements of a script.
     *
     * @param statementIds     statement ids (as in {@code analyze}); empty or null = all
     * @param includeEdgeCases also apply {@link Safety#CHANGES_BEHAVIOR_IN_EDGE_CASES} rewrites
     * @param ruleEnabled      the user's rule settings; rewrites of disabled rules are listed as DISABLED
     * @param additionalDdl    per statement id, DDL to suggest alongside (may be empty)
     */
    public static Result optimize(Dialect dialect, String text, Catalog catalog, List<Integer> statementIds,
                                  boolean includeEdgeCases, Predicate<String> ruleEnabled, boolean allowFallback,
                                  Map<Integer, List<String>> additionalDdl) {
        return optimize(dialect, text, catalog, statementIds, includeEdgeCases, ruleEnabled, allowFallback,
                additionalDdl, UnaryOperator.identity());
    }

    /** As above; {@code edited} sees every edited text before it is verified (a seam for tests). */
    static Result optimize(Dialect dialect, String text, Catalog catalog, List<Integer> statementIds,
                           boolean includeEdgeCases, Predicate<String> ruleEnabled, boolean allowFallback,
                           Map<Integer, List<String>> additionalDdl, UnaryOperator<String> edited) {
        Catalog base = catalog == null ? new Catalog() : catalog;
        Predicate<String> enabled = ruleEnabled == null ? r -> true : ruleEnabled;
        DialectFrontend f = DialectFrontends.get(dialect);
        Parses parses = new Parses(f, allowFallback);
        List<Statement> steps0 = steps(parses, text, base);
        if (steps0 == null) {
            return new Result(List.of(), text);
        }
        Set<Integer> wanted = statementIds == null ? Set.of() : Set.copyOf(statementIds);
        Map<Integer, List<Applied>> applied = new TreeMap<>();
        Map<Integer, Map<String, Skipped>> skipped = new TreeMap<>();
        String cur = text;
        for (int pass = 1; pass <= MAX_PASSES; pass++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("cancelled");    // $/cancel
            }
            List<Rewrite> all = RewriteSuggester.suggest(dialect, cur, parses.of(cur), base, statementIds,
                    allowFallback, r -> true);
            List<Rewrite> chosen = new ArrayList<>();
            for (Rewrite r : all) {
                String id = pass == 1 ? r.id() : pass + ":" + r.id();
                Map<String, Skipped> sk = skipped.computeIfAbsent(r.statement(), k -> new LinkedHashMap<>());
                String key = r.rule() + "|" + r.title();
                if (!enabled.test(r.rule())) {
                    sk.putIfAbsent(key, new Skipped(id, r.rule(), r.title(), "DISABLED"));
                } else if (r.safety() == Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES && !includeEdgeCases) {
                    sk.putIfAbsent(key, new Skipped(id, r.rule(), r.title(), "EDGE_CASE"));
                } else if (chosen.stream().anyMatch(c -> overlaps(c, r))) {
                    sk.putIfAbsent(key, new Skipped(id, r.rule(), r.title(), "CONFLICT"));
                } else {
                    chosen.add(r);
                }
            }
            if (chosen.isEmpty()) {
                break;
            }
            Applying next = applyVerified(parses, cur, chosen, steps0.size(), base, edited);
            for (Rewrite r : chosen) {
                String id = pass == 1 ? r.id() : pass + ":" + r.id();
                String key = r.rule() + "|" + r.title();
                if (next.statements().contains(r.statement())) {
                    applied.computeIfAbsent(r.statement(), k -> new ArrayList<>()).add(new Applied(id, r.rule(),
                            r.title(), r.safety()));
                    skipped.getOrDefault(r.statement(), new LinkedHashMap<>()).remove(key);
                } else {
                    skipped.computeIfAbsent(r.statement(), k -> new LinkedHashMap<>()).put(key,
                            new Skipped(id, r.rule(), r.title(), "UNVERIFIED"));
                }
            }
            if (next.statements().isEmpty()) {
                break;
            }
            cur = next.text();
        }
        List<Statement> stepsN = cur.equals(text) ? steps0 : steps(parses, cur, base);
        if (stepsN == null || stepsN.size() != steps0.size()) {
            stepsN = steps0;                        // cannot happen after verified passes; keep the original
            cur = text;
            applied.clear();
        }
        Map<Integer, List<String>> ddl = additionalDdl == null ? Map.of() : additionalDdl;
        Set<Integer> ids = new TreeSet<>(applied.keySet());
        ids.addAll(ddl.keySet());
        List<StatementResult> out = new ArrayList<>();
        Map<Integer, String> replacements = new TreeMap<>();
        for (int id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("cancelled");    // $/cancel
            }
            if ((!wanted.isEmpty() && !wanted.contains(id)) || id < 0 || id >= steps0.size()) {
                continue;
            }
            String original = slice(text, steps0.get(id));
            List<Applied> app = applied.getOrDefault(id, List.of());
            List<Skipped> sk = new ArrayList<>(skipped.getOrDefault(id, Map.of()).values());
            String optimized = app.isEmpty() ? original : slice(cur, stepsN.get(id));
            Check check = null;
            if (!app.isEmpty()) {
                AiCheck.Result r = check(dialect, original, optimized, text, cur, base);
                if (r == null) {
                    for (Applied a : app) {
                        sk.add(new Skipped(a.id(), a.rule(), a.title(), "UNVERIFIED"));
                    }
                    app = List.of();
                    optimized = original;
                } else {
                    check = new Check(r.label().name(), r.differences());
                    replacements.put(id, optimized);
                }
            }
            List<String> extra = ddl.getOrDefault(id, List.of());
            if (app.isEmpty() && extra.isEmpty()) {
                continue;
            }
            Safety safety = app.stream().map(Applied::safety).max(Comparator.naturalOrder()).orElse(null);
            out.add(new StatementResult(id, original, optimized, List.copyOf(app), List.copyOf(sk), safety, check,
                    List.copyOf(extra)));
        }
        return new Result(out, replaceAll(text, steps0, replacements));
    }

    private static boolean overlaps(Rewrite a, Rewrite b) {
        for (Edit x : a.edits()) {
            for (Edit y : b.edits()) {
                if (x.overlaps(y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The text after a pass and the statements whose rewrites it contains. */
    private record Applying(String text, Set<Integer> statements) {
    }

    /**
     * Applies the chosen rewrites; when the whole result does not parse as before, statement by statement
     * (later statements first, so earlier offsets stay valid), keeping only those that do.
     */
    private static Applying applyVerified(Parses parses, String cur, List<Rewrite> chosen, int count,
                                          Catalog base, UnaryOperator<String> edited) {
        Map<Integer, List<Edit>> byStatement = new TreeMap<>(Comparator.reverseOrder());
        for (Rewrite r : chosen) {
            byStatement.computeIfAbsent(r.statement(), k -> new ArrayList<>()).addAll(r.edits());
        }
        List<Edit> edits = new ArrayList<>();
        chosen.forEach(r -> edits.addAll(r.edits()));
        String all = edited.apply(RewriteSuggester.apply(cur, edits));
        List<Statement> s = steps(parses, all, base);
        if (s != null && s.size() == count) {
            return new Applying(all, byStatement.keySet());
        }
        String text = cur;
        Set<Integer> kept = new TreeSet<>();
        for (Map.Entry<Integer, List<Edit>> group : byStatement.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("cancelled");    // $/cancel
            }
            String candidate = edited.apply(RewriteSuggester.apply(text, group.getValue()));
            List<Statement> st = steps(parses, candidate, base);
            if (st != null && st.size() == count) {
                text = candidate;
                kept.add(group.getKey());
            }
        }
        return new Applying(text, kept);
    }

    private static AiCheck.Result check(Dialect dialect, String original, String optimized, String text, String cur,
                                        Catalog base) {
        AiCheck.Result r = AiCheck.check(dialect, original, optimized, Map.of(), base);
        if (r.label() != AiCheck.Label.INVALID_SQL) {
            return r;
        }
        // a body statement that only parses inside its routine: check the whole script instead
        AiCheck.Result whole = AiCheck.check(dialect, text, cur, Map.of(), base);
        return whole.label() == AiCheck.Label.INVALID_SQL ? null : whole;
    }

    /**
     * Parses of the texts one optimization sees, so that a text is not parsed twice in a row: the original
     * (read for its statements, then for suggestions) and the text after each pass (verified, then read by
     * the next pass). Parsing dominates the cost on long input.
     */
    private static final class Parses {
        private final DialectFrontend f;
        private final boolean fallback;
        /** The two latest parses only: a long script's trees are large, and only the latest text is reused. */
        private final Map<String, ScriptParse> done = new LinkedHashMap<>(4, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ScriptParse> eldest) {
                return size() > 2;
            }
        };

        Parses(DialectFrontend f, boolean fallback) {
            this.f = f;
            this.fallback = fallback;
        }

        ScriptParse of(String text) {
            return done.computeIfAbsent(text, t -> f.parse(SourceText.of(t),
                    fallback ? ParseOptions.FALLBACK : ParseOptions.STRICT));
        }
    }

    /** Statements in step order, or null when the text does not parse (strictly unless fallback is allowed). */
    private static List<Statement> steps(Parses parses, String text, Catalog base) {
        ScriptParse p = parses.of(text);
        if (p.status() == ScriptStatus.FAILED || (!parses.fallback && p.status() != ScriptStatus.SUCCESS)) {
            return null;
        }
        return ScriptIrBuilder.build(parses.f, p, base.copy()).flatten();
    }

    private static String slice(String text, Statement st) {
        return text.substring(st.range().start().offset(), st.range().end().offset());
    }

    private static String replaceAll(String text, List<Statement> steps, Map<Integer, String> replacements) {
        List<Integer> ids = new ArrayList<>(replacements.keySet());
        ids.sort(Comparator.comparingInt((Integer i) -> steps.get(i).range().start().offset()).reversed());
        StringBuilder sb = new StringBuilder(text);
        int limit = Integer.MAX_VALUE;
        for (int id : ids) {
            Statement st = steps.get(id);
            if (st.range().end().offset() > limit) {
                continue;                           // nested in a statement replaced already
            }
            sb.replace(st.range().start().offset(), st.range().end().offset(), replacements.get(id));
            limit = st.range().start().offset();
        }
        return sb.toString();
    }
}
