package com.github.amber.diff;

import com.github.amber.core.Dialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sequence matcher and token helpers behind the diff.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class DiffSequencesTest {

    private static int matched(int[] m) {
        int n = 0;
        for (int x : m) {
            if (x >= 0) {
                n++;
            }
        }
        return n;
    }

    @Test
    void myersFindsLongestCommonSubsequence() {
        int[] a = {1, 2, 3, 4, 5, 6};
        int[] b = {9, 2, 4, 3, 5, 7};
        int[] m = Sequences.match(a, b);
        assertEquals(3, matched(m));                     // 2, (3 or 4), 5
        assertEquals(1, m[1]);
        assertEquals(4, m[4]);
        assertArrayEquals(new int[]{0, 1, 2}, Sequences.match(new int[]{1, 2, 3}, new int[]{1, 2, 3}));
        assertArrayEquals(new int[]{-1, -1}, Sequences.match(new int[]{1, 2}, new int[]{}));
        assertArrayEquals(new int[]{}, Sequences.match(new int[]{}, new int[]{1}));
    }

    @Test
    void cappedMyersFallsBackToPatience() {
        int[] a = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] b = {8, 1, 2, 30, 4, 50, 6, 7};
        assertNull(Sequences.myers(new int[]{1, 2, 3}, new int[]{4, 5, 6}, 2));
        int[] m = Sequences.match(a, b, 1);
        assertEquals(1, m[0]);
        assertEquals(2, m[1]);
        assertEquals(-1, m[2]);
        assertTrue(matched(m) >= 5);
        int[] p = Sequences.patience(new int[]{5, 5, 1, 2}, new int[]{2, 1, 5});
        assertEquals(1, matched(p));                     // 5 is not unique on the left; 1 and 2 cross
    }

    @Test
    void tokensIgnoreCaseWhitespaceAndComments() {
        var a = SqlTokens.of(Dialect.ORACLE, "select  A -- x\n from T", 0, 23);
        var b = SqlTokens.of(Dialect.ORACLE, "SELECT a /* y */ FROM t", 0, 23);
        assertEquals(SqlTokens.hash(a), SqlTokens.hash(b));
        assertEquals(1.0, SqlTokens.similarity(a, b));
        assertEquals(1.0, SqlTokens.similarity(List.of(), List.of()));
        assertEquals(0.0, SqlTokens.similarity(a, List.of()));
        assertEquals(1, SqlTokens.countWord(a, "FROM"));
        var q = SqlTokens.of(Dialect.ORACLE, "select 'Abc', \"Col\" from t", 0, 26);
        var r = SqlTokens.of(Dialect.ORACLE, "select 'abc', \"Col\" from t", 0, 26);
        assertTrue(SqlTokens.hash(q) != SqlTokens.hash(r));   // string literals keep their case
    }
}
