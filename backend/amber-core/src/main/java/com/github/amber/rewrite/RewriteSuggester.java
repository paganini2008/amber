package com.github.amber.rewrite;

import com.github.amber.ai.AiCheck;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule-based rewrite suggestions: for findings with a deterministic fix, the concrete edited SQL, like an
 * IDE quick fix, without any AI. Nothing is executed and nothing is applied to the user's file.
 *
 * <p>Every suggestion is verified before it is returned: its edits are applied, the result must parse
 * strictly in the same dialect, and it is compared structurally with the original
 * ({@link AiCheck#check}). A rewrite that does not parse is dropped; a rewrite declared equivalent whose
 * structure changes in a way the rule does not expect is dropped too.
 *
 * <p>Rules: AMB-S002 LEFT JOIN made inner by WHERE, AMB-S004 NOT IN subquery, AMB-S008 day function on a
 * filtered column, AMB-S009 UNION, AMB-S010 SELECT * written to a table, AMB-S011 DISTINCT with GROUP BY,
 * AMB-S012 ORDER BY in a subquery, AMB-D002 repeated subquery. A {@code -- amber:ignore RULE} comment that
 * suppresses a finding also suppresses its rewrite.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class RewriteSuggester {

    private static final List<RewriteRule> RULES = List.of(new LeftJoinRule(), new NotInRule(), new DateRangeRule(),
            new UnionAllRule(), new SelectStarRule(), new DistinctGroupByRule(), new SubqueryOrderByRule(),
            new RepeatedSubqueryRule());

    private RewriteSuggester() {
    }

    /**
     * Rewrites for the statements of a script.
     *
     * @param dialect      the script's dialect
     * @param text         the whole script; edit offsets refer to it
     * @param catalog      known metadata (packs, DDL files, online mode); may be empty, never changed
     * @param statementIds statement ids (step indexes, as in {@code analyze}); empty or null means all
     * @return verified rewrites in statement and source order; empty when the script does not parse strictly
     */
    public static List<Rewrite> suggest(Dialect dialect, String text, Catalog catalog, List<Integer> statementIds) {
        return suggest(dialect, text, catalog, statementIds, false);
    }

    /**
     * As {@link #suggest(Dialect, String, Catalog, List)}; with {@code allowFallback} a script that does not
     * parse strictly is read in fallback mode (statement ids then match {@code analyze} with
     * {@code allowFallback: true}). Unparsed statements get no rewrites, and each rewrite must still parse
     * strictly on its own: one that could only be checked within the whole script is dropped.
     */
    public static List<Rewrite> suggest(Dialect dialect, String text, Catalog catalog, List<Integer> statementIds,
                                        boolean allowFallback) {
        DialectFrontend f = DialectFrontends.get(dialect);
        SourceText source = SourceText.of(text);
        ScriptParse parse = f.parse(source, allowFallback ? ParseOptions.FALLBACK : ParseOptions.STRICT);
        if (parse.status() == ScriptStatus.FAILED || (!allowFallback && parse.status() != ScriptStatus.SUCCESS)) {
            return List.of();
        }
        Catalog base = catalog == null ? new Catalog() : catalog;
        Catalog scriptCatalog = base.copy();
        Script script = ScriptIrBuilder.build(f, parse, scriptCatalog);
        List<Statement> steps = script.flatten();
        Map<Statement, Statement> parents = new IdentityHashMap<>();
        for (Statement st : steps) {
            st.children().forEach(c -> parents.put(c, st));
        }
        Map<String, Integer> creates = RewriteContext.counts(steps, false);
        Map<String, Integer> firstCreate = RewriteContext.counts(steps, true);
        Verifier verifier = new Verifier(dialect, f, text, scriptCatalog, base,
                parse.diagnostics().size() + script.diagnostics().size());
        Set<Integer> wanted = statementIds == null ? Set.of() : Set.copyOf(statementIds);
        List<Rewrite> out = new ArrayList<>();
        int[] counter = {0};
        for (int id = 0; id < steps.size(); id++) {
            Statement st = steps.get(id);
            if ((!wanted.isEmpty() && !wanted.contains(id)) || st.kind().isContainer()
                    || st.kind() == StatementKind.CLIENT_COMMAND || st.kind() == StatementKind.DATA
                    || st.kind() == StatementKind.UNPARSED) {
                continue;
            }
            Sql sql = new Sql(dialect, text, st.range().start().offset(), st.range().end().offset());
            if (sql.size() == 0) {
                continue;
            }
            RewriteContext ctx = new RewriteContext(dialect, source, st, id, sql, scriptCatalog, parents.get(st),
                    creates, firstCreate, steps);
            out.addAll(forStatement(ctx, verifier, counter));
        }
        return out;
    }

    private static List<Rewrite> forStatement(RewriteContext ctx, Verifier verifier, int[] counter) {
        List<Candidate> candidates = new ArrayList<>();
        for (RewriteRule rule : RULES) {
            if (ctx.ignored(rule.id())) {
                continue;
            }
            try {
                candidates.addAll(rule.propose(ctx));
            } catch (RuntimeException e) {
                // a rule that cannot read an unusual statement proposes nothing; suggestions are optional
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::firstOffset));
        List<Rewrite> accepted = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!internallyDisjoint(c.edits())) {
                continue;
            }
            Verifier.Outcome v = verifier.verify(ctx, c);
            if (v == null) {
                continue;
            }
            String conflict = null;
            for (Rewrite r : accepted) {
                if (r.conflictsWith() == null && overlaps(r.edits(), c.edits())) {
                    conflict = r.id();
                    break;
                }
            }
            accepted.add(new Rewrite("R" + (++counter[0]), c.rule(), c.title(), c.explanation(), ctx.id(), c.edits(),
                    v.rewrittenStatement(), c.safety(), c.safetyNote(), v.label(), v.differences(), conflict));
        }
        return accepted;
    }

    private static boolean internallyDisjoint(List<Edit> edits) {
        for (int i = 0; i < edits.size(); i++) {
            for (int j = i + 1; j < edits.size(); j++) {
                if (edits.get(i).overlaps(edits.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean overlaps(List<Edit> a, List<Edit> b) {
        for (Edit x : a) {
            for (Edit y : b) {
                if (x.overlaps(y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Applies edits to a text (offsets refer to {@code text}).
     *
     * @throws IllegalArgumentException when two edits overlap or an edit lies outside the text
     */
    public static String apply(String text, List<Edit> edits) {
        return apply(text, edits, 0);
    }

    /** Applies edits whose offsets are relative to a text that starts at {@code base} in the script. */
    static String apply(String text, List<Edit> edits, int base) {
        List<Edit> sorted = edits.stream().sorted(Comparator.comparingInt(Edit::start)).toList();
        StringBuilder sb = new StringBuilder(text.length() + 64);
        int pos = 0;
        Edit prev = null;
        for (Edit e : sorted) {
            int s = e.start() - base;
            int en = e.end() - base;
            if (s < pos || en > text.length() || (prev != null && prev.overlaps(e))) {
                throw new IllegalArgumentException("Overlapping or out-of-range edit at offset " + e.start());
            }
            sb.append(text, pos, s).append(e.replacement());
            pos = en;
            prev = e;
        }
        return sb.append(text, pos, text.length()).toString();
    }
}
