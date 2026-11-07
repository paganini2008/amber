package com.github.amber.format;

import com.github.amber.core.Dialect;

import java.util.List;

/**
 * Proves that formatting a unit changed nothing but whitespace (and the case of reserved words): the
 * formatted text is lexed again and its tokens, comments included, must match the original ones one
 * for one. Identifiers, quoted identifiers, strings, numbers, operators and comments must be
 * byte-identical; a word may differ only in case, and only if it is one the formatter is allowed to
 * re-case.
 *
 * <p>For Oracle two SQL*Plus rules are checked as well, because SQL*Plus reads scripts line by line:
 * the output may not contain more lines holding only {@code .} or only {@code /}, nor more blank
 * lines, than the input (a blank line ends a SQL statement by default).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SafetyCheck {

    private SafetyCheck() {
    }

    /** Returns null when {@code formatted} is equivalent to {@code original}, otherwise the reason. */
    static String verify(Dialect dialect, String original, String formatted, String terminator) {
        List<FormatToken> before = FormatLexer.lex(original, dialect, terminator);
        List<FormatToken> after = FormatLexer.lex(formatted, dialect, terminator);
        return compare(dialect, original, formatted, before, after);
    }

    static String compare(Dialect dialect, String original, String formatted, List<FormatToken> before,
                          List<FormatToken> after) {
        int count = Math.min(before.size(), after.size());
        for (int k = 0; k < count; k++) {
            FormatToken a = before.get(k);
            FormatToken b = after.get(k);
            if (a.kind != b.kind) {
                return "token " + (k + 1) + " changed from " + a.kind + " '" + abbreviate(a.text) + "' to "
                        + b.kind + " '" + abbreviate(b.text) + "'";
            }
            if (!a.text.equals(b.text)) {
                boolean caseOnly = a.kind == FormatToken.Kind.WORD && Keywords.isCaseable(a.upper)
                        && a.upper.equals(b.upper);
                if (!caseOnly) {
                    return "token " + (k + 1) + " changed from '" + abbreviate(a.text) + "' to '"
                            + abbreviate(b.text) + "'";
                }
            }
        }
        if (before.size() != after.size()) {
            return "token count changed from " + before.size() + " to " + after.size();
        }
        if (dialect == Dialect.ORACLE) {
            if (countLines(formatted, ".") > countLines(original, ".")
                    || countLines(formatted, "/") > countLines(original, "/")) {
                return "a SQL*Plus terminator line would appear";
            }
            if (countLines(formatted, "") > countLines(original, "")) {
                return "a blank line would appear inside the statement";
            }
        }
        return null;
    }

    /** Number of lines whose trimmed content equals {@code content}. */
    private static int countLines(String text, String content) {
        int count = 0;
        int start = 0;
        int len = text.length();
        while (start <= len) {
            int end = start;
            while (end < len && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
                end++;
            }
            if (text.substring(start, end).strip().equals(content)) {
                count++;
            }
            if (end >= len) {
                break;
            }
            start = end + (text.startsWith("\r\n", end) ? 2 : 1);
        }
        return count;
    }

    private static String abbreviate(String s) {
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 40 ? one : one.substring(0, 37) + "...";
    }
}
