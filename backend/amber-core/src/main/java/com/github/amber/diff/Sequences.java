package com.github.amber.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Longest common subsequence of two int sequences (hashes of statements or tokens). Common prefix and
 * suffix are trimmed first, then Myers' O((N+M)·D) algorithm runs on the rest; when the sequences are so
 * different that D exceeds a cap, a patience match on elements unique to both sides is used instead, so
 * time and memory stay bounded for any input.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Sequences {

    /** Above this edit distance Myers' trace would cost too much memory; patience matching takes over. */
    static final int MAX_EDITS = 2000;

    private Sequences() {
    }

    /**
     * Matches elements of {@code a} with equal elements of {@code b}, keeping order on both sides.
     *
     * @return for each index of {@code a}, the matched index of {@code b}, or -1
     */
    static int[] match(int[] a, int[] b) {
        return match(a, b, MAX_EDITS);
    }

    static int[] match(int[] a, int[] b, int maxEdits) {
        int[] result = new int[a.length];
        Arrays.fill(result, -1);
        int n = a.length;
        int m = b.length;
        int prefix = 0;
        while (prefix < n && prefix < m && a[prefix] == b[prefix]) {
            result[prefix] = prefix;
            prefix++;
        }
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix && a[n - 1 - suffix] == b[m - 1 - suffix]) {
            result[n - 1 - suffix] = m - 1 - suffix;
            suffix++;
        }
        int[] ma = Arrays.copyOfRange(a, prefix, n - suffix);
        int[] mb = Arrays.copyOfRange(b, prefix, m - suffix);
        if (ma.length == 0 || mb.length == 0) {
            return result;
        }
        int[] mid = myers(ma, mb, maxEdits);
        if (mid == null) {
            mid = patience(ma, mb);
        }
        for (int i = 0; i < mid.length; i++) {
            if (mid[i] >= 0) {
                result[prefix + i] = prefix + mid[i];
            }
        }
        return result;
    }

    /** Myers' greedy LCS with a stored trace; {@code null} when more than {@code maxEdits} edits are needed. */
    static int[] myers(int[] a, int[] b, int maxEdits) {
        int n = a.length;
        int m = b.length;
        int max = n + m;
        int limit = Math.min(max, maxEdits);
        int off = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        for (int d = 0; d <= limit; d++) {
            int[] snap = new int[2 * d + 1];
            System.arraycopy(v, off - d, snap, 0, 2 * d + 1);
            trace.add(snap);
            for (int k = -d; k <= d; k += 2) {
                int x = (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) ? v[off + k + 1] : v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[x] == b[y]) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    return backtrack(a.length, trace, n, m, d);
                }
            }
        }
        return null;
    }

    private static int[] backtrack(int size, List<int[]> trace, int n, int m, int dEnd) {
        int[] result = new int[size];
        Arrays.fill(result, -1);
        int x = n;
        int y = m;
        for (int d = dEnd; d > 0; d--) {
            int[] prev = trace.get(d);           // values after step d-1, indexed k + d
            int k = x - y;
            int prevK = (k == -d || (k != d && prev[k - 1 + d] < prev[k + 1 + d])) ? k + 1 : k - 1;
            int prevX = prev[prevK + d];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                result[x - 1] = y - 1;
                x--;
                y--;
            }
            x = prevX;
            y = prevY;
        }
        while (x > 0 && y > 0) {
            result[x - 1] = y - 1;
            x--;
            y--;
        }
        return result;
    }

    /** Matches elements occurring exactly once on each side, keeping the longest increasing run of them. */
    static int[] patience(int[] a, int[] b) {
        Map<Integer, int[]> counts = new HashMap<>();       // value -> {countA, countB, lastA, lastB}
        for (int i = 0; i < a.length; i++) {
            int[] c = counts.computeIfAbsent(a[i], k -> new int[4]);
            c[0]++;
            c[2] = i;
        }
        for (int j = 0; j < b.length; j++) {
            int[] c = counts.get(b[j]);
            if (c != null) {
                c[1]++;
                c[3] = j;
            }
        }
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            int[] c = counts.get(a[i]);
            if (c[0] == 1 && c[1] == 1) {
                pairs.add(new int[]{i, c[3]});
            }
        }
        int[] result = new int[a.length];
        Arrays.fill(result, -1);
        for (int[] p : longestIncreasing(pairs)) {
            result[p[0]] = p[1];
        }
        return result;
    }

    /** Longest subsequence of pairs (sorted by first) whose second values increase; O(k log k). */
    private static List<int[]> longestIncreasing(List<int[]> pairs) {
        int k = pairs.size();
        int[] tails = new int[k];
        int[] prev = new int[k];
        int len = 0;
        for (int i = 0; i < k; i++) {
            int lo = 0;
            int hi = len;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (pairs.get(tails[mid])[1] < pairs.get(i)[1]) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            prev[i] = lo > 0 ? tails[lo - 1] : -1;
            tails[lo] = i;
            if (lo == len) {
                len++;
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int i = len == 0 ? -1 : tails[len - 1]; i >= 0; i = prev[i]) {
            out.add(pairs.get(i));
        }
        Collections.reverse(out);
        return out;
    }
}
