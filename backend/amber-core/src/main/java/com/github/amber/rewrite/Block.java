package com.github.amber.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One {@code SELECT} query block of a statement, read from tokens: where its clauses start and end at
 * the block's own parenthesis level. Token indexes refer to the owning {@link Sql}; clause indexes are
 * -1 when the clause is absent, and every clause ends where the next one starts.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Block {

    private static final Set<String> FOR_CLAUSE = Set.of("UPDATE", "XML", "JSON", "BROWSE", "READ", "SHARE", "NO", "KEY");

    final Sql sql;
    /** Index of SELECT. */
    final int start;
    /** Exclusive end: a closing parenthesis, a set operator, a ';' or the end of the statement. */
    final int end;
    final int depth;
    /** Index of DISTINCT / UNIQUE right after SELECT, or -1. */
    int distinct = -1;
    int top = -1;
    int listStart;
    int listEnd;
    int into = -1;
    int from = -1;
    int where = -1;
    int groupBy = -1;
    int having = -1;
    int orderBy = -1;
    int connectBy = -1;
    int startWith = -1;
    /** LIMIT, OFFSET, FETCH, TOP or ROWNUM at the block's level. */
    boolean limited;
    /** FOR XML / FOR JSON / FOR UPDATE … at the block's level. */
    int forClause = -1;
    /** Other clauses that stop a clause: WINDOW, QUALIFY, MODEL, RETURNING… */
    boolean exotic;
    private final List<Integer> clauseStarts = new ArrayList<>();

    Block(Sql sql, int start) {
        this.sql = sql;
        this.start = start;
        this.depth = sql.depth[start];
        int e = start + 1;
        while (e < sql.size() && sql.depth[e] >= depth) {
            if (sql.depth[e] == depth && (sql.wordIn(e, Sql.SET_OPERATORS) || sql.sym(e, ';'))) {
                break;
            }
            e++;
        }
        this.end = e;
        int i = start + 1;
        if (sql.wordIn(i, Set.of("DISTINCT", "UNIQUE")) && !sql.word(i + 1, "ON")) {
            distinct = i++;
        } else if (sql.word(i, "DISTINCT")) {
            distinct = i;                          // DISTINCT ON (…): PostgreSQL, handled as exotic
            exotic = true;
            i++;
        } else if (sql.word(i, "ALL")) {
            i++;
        }
        if (sql.word(i, "TOP")) {
            top = i;
            limited = true;
            i++;
            if (sql.sym(i, '(')) {
                i = sql.close(i) + 1;
            } else {
                i++;
            }
            if (sql.word(i, "PERCENT")) {
                i++;
            }
            if (sql.word(i, "WITH") && sql.word(i + 1, "TIES")) {
                i += 2;
            }
        }
        listStart = i;
        listEnd = end;
        for (int k = i; k < end; k++) {
            if (sql.depth[k] != depth) {
                continue;
            }
            if (sql.word(k, "INTO") && into < 0 && from < 0) {
                into = mark(k);
            } else if (sql.word(k, "FROM") && from < 0) {
                from = mark(k);
            } else if (sql.word(k, "WHERE") && where < 0) {
                where = mark(k);
            } else if (sql.word(k, "GROUP") && sql.word(k + 1, "BY")) {
                groupBy = mark(k);
            } else if (sql.word(k, "HAVING")) {
                having = mark(k);
            } else if (sql.word(k, "ORDER") && (sql.word(k + 1, "BY") || sql.word(k + 1, "SIBLINGS"))) {
                orderBy = mark(k);
            } else if (sql.word(k, "CONNECT") && sql.word(k + 1, "BY")) {
                connectBy = mark(k);
            } else if (sql.word(k, "START") && sql.word(k + 1, "WITH")) {
                startWith = mark(k);
            } else if (sql.wordIn(k, Set.of("LIMIT", "OFFSET", "FETCH"))) {
                limited = true;
                mark(k);
            } else if (sql.word(k, "FOR") && sql.wordIn(k + 1, FOR_CLAUSE)) {
                forClause = mark(k);
            } else if (sql.wordIn(k, Set.of("QUALIFY", "MODEL", "RETURNING", "LOCK"))
                    || (sql.word(k, "WINDOW") && sql.isName(k + 1) && sql.word(k + 2, "AS"))) {
                exotic = true;
                mark(k);
            } else if (sql.word(k, "ROWNUM")) {
                limited = true;
            }
        }
        if (!clauseStarts.isEmpty()) {
            listEnd = clauseStarts.getFirst();
        }
    }

    private int mark(int k) {
        clauseStarts.add(k);
        return k;
    }

    /** Exclusive end of the clause that starts at {@code clause}. */
    int clauseEnd(int clause) {
        for (int c : clauseStarts) {
            if (c > clause) {
                return c;
            }
        }
        return end;
    }

    /** End of the FROM clause (exclusive), or -1 without FROM. */
    int fromEnd() {
        return from < 0 ? -1 : clauseEnd(from);
    }

    int whereEnd() {
        return where < 0 ? -1 : clauseEnd(where);
    }

    /** The FROM items of this block. */
    List<Source> sources() {
        return from < 0 ? List.of() : Source.parse(sql, from + 1, fromEnd(), depth);
    }

    /** The open parenthesis right before SELECT, or -1 when the block is not parenthesized. */
    int openParen() {
        return sql.sym(start - 1, '(') ? start - 1 : -1;
    }

    /** Select items as token ranges {@code [from, to)} split on top-level commas. */
    List<int[]> items() {
        List<int[]> out = new ArrayList<>();
        int s = listStart;
        for (int k = listStart; k < listEnd; k++) {
            if (sql.depth[k] == depth && sql.sym(k, ',')) {
                out.add(new int[]{s, k});
                s = k + 1;
            }
        }
        if (s < listEnd) {
            out.add(new int[]{s, listEnd});
        }
        return out;
    }

    /** Tokens of one select item without a trailing alias ({@code expr [AS] alias}). */
    int[] itemExpression(int[] item) {
        int a = item[0];
        int b = item[1];
        if (b - a >= 3 && sql.word(b - 2, "AS") && sql.isName(b - 1)) {
            return new int[]{a, b - 2};
        }
        if (b - a >= 2 && sql.isName(b - 1) && !sql.sym(b - 2, '.') && endsExpression(b - 2)) {
            return new int[]{a, b - 1};
        }
        return item;
    }

    /** The alias of a select item, or null. */
    String itemAlias(int[] item) {
        int[] e = itemExpression(item);
        return e[1] < item[1] ? Sql.key(sql.tok(item[1] - 1).text()) : null;
    }

    private boolean endsExpression(int i) {
        var tok = sql.tok(i);
        return tok.isSymbol(')') || tok.type() == com.github.amber.dialect.split.ScanToken.Type.STRING
                || tok.type() == com.github.amber.dialect.split.ScanToken.Type.NUMBER
                || (sql.isName(i) && !tok.isWordIn(Set.of("SELECT", "DISTINCT", "ALL", "AND", "OR", "NOT", "CASE",
                "WHEN", "THEN", "ELSE", "IS", "IN", "LIKE", "BETWEEN")));
    }
}
