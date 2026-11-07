package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The tokens of one statement (comments and whitespace skipped) with their parenthesis depth and
 * matching parentheses, plus the SELECT blocks found in them. Offsets stay those of the whole script,
 * so edits computed here apply to the original text.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Sql {

    static final Set<String> SET_OPERATORS = Set.of("UNION", "EXCEPT", "INTERSECT", "MINUS");

    final Dialect dialect;
    final String text;
    final List<ScanToken> t;
    final int[] depth;
    final int[] match;
    private List<Block> blocks;

    Sql(Dialect dialect, String text, int start, int end) {
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
        java.util.Arrays.fill(match, -1);
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
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

    boolean word(int i, String w) {
        return i >= 0 && i < t.size() && t.get(i).isWord(w);
    }

    boolean wordIn(int i, Set<String> words) {
        return i >= 0 && i < t.size() && t.get(i).isWordIn(words);
    }

    boolean sym(int i, char c) {
        return i >= 0 && i < t.size() && t.get(i).isSymbol(c);
    }

    boolean isName(int i) {
        return i >= 0 && i < t.size()
                && (t.get(i).type() == ScanToken.Type.WORD || t.get(i).type() == ScanToken.Type.QUOTED_IDENTIFIER);
    }

    /** Index of the parenthesis matching the one at {@code i}, or -1. */
    int close(int i) {
        return i >= 0 && i < match.length ? match[i] : -1;
    }

    /** Source text from the start of token {@code from} to the end of token {@code to} (inclusive). */
    String slice(int from, int to) {
        return text.substring(t.get(from).start(), t.get(to).end());
    }

    /** Normalized text of tokens [from, to): names and keywords upper-case, one space between tokens. */
    String norm(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            ScanToken tok = t.get(i);
            String s = switch (tok.type()) {
                case WORD -> tok.upper();
                case QUOTED_IDENTIFIER -> unquote(tok.text()).toUpperCase(Locale.ROOT);
                default -> tok.text();
            };
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** Exact token text [from, to) joined by single spaces (literals kept as written). */
    String exact(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            ScanToken tok = t.get(i);
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(tok.type() == ScanToken.Type.WORD ? tok.upper() : tok.text());
        }
        return sb.toString();
    }

    /**
     * End of the qualified name starting at {@code i} ({@code a}, {@code a.b}, {@code a.b.c}): index of its
     * last part, or -1 when {@code i} does not start a name.
     */
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

    /** The SELECT blocks of the statement, outermost first in source order. */
    List<Block> blocks() {
        if (blocks == null) {
            List<Block> list = new ArrayList<>();
            for (int i = 0; i < t.size(); i++) {
                if (t.get(i).isWord("SELECT")) {
                    list.add(new Block(this, i));
                }
            }
            blocks = List.copyOf(list);
        }
        return blocks;
    }

    /** The innermost SELECT block whose own level holds token {@code i}, or null. */
    Block blockAt(int i) {
        Block best = null;
        for (Block b : blocks()) {
            if (b.start <= i && i < b.end && depth[i] == b.depth && (best == null || b.start > best.start)) {
                best = b;
            }
        }
        return best;
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

    /** Case-insensitive identity of a name token: quotes removed, upper-case. */
    static String key(String name) {
        return unquote(name).toUpperCase(Locale.ROOT);
    }

    /** Start offset of the whitespace run that ends at {@code offset} (comments are not crossed). */
    int spaceBefore(int offset) {
        int i = offset;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /** End offset of the whitespace run that starts at {@code offset} (comments are not crossed). */
    int spaceAfter(int offset) {
        int i = offset;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
