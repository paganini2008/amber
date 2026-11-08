package com.github.amber.dialect.lineage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Quote- and parenthesis-aware helpers for argument and expression lists in SQL text: matching
 * parentheses, splitting at top-level commas, and finding a keyword outside parentheses, strings,
 * quoted names and comments.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class ArgumentText {

    private ArgumentText() {
    }

    /** Index just after the literal, quoted name or comment starting at {@code i}, or {@code i} when there is none. */
    private static int skipQuoted(String text, int i, int to) {
        char c = text.charAt(i);
        if (c == '\'' || c == '"' || c == '`' || c == '[') {
            char close = c == '[' ? ']' : c;
            int j = i + 1;
            while (j < to) {
                if (text.charAt(j) == close) {
                    if (close != ']' && j + 1 < to && text.charAt(j + 1) == close) {
                        j += 2;                       // doubled quote inside the literal
                        continue;
                    }
                    return j + 1;
                }
                j++;
            }
            return to;
        }
        if (c == '-' && i + 1 < to && text.charAt(i + 1) == '-') {
            int nl = text.indexOf('\n', i);
            return nl < 0 || nl >= to ? to : nl + 1;
        }
        if (c == '/' && i + 1 < to && text.charAt(i + 1) == '*') {
            int end = text.indexOf("*/", i + 2);
            return end < 0 || end + 2 > to ? to : end + 2;
        }
        return i;
    }

    /** Index of the parenthesis closing the one at {@code open}, or -1. */
    static int closing(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            int skipped = skipQuoted(text, i, text.length());
            if (skipped != i) {
                i = skipped;
                continue;
            }
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** {@code [start, end)} spans of the comma-separated items of {@code text[from, to)} at depth 0. */
    static List<int[]> split(String text, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int itemStart = from;
        int i = from;
        while (i < to) {
            int skipped = skipQuoted(text, i, to);
            if (skipped != i) {
                i = skipped;
                continue;
            }
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(new int[] {itemStart, i});
                itemStart = i + 1;
            }
            i++;
        }
        out.add(new int[] {itemStart, to});
        return out;
    }

    /** Index of keyword {@code word} in {@code text[from, to)} outside parentheses and literals, or -1. */
    static int wordAtDepth0(String text, int from, int to, String word) {
        int depth = 0;
        int i = from;
        String upper = word.toUpperCase(Locale.ROOT);
        while (i < to) {
            int skipped = skipQuoted(text, i, to);
            if (skipped != i) {
                i = skipped;
                continue;
            }
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && i + upper.length() <= to && text.regionMatches(true, i, upper, 0, upper.length())
                    && (i == 0 || !isWordChar(text.charAt(i - 1)))
                    && (i + upper.length() == text.length() || !isWordChar(text.charAt(i + upper.length())))) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }

    /** {@code [from, to)} without leading and trailing whitespace. */
    static int[] trim(String text, int from, int to) {
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        return new int[] {from, to};
    }
}
