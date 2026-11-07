package com.github.amber.rewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a condition into its top-level AND terms.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Predicates {

    private Predicates() {
    }

    /**
     * The AND terms of the condition in tokens [from, to) at parenthesis depth {@code depth}, as
     * {@code [start, end)} pairs; {@code null} when the condition has a top-level OR (its terms are then
     * not all required) or is empty. The AND of {@code BETWEEN x AND y} does not split.
     */
    static List<int[]> conjuncts(Sql sql, int from, int to, int depth) {
        if (from >= to) {
            return null;
        }
        List<int[]> out = new ArrayList<>();
        int start = from;
        boolean between = false;
        for (int i = from; i < to; i++) {
            if (sql.depth[i] != depth) {
                continue;
            }
            if (sql.word(i, "OR")) {
                return null;
            }
            if (sql.word(i, "BETWEEN")) {
                between = true;
            } else if (sql.word(i, "AND")) {
                if (between) {
                    between = false;
                } else {
                    if (i == start) {
                        return null;
                    }
                    out.add(new int[]{start, i});
                    start = i + 1;
                }
            }
        }
        if (start >= to) {
            return null;
        }
        out.add(new int[]{start, to});
        return out;
    }

    /** True when tokens [from, to) at {@code depth} contain a top-level OR. */
    static boolean hasOr(Sql sql, int from, int to, int depth) {
        for (int i = from; i < to; i++) {
            if (sql.depth[i] == depth && sql.word(i, "OR")) {
                return true;
            }
        }
        return false;
    }
}
