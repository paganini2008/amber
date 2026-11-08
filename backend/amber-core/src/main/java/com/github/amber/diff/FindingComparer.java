package com.github.amber.diff;

import com.github.amber.core.i18n.Messages;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.diff.DiffResult.FindingChange;
import com.github.amber.diff.DiffResult.Risk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Findings introduced or resolved by the change. A finding of the old version and one of the new
 * version are the same when they have the same rule and, in order of preference: their statements are
 * aligned and the flagged text is the same after normalization; their statements are aligned; or the
 * flagged text is the same anywhere.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class FindingComparer {

    private FindingComparer() {
    }

    static List<FindingChange> compare(DiffContext ctx) {
        List<Finding> fb = ctx.before.findings;
        List<Finding> fa = ctx.after.findings;
        boolean[] mb = new boolean[fb.size()];
        boolean[] ma = new boolean[fa.size()];
        long[] hb = fb.stream().mapToLong(f -> textHash(ctx.before, f)).toArray();
        long[] ha = fa.stream().mapToLong(f -> textHash(ctx.after, f)).toArray();
        // the statement of the new version each finding's statement is aligned with (null when none)
        Integer[] targetB = new Integer[fb.size()];
        Integer[] stmtA = new Integer[fa.size()];
        for (int i = 0; i < targetB.length; i++) {
            Integer sb = ctx.before.id(fb.get(i).statement());
            targetB[i] = sb == null ? null : ctx.beforeToAfter.get(sb);
        }
        for (int j = 0; j < stmtA.length; j++) {
            stmtA[j] = ctx.after.id(fa.get(j).statement());
        }
        pass(fb, fa, mb, ma, i -> targetB[i] == null ? null : List.of(targetB[i], hb[i]),
                j -> stmtA[j] == null ? null : List.of(stmtA[j], ha[j]));
        pass(fb, fa, mb, ma, i -> targetB[i], j -> stmtA[j]);
        pass(fb, fa, mb, ma, i -> hb[i], j -> ha[j]);
        List<FindingChange> out = new ArrayList<>();
        for (int j = 0; j < fa.size(); j++) {
            if (!ma[j]) {
                Finding f = fa.get(j);
                Integer sa = ctx.after.id(f.statement());
                Integer sb = sa == null ? null : ctx.afterToBefore.get(sa);
                out.add(new FindingChange("NEW", f.rule(), f.severity().name(), f.title(), f.message(), sb, sa, null,
                        f.range()));
                Risk level = f.severity() == Finding.Severity.HIGH ? Risk.MEDIUM : Risk.LOW;
                ctx.reason(level, "NEW_FINDING", Messages.t("diff.finding.new", Messages.t("diff.severity." + f.severity().name()), f.rule(), f.title()),
                        sb, sa, null, f.range());
            }
        }
        for (int i = 0; i < fb.size(); i++) {
            if (!mb[i]) {
                Finding f = fb.get(i);
                Integer sb = ctx.before.id(f.statement());
                Integer sa = sb == null ? null : ctx.beforeToAfter.get(sb);
                out.add(new FindingChange("RESOLVED", f.rule(), f.severity().name(), f.title(), f.message(), sb, sa,
                        f.range(), null));
            }
        }
        return out;
    }

    /**
     * Pairs each unmatched old finding, in order, with the first unmatched new finding of the same rule and
     * the same key (null keys never match). Buckets by key keep this linear: a script with thousands of
     * findings on both sides used to take tens of seconds when nothing matched.
     */
    private static void pass(List<Finding> fb, List<Finding> fa, boolean[] mb, boolean[] ma,
                             IntFunction<Object> keyBefore, IntFunction<Object> keyAfter) {
        Map<List<Object>, ArrayDeque<Integer>> free = new HashMap<>();
        for (int j = 0; j < fa.size(); j++) {
            Object k = ma[j] ? null : keyAfter.apply(j);
            if (k != null) {
                free.computeIfAbsent(List.of(fa.get(j).rule(), k), x -> new ArrayDeque<>()).add(j);
            }
        }
        for (int i = 0; i < fb.size(); i++) {
            Object k = mb[i] ? null : keyBefore.apply(i);
            ArrayDeque<Integer> q = k == null ? null : free.get(List.of(fb.get(i).rule(), k));
            if (q != null && !q.isEmpty()) {
                int j = q.poll();
                mb[i] = true;
                ma[j] = true;
            }
        }
    }

    private static long textHash(Version v, Finding f) {
        if (f.range() == null) {
            return 0;
        }
        return SqlTokens.hash(SqlTokens.of(v.dialect, v.source.text(), f.range().start().offset(),
                f.range().end().offset()));
    }
}
