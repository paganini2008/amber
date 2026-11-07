package com.github.amber.diff;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normalized tokens of a stretch of SQL: comments and whitespace dropped, words (keywords and unquoted
 * names) upper-cased, quoted identifiers, strings and numbers kept as written. Two statements with the
 * same normalized tokens differ only in formatting.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SqlTokens {

    /** One token: normalized text and its source offsets (end exclusive). */
    record Tok(String norm, int start, int end) {
    }

    private SqlTokens() {
    }

    /** Tokens starting in {@code [from, to)} of {@code text}. */
    static List<Tok> of(Dialect dialect, String text, int from, int to) {
        SqlScanner sc = new SqlScanner(text, ScannerOptions.forDialect(dialect));
        sc.reset(from);
        List<Tok> out = new ArrayList<>();
        for (ScanToken t = sc.next(); t != null && t.start() < to; t = sc.next()) {
            String norm = t.type() == ScanToken.Type.WORD ? t.text().toUpperCase(Locale.ROOT) : t.text();
            out.add(new Tok(norm, t.start(), Math.min(t.end(), to)));
        }
        return out;
    }

    /** 64-bit FNV-1a hash of the normalized token sequence. */
    static long hash(List<Tok> tokens) {
        long h = 0xcbf29ce484222325L;
        for (Tok t : tokens) {
            String s = t.norm();
            for (int i = 0; i < s.length(); i++) {
                h ^= s.charAt(i);
                h *= 0x100000001b3L;
            }
            h ^= 0x1F;                            // token separator
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Multiset Jaccard similarity of two token lists, 0..1 (1 when both are empty). */
    static double similarity(List<Tok> a, List<Tok> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        Map<String, int[]> counts = new HashMap<>();
        for (Tok t : a) {
            counts.computeIfAbsent(t.norm(), k -> new int[2])[0]++;
        }
        for (Tok t : b) {
            counts.computeIfAbsent(t.norm(), k -> new int[2])[1]++;
        }
        long inter = 0;
        long union = 0;
        for (int[] c : counts.values()) {
            inter += Math.min(c[0], c[1]);
            union += Math.max(c[0], c[1]);
        }
        return union == 0 ? 1 : (double) inter / union;
    }

    /** Number of occurrences of a word among the tokens. */
    static int countWord(List<Tok> tokens, String upperWord) {
        int n = 0;
        for (Tok t : tokens) {
            if (t.norm().equals(upperWord)) {
                n++;
            }
        }
        return n;
    }
}
