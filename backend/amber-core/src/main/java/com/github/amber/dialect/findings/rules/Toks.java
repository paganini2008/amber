package com.github.amber.dialect.findings.rules;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The significant tokens of a text range (comments and whitespace skipped) with their parenthesis depth
 * and matching parentheses. Offsets are those of the whole script. Every accessor is bounds-safe, so the
 * rules can probe neighbours without checks.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class Toks {

    /** Words that never name a column. */
    static final Set<String> KEYWORDS = Set.of("SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL",
            "LIKE", "BETWEEN", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "AS", "ON", "JOIN", "LEFT", "RIGHT",
            "INNER", "OUTER", "FULL", "CROSS", "GROUP", "ORDER", "BY", "HAVING", "UNION", "ALL", "ANY", "SOME",
            "DISTINCT", "TRUE", "FALSE", "ESCAPE", "SET", "VALUES", "INTO", "UPDATE", "DELETE", "INSERT", "MERGE",
            "USING", "WITH", "LIMIT", "OFFSET", "FETCH", "TOP", "ROWNUM", "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE",
            "CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "CURRENT_TIME", "DATE", "TIMESTAMP", "INTERVAL", "PRIOR", "LEVEL",
            "ROWID", "DEFAULT", "COLLATE", "UNKNOWN", "NULLS", "FIRST", "LAST", "ASC", "DESC", "OVER", "PARTITION");

    final Dialect dialect;
    final String text;
    final List<ScanToken> t;
    final int[] depth;
    final int[] match;

    Toks(Dialect dialect, String text, int start, int end) {
        this.dialect = dialect;
        this.text = text;
        SqlScanner sc = new SqlScanner(text, ScannerOptions.forDialect(dialect));
        sc.reset(start);
        List<ScanToken> list = new ArrayList<>();
        ScanToken tok;
        while ((tok = sc.next()) != null && tok.start() < end) {
            list.add(tok);
        }
        this.t = List.copyOf(list);
        this.depth = new int[t.size()];
        this.match = new int[t.size()];
        Arrays.fill(match, -1);
        Deque<Integer> open = new ArrayDeque<>();
        int level = 0;
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).isSymbol(')')) {
                level--;
                if (!open.isEmpty()) {
                    int o = open.pop();
                    match[o] = i;
                    match[i] = o;
                }
            }
            depth[i] = level;
            if (t.get(i).isSymbol('(')) {
                level++;
                open.push(i);
            }
        }
    }

    int size() {
        return t.size();
    }

    ScanToken tok(int i) {
        return t.get(i);
    }

    boolean in(int i) {
        return i >= 0 && i < t.size();
    }

    boolean word(int i, String w) {
        return in(i) && t.get(i).isWord(w);
    }

    boolean wordIn(int i, Set<String> words) {
        return in(i) && t.get(i).isWordIn(words);
    }

    boolean sym(int i, char c) {
        return in(i) && t.get(i).isSymbol(c);
    }

    boolean type(int i, ScanToken.Type type) {
        return in(i) && t.get(i).type() == type;
    }

    boolean isName(int i) {
        return in(i) && (t.get(i).type() == ScanToken.Type.WORD || t.get(i).type() == ScanToken.Type.QUOTED_IDENTIFIER);
    }

    /** A name that can be a column or alias: not a keyword, not a variable sigil, not a function call. */
    boolean isPlainName(int i) {
        return isName(i) && !wordIn(i, KEYWORDS) && !sym(i + 1, '(') && !t.get(i).text().startsWith("@");
    }

    int close(int i) {
        return in(i) ? match[i] : -1;
    }

    /** Index of the last part of the qualified name starting at {@code i}, or -1. */
    int nameEnd(int i) {
        if (!isName(i)) {
            return -1;
        }
        int j = i;
        while (sym(j + 1, '.') && isName(j + 2)) {
            j += 2;
        }
        return j;
    }

    String slice(int from, int to) {
        return text.substring(t.get(from).start(), t.get(to).end());
    }

    /** Normalized text of tokens [from, to): names upper-case without quotes, single spaces. */
    String norm(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, from); i < Math.min(to, t.size()); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(key(t.get(i)));
        }
        return sb.toString();
    }

    static String key(ScanToken tok) {
        return switch (tok.type()) {
            case WORD -> tok.upper();
            case QUOTED_IDENTIFIER -> unquote(tok.text()).toUpperCase(Locale.ROOT);
            default -> tok.text();
        };
    }

    static String key(String name) {
        return unquote(name).toUpperCase(Locale.ROOT);
    }

    static String unquote(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '[' && b == ']') || (a == '`' && b == '`')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** True when tokens [from, to) contain {@code SELECT}. */
    boolean hasSelect(int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(to, t.size()); i++) {
            if (t.get(i).isWord("SELECT")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A comparison operator starting at {@code i} ({@code =}, {@code <>}, {@code !=}, {@code <}, {@code <=},
     * {@code >}, {@code >=}, {@code ^=}): the operator text, or null. Its token length is
     * {@link #opLength(String)}.
     */
    String comparison(int i) {
        if (!type(i, ScanToken.Type.SYMBOL)) {
            return null;
        }
        char c = t.get(i).text().charAt(0);
        boolean adjacent = in(i + 1) && t.get(i + 1).start() == t.get(i).end() && type(i + 1, ScanToken.Type.SYMBOL);
        char n = adjacent ? t.get(i + 1).text().charAt(0) : ' ';
        switch (c) {
            case '=':
                return "=";
            case '<':
                return n == '>' ? "<>" : n == '=' ? "<=" : "<";
            case '>':
                return n == '=' ? ">=" : ">";
            case '!':
            case '^':
                return n == '=' ? c + "=" : null;
            default:
                return null;
        }
    }

    static int opLength(String op) {
        return op.length();
    }
}
