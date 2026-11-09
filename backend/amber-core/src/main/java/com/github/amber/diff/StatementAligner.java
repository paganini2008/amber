package com.github.amber.diff;

import com.github.amber.diff.DiffResult.Status;
import com.github.amber.diff.Version.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Lines up the statements of two versions, level by level:
 * <ol>
 *   <li>an LCS over statement signatures (kind, target, normalized-text hash) gives UNCHANGED and
 *       FORMAT_ONLY pairs;</li>
 *   <li>leftovers with an equal signature elsewhere are MOVED;</li>
 *   <li>leftovers between the same anchors with the same kind and target and similar tokens (or the same
 *       defined name, e.g. a procedure) are MODIFIED, then the same across the whole level;</li>
 *   <li>children of paired containers are aligned recursively;</li>
 *   <li>finally ADDED and REMOVED statements anywhere in the tree are matched across containers: those are
 *       statements moved into or out of an IF, LOOP, handler or block.</li>
 * </ol>
 * Only hashes are compared until a pair is a candidate, so large scripts stay fast.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class StatementAligner {

    static final double MODIFIED_THRESHOLD = 0.5;
    static final double RETARGET_THRESHOLD = 0.6;
    /** Similarity comparisons per bucket are capped so a level of thousands of statements stays linear-ish. */
    private static final int MAX_CANDIDATES = 64;

    /** A pair under construction. */
    static final class P {
        Status status;
        final Node a;
        final Node b;
        double similarity;
        boolean reordered;
        boolean crossLevel;
        List<P> children = new ArrayList<>();
        List<P> owner;

        P(Status status, Node a, Node b, double similarity) {
            this.status = status;
            this.a = a;
            this.b = b;
            this.similarity = similarity;
        }

        /** Visits this pair and nested pairs. */
        void forEach(Consumer<P> visitor) {
            visitor.accept(this);
            for (P c : new ArrayList<>(children)) {
                c.forEach(visitor);
            }
        }
    }

    private StatementAligner() {
    }

    static List<P> align(List<Node> before, List<Node> after) {
        List<P> roots = level(before, after);
        crossLevel(roots);
        return roots;
    }

    // ----- one level -------------------------------------------------------------------------------

    static List<P> level(List<Node> as, List<Node> bs) {
        Map<String, Integer> ids = new HashMap<>();
        int[] sa = new int[as.size()];
        int[] sb = new int[bs.size()];
        for (int i = 0; i < sa.length; i++) {
            sa[i] = ids.computeIfAbsent(as.get(i).signature(), k -> ids.size());
        }
        for (int j = 0; j < sb.length; j++) {
            sb[j] = ids.computeIfAbsent(bs.get(j).signature(), k -> ids.size());
        }
        int[] match = Sequences.match(sa, sb);
        P[] byA = new P[as.size()];
        P[] byB = new P[bs.size()];
        for (int i = 0; i < match.length; i++) {
            if (match[i] >= 0) {
                Node a = as.get(i);
                Node b = bs.get(match[i]);
                P p = new P(a.raw().equals(b.raw()) ? Status.UNCHANGED : Status.FORMAT_ONLY, a, b, 1);
                byA[i] = p;
                byB[match[i]] = p;
            }
        }
        int[] anchorOfA = anchors(match);
        // equal signature elsewhere: moved
        Map<String, Deque<Integer>> freeA = new LinkedHashMap<>();
        for (int i = 0; i < sa.length; i++) {
            if (byA[i] == null) {
                freeA.computeIfAbsent(as.get(i).signature(), k -> new ArrayDeque<>()).add(i);
            }
        }
        for (int j = 0; j < sb.length; j++) {
            Deque<Integer> q = byB[j] == null ? freeA.get(bs.get(j).signature()) : null;
            if (q != null && !q.isEmpty()) {
                int i = q.poll();
                P p = new P(Status.MOVED, as.get(i), bs.get(j), 1);
                p.reordered = true;
                byA[i] = p;
                byB[j] = p;
            }
        }
        // similar statements between the same anchors, then anywhere on the level
        int[] anchorOfB = new int[bs.size()];
        int last = -1;
        int[] matchB = new int[bs.size()];
        Arrays.fill(matchB, -1);
        for (int i = 0; i < match.length; i++) {
            if (match[i] >= 0) {
                matchB[match[i]] = i;
            }
        }
        for (int j = 0; j < bs.size(); j++) {
            if (matchB[j] >= 0) {
                last = matchB[j];
            }
            anchorOfB[j] = last;
        }
        pairSimilar(as, bs, byA, byB, anchorOfA, anchorOfB, true);
        pairSimilar(as, bs, byA, byB, anchorOfA, anchorOfB, false);
        // assemble in the new version's order, removed statements after the anchor that preceded them
        List<Object[]> keyed = new ArrayList<>();
        for (int j = 0; j < bs.size(); j++) {
            P p = byB[j] != null ? byB[j] : new P(Status.ADDED, null, bs.get(j), 0);
            keyed.add(new Object[]{p, j, 0, j});
        }
        for (int i = 0; i < as.size(); i++) {
            if (byA[i] == null) {
                int anchor = anchorOfA[i];
                int bPos = anchor < 0 ? -1 : match[anchor];
                keyed.add(new Object[]{new P(Status.REMOVED, as.get(i), null, 0), bPos, 1, i});
            }
        }
        keyed.sort(Comparator.<Object[]>comparingInt(o -> (int) o[1]).thenComparingInt(o -> (int) o[2])
                .thenComparingInt(o -> (int) o[3]));
        List<P> out = new ArrayList<>();
        for (Object[] o : keyed) {
            P p = (P) o[0];
            p.owner = out;
            out.add(p);
            p.children = childrenOf(p);
        }
        return out;
    }

    /** For each index of a, the closest preceding index of a that the LCS matched (-1 if none). */
    private static int[] anchors(int[] match) {
        int[] out = new int[match.length];
        int last = -1;
        for (int i = 0; i < match.length; i++) {
            out[i] = last;
            if (match[i] >= 0) {
                last = i;
                out[i] = i;
            }
        }
        return out;
    }

    private static void pairSimilar(List<Node> as, List<Node> bs, P[] byA, P[] byB, int[] anchorOfA,
                                    int[] anchorOfB, boolean sameGap) {
        Map<String, List<Integer>> freeB = new HashMap<>();
        Map<String, List<Integer>> freeBByKind = new HashMap<>();
        for (int j = 0; j < bs.size(); j++) {
            if (byB[j] == null) {
                freeB.computeIfAbsent(bs.get(j).st.kind() + "|" + bs.get(j).target, k -> new ArrayList<>()).add(j);
                freeBByKind.computeIfAbsent(bs.get(j).st.kind().name(), k -> new ArrayList<>()).add(j);
            }
        }
        // statements of one kind and target that are alone in their gap on both sides pair whatever changed
        Map<String, Integer> gapCount = new HashMap<>();
        if (sameGap) {
            for (int j = 0; j < bs.size(); j++) {
                if (byB[j] == null) {
                    gapCount.merge("b|" + anchorOfB[j] + "|" + bs.get(j).st.kind() + "|" + bs.get(j).target, 1, Integer::sum);
                }
            }
            for (int i = 0; i < as.size(); i++) {
                if (byA[i] == null) {
                    gapCount.merge("a|" + anchorOfA[i] + "|" + as.get(i).st.kind() + "|" + as.get(i).target, 1, Integer::sum);
                }
            }
        }
        List<double[]> candidates = new ArrayList<>();        // {similarity, i, j}
        for (int i = 0; i < as.size(); i++) {
            if (byA[i] != null) {
                continue;
            }
            Node a = as.get(i);
            List<Integer> same = freeB.getOrDefault(a.st.kind() + "|" + a.target, List.of());
            int tried = 0;
            for (int j : same) {
                if ((sameGap && anchorOfB[j] != anchorOfA[i]) || tried++ >= MAX_CANDIDATES) {
                    continue;
                }
                Node b = bs.get(j);
                double sim = SqlTokens.similarity(a.tokens(), b.tokens());
                boolean sameDefinition = a.definesName && !a.target.isEmpty();
                String gk = anchorOfA[i] + "|" + a.st.kind() + "|" + a.target;
                boolean alone = sameGap && gapCount.getOrDefault("a|" + gk, 0) == 1
                        && gapCount.getOrDefault("b|" + gk, 0) == 1;
                if (sim >= MODIFIED_THRESHOLD || sameDefinition || alone) {
                    candidates.add(new double[]{sim + (sameDefinition ? 1 : 0), i, j});
                }
            }
            if (!sameGap) {
                continue;
            }
            // same kind, another target (e.g. INSERT now into another table): stricter, same gap only
            tried = 0;
            for (int j : freeBByKind.getOrDefault(a.st.kind().name(), List.of())) {
                Node b = bs.get(j);
                if (anchorOfB[j] != anchorOfA[i] || b.target.equals(a.target) || tried++ >= MAX_CANDIDATES) {
                    continue;
                }
                double sim = SqlTokens.similarity(a.tokens(), b.tokens());
                if (sim >= RETARGET_THRESHOLD) {
                    candidates.add(new double[]{sim - 0.5, i, j});      // prefer same-target pairs
                }
            }
        }
        candidates.sort((x, y) -> Double.compare(y[0], x[0]));
        for (double[] c : candidates) {
            int i = (int) c[1];
            int j = (int) c[2];
            if (byA[i] == null && byB[j] == null) {
                Node a = as.get(i);
                Node b = bs.get(j);
                P p = new P(Status.MODIFIED, a, b, SqlTokens.similarity(a.tokens(), b.tokens()));
                p.reordered = !sameGap;
                byA[i] = p;
                byB[j] = p;
            }
        }
    }

    private static List<P> childrenOf(P p) {
        List<Node> ac = p.a == null ? List.of() : p.a.children;
        List<Node> bc = p.b == null ? List.of() : p.b.children;
        if (ac.isEmpty() && bc.isEmpty()) {
            return new ArrayList<>();
        }
        return level(ac, bc);
    }

    // ----- across containers -----------------------------------------------------------------------

    /** Pairs ADDED and REMOVED statements found under different containers. */
    private static void crossLevel(List<P> roots) {
        List<P> removed = new ArrayList<>();
        List<P> added = new ArrayList<>();
        for (P r : roots) {
            r.forEach(p -> {
                if (p.status == Status.REMOVED) {
                    removed.add(p);
                } else if (p.status == Status.ADDED) {
                    added.add(p);
                }
            });
        }
        if (removed.isEmpty() || added.isEmpty()) {
            return;
        }
        Set<P> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        // 1. equal signature (containers included, outermost first)
        Map<String, Deque<P>> bySig = new LinkedHashMap<>();
        for (P r : removed) {
            bySig.computeIfAbsent(r.a.signature(), k -> new ArrayDeque<>()).add(r);
        }
        for (P ad : added) {
            if (consumed.contains(ad)) {
                continue;
            }
            Deque<P> q = bySig.get(ad.b.signature());
            P r = null;
            while (q != null && !q.isEmpty() && r == null) {
                P c = q.poll();
                if (!anyConsumed(c, consumed)) {
                    r = c;
                }
            }
            if (r != null) {
                repair(r, ad, Status.MOVED, 1, consumed);
            }
        }
        // 2. similar leaves with the same kind and target
        Map<String, List<P>> freeR = new HashMap<>();
        for (P r : removed) {
            if (!consumed.contains(r) && !r.a.container()) {
                freeR.computeIfAbsent(r.a.st.kind() + "|" + r.a.target, k -> new ArrayList<>()).add(r);
            }
        }
        List<Object[]> candidates = new ArrayList<>();
        for (P ad : added) {
            if (consumed.contains(ad) || ad.b.container()) {
                continue;
            }
            int tried = 0;
            for (P r : freeR.getOrDefault(ad.b.st.kind() + "|" + ad.b.target, List.of())) {
                if (tried++ >= MAX_CANDIDATES) {
                    break;
                }
                double sim = SqlTokens.similarity(r.a.tokens(), ad.b.tokens());
                if (sim >= MODIFIED_THRESHOLD) {
                    candidates.add(new Object[]{sim, r, ad});
                }
            }
        }
        candidates.sort((x, y) -> Double.compare((double) y[0], (double) x[0]));
        for (Object[] c : candidates) {
            P r = (P) c[1];
            P ad = (P) c[2];
            if (!consumed.contains(r) && !consumed.contains(ad)) {
                repair(r, ad, Status.MODIFIED, (double) c[0], consumed);
            }
        }
    }

    /** Replaces the ADDED entry by a pair with the REMOVED statement, and drops the REMOVED entry. */
    private static void repair(P r, P ad, Status status, double sim, Set<P> consumed) {
        r.forEach(consumed::add);
        ad.forEach(consumed::add);
        P p = new P(status, r.a, ad.b, sim);
        p.reordered = true;
        p.crossLevel = true;
        p.owner = ad.owner;
        p.children = childrenOf(p);
        for (P c : p.children) {
            c.forEach(consumed::add);
        }
        int at = indexOf(ad.owner, ad);
        ad.owner.set(at, p);
        r.owner.remove(indexOf(r.owner, r));
    }

    private static boolean anyConsumed(P p, Set<P> consumed) {
        boolean[] any = {false};
        p.forEach(c -> any[0] |= consumed.contains(c));
        return any[0];
    }

    private static int indexOf(List<P> list, P p) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == p) {
                return i;
            }
        }
        throw new IllegalStateException("pair not in its list");
    }
}
