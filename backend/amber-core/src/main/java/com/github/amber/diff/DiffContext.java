package com.github.amber.diff;

import com.github.amber.core.source.TextRange;
import com.github.amber.diff.DiffResult.Reason;
import com.github.amber.diff.DiffResult.Risk;
import com.github.amber.diff.DiffResult.Status;
import com.github.amber.diff.StatementAligner.P;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What every comparison step shares: both versions, the statement alignment and the risk reasons
 * collected so far.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class DiffContext {

    final Version before;
    final Version after;
    final List<P> roots;
    final List<P> pairs = new ArrayList<>();
    final Map<Integer, Integer> beforeToAfter = new HashMap<>();
    final Map<Integer, Integer> afterToBefore = new HashMap<>();
    final List<Reason> reasons = new ArrayList<>();

    DiffContext(Version before, Version after, List<P> roots) {
        this.before = before;
        this.after = after;
        this.roots = roots;
        for (P r : roots) {
            r.forEach(pairs::add);
        }
        for (P p : pairs) {
            if (p.a != null && p.b != null) {
                beforeToAfter.put(p.a.id, p.b.id);
                afterToBefore.put(p.b.id, p.a.id);
            }
        }
    }

    /** Pairs present on both sides whose text really differs. */
    List<P> modified() {
        return pairs.stream().filter(p -> p.status == Status.MODIFIED).toList();
    }

    void reason(Risk level, String code, String message, Integer beforeId, Integer afterId) {
        TextRange br = beforeId == null ? null : before.range(beforeId);
        TextRange ar = afterId == null ? null : after.range(afterId);
        reasons.add(new Reason(level, code, message, beforeId, afterId, br, ar));
    }

    void reason(Risk level, String code, String message, Integer beforeId, Integer afterId, TextRange br,
                TextRange ar) {
        reasons.add(new Reason(level, code, message, beforeId, afterId, br, ar));
    }
}
