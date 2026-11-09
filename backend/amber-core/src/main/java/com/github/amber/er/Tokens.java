package com.github.amber.er;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A small SQL lexer for join conditions: identifiers (bare and quoted), literals, operators and
 * punctuation, comments skipped. It reads only the parts of the text that are not masked (nested
 * queries are read as blocks of their own) and puts one {@link Kind#MASK} token where a masked part was.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class Tokens {

    enum Kind {
        IDENT, QUOTED, NUMBER, STRING, OP, MASK
    }

    /** A token with absolute offsets into the script text; {@code text} of a quoted name is unquoted. */
    record Tok(Kind kind, String text, int start, int end) {

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        boolean is(String s) {
            return (kind == Kind.IDENT || kind == Kind.OP) && text.equalsIgnoreCase(s);
        }

        boolean isName() {
            return kind == Kind.IDENT || kind == Kind.QUOTED;
        }
    }

    private Tokens() {
    }

    /**
     * Tokens of {@code text[from, to)} outside {@code masks} (sorted, non-overlapping {@code [start, end)}
     * pairs; overlapping or out-of-range pairs are tolerated).
     */
    static List<Tok> of(String text, int from, int to, List<int[]> masks) {
        List<Tok> out = new ArrayList<>();
        int at = Math.max(0, from);
        int limit = Math.min(text.length(), to);
        for (int[] m : masks) {
            int ms = Math.max(m[0], at);
            int me = Math.min(m[1], limit);
            if (me <= at) {
                continue;
            }
            if (ms > at) {
                lex(text, at, ms, out);
            }
            out.add(new Tok(Kind.MASK, "", Math.max(ms, at), me));
            at = me;
        }
        if (at < limit) {
            lex(text, at, limit, out);
        }
        return out;
    }

    private static void lex(String s, int from, int to, List<Tok> out) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < to && s.charAt(i + 1) == '-') {
                while (i < to && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && i + 1 < to && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 || end + 2 > to ? to : end + 2;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < to) {
                    if (s.charAt(j) == '\'') {
                        if (j + 1 < to && s.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                int end = Math.min(to, j + 1);
                out.add(new Tok(Kind.STRING, s.substring(i, end), i, end));
                i = end;
            } else if (c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                StringBuilder name = new StringBuilder();
                int j = i + 1;
                while (j < to) {
                    char d = s.charAt(j);
                    if (d == close) {
                        if (close != ']' && j + 1 < to && s.charAt(j + 1) == close) {
                            name.append(close);
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    name.append(d);
                    j++;
                }
                int end = Math.min(to, j + 1);
                out.add(new Tok(Kind.QUOTED, name.toString(), i, end));
                i = end;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < to && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Tok(Kind.NUMBER, s.substring(i, j), i, j));
                i = j;
            } else if (isNameStart(c)) {
                int j = i + 1;
                while (j < to && isNamePart(s.charAt(j))) {
                    j++;
                }
                out.add(new Tok(Kind.IDENT, s.substring(i, j), i, j));
                i = j;
            } else {
                int len = i + 1 < to && TWO_CHAR.contains(s.substring(i, i + 2)) ? 2 : 1;
                out.add(new Tok(Kind.OP, s.substring(i, i + len), i, i + len));
                i += len;
            }
        }
    }

    private static final Set<String> TWO_CHAR = Set.of("<>", "!=", "<=", ">=", "||", "::", "=>",
            "*=", "=*", "==", ":=", "->");

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '@' || c == '#' || c == '$';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '@' || c == '#' || c == '$';
    }
}
