package com.github.amber.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One item of a FROM clause read from tokens: a table (possibly qualified), a derived table or
 * something else (table function, parenthesized join), with its alias and the join that introduces it.
 *
 * @param kind       what the item is
 * @param name       the table name as written ({@code crm.customer}), null unless {@link Kind#TABLE}
 * @param nameStart  token index of the first name part (or of the open parenthesis)
 * @param nameEnd    token index of the last name part (or of the closing parenthesis)
 * @param alias      the alias as written, or null
 * @param aliasIndex token index of the alias, or -1
 * @param join       join words before the item ({@code LEFT OUTER JOIN}, {@code ,}), empty for the first
 * @param joinStart  token index of the first join word, or -1
 * @param joinEnd    token index of the last join word (JOIN, APPLY or the comma), or -1
 * @param onStart    token index of ON / USING, or -1
 * @param end        exclusive token index where the item (with its join condition) ends
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
record Source(Kind kind, String name, int nameStart, int nameEnd, String alias, int aliasIndex, String join,
              int joinStart, int joinEnd, int onStart, int end) {

    enum Kind {
        TABLE, DERIVED, OTHER
    }

    private static final Set<String> JOIN_WORDS = Set.of("LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS",
            "NATURAL", "JOIN", "APPLY", "STRAIGHT_JOIN");
    static final Set<String> NOT_ALIAS = Set.of("ON", "USING", "WHERE", "LEFT", "RIGHT", "INNER", "JOIN", "FULL",
            "CROSS", "OUTER", "NATURAL", "GROUP", "ORDER", "HAVING", "UNION", "EXCEPT", "INTERSECT", "MINUS", "LIMIT",
            "FETCH", "OFFSET", "CONNECT", "START", "FOR", "WINDOW", "QUALIFY", "PIVOT", "UNPIVOT", "WITH", "APPLY",
            "SET", "INTO", "VALUES", "RETURNING", "LATERAL", "SAMPLE", "PARTITION", "TABLESAMPLE", "STRAIGHT_JOIN",
            "USE", "FORCE", "IGNORE", "WHEN", "THEN", "MODEL", "LOCK", "AS", "SELECT", "FROM", "OUTPUT", "OPTION",
            "AND", "OR", "NOT");

    /** The name other parts of the query use for this item: the alias, else the table's last name part. */
    String exposed(Sql sql) {
        if (alias != null) {
            return Sql.key(alias);
        }
        return kind == Kind.TABLE ? Sql.key(sql.tok(nameEnd).text()) : null;
    }

    boolean isOuterJoinRight() {
        return join.startsWith("LEFT");
    }

    /** Parses the FROM items in tokens [from, to) at parenthesis depth {@code depth}. */
    static List<Source> parse(Sql sql, int from, int to, int depth) {
        List<Source> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            int joinStart = -1;
            int joinEnd = -1;
            StringBuilder join = new StringBuilder();
            while (i < to && (sql.wordIn(i, JOIN_WORDS) || sql.sym(i, ','))) {
                if (joinStart < 0) {
                    joinStart = i;
                }
                joinEnd = i;
                join.append(join.isEmpty() ? "" : " ").append(sql.tok(i).isSymbol(',') ? "," : sql.tok(i).upper());
                i++;
            }
            if (i >= to) {
                break;
            }
            if (sql.wordIn(i, Set.of("LATERAL", "ONLY"))) {
                i++;
            }
            Kind kind;
            String name = null;
            int nameStart = i;
            int nameEnd;
            if (sql.sym(i, '(')) {
                nameEnd = sql.close(i);
                if (nameEnd < 0 || nameEnd >= to) {
                    return List.of();
                }
                kind = sql.word(i + 1, "SELECT") || sql.word(i + 1, "WITH") ? Kind.DERIVED : Kind.OTHER;
            } else if (sql.isName(i)) {
                nameEnd = sql.nameEnd(i);
                if (sql.sym(nameEnd + 1, '(')) {
                    kind = Kind.OTHER;              // table function
                    nameEnd = sql.close(nameEnd + 1);
                    if (nameEnd < 0) {
                        return List.of();
                    }
                } else {
                    kind = Kind.TABLE;
                    name = sql.slice(nameStart, nameEnd);
                }
            } else {
                return List.of();                   // not a shape we understand
            }
            int j = nameEnd + 1;
            if (kind == Kind.TABLE && sql.sym(j, '@') && sql.isName(j + 1)) {
                j = sql.nameEnd(j + 1) + 1;         // Oracle database link
                kind = Kind.OTHER;
            }
            j = skipHints(sql, j, to);
            String alias = null;
            int aliasIndex = -1;
            if (sql.word(j, "AS") && sql.isName(j + 1)) {
                j++;
            }
            if (j < to && sql.isName(j) && !sql.wordIn(j, NOT_ALIAS)) {
                alias = sql.tok(j).text();
                aliasIndex = j;
                j++;
                if (sql.sym(j, '(')) {
                    kind = kind == Kind.TABLE ? Kind.OTHER : kind;   // column alias list renames columns
                    j = sql.close(j) + 1;
                }
            }
            j = skipHints(sql, j, to);
            int onStart = -1;
            while (j < to && !sql.sym(j, ',') && !(sql.wordIn(j, JOIN_WORDS) && sql.depth[j] == depth)) {
                if (onStart < 0 && (sql.word(j, "ON") || sql.word(j, "USING"))) {
                    onStart = j;
                } else if (onStart < 0) {
                    kind = Kind.OTHER;              // something we do not understand follows the item
                }
                j = sql.sym(j, '(') && sql.close(j) > j ? sql.close(j) + 1 : j + 1;
            }
            out.add(new Source(kind, name, nameStart, nameEnd, alias, aliasIndex, join.toString(), joinStart, joinEnd,
                    onStart, j));
            i = j;
        }
        return out;
    }

    /**
     * The single target table of {@code UPDATE t [alias] SET …} or {@code DELETE [FROM] t [alias] WHERE …};
     * empty when the statement names several tables (UPDATE … FROM, DELETE … USING, joins).
     */
    static List<Source> dmlTarget(Sql sql) {
        int start;
        String stop;
        if (sql.word(0, "UPDATE")) {
            start = 1;
            stop = "SET";
        } else if (sql.word(0, "DELETE")) {
            start = sql.word(1, "FROM") ? 2 : 1;
            stop = "WHERE";
        } else {
            return List.of();
        }
        int end = -1;
        for (int i = start; i < sql.size(); i++) {
            if (sql.depth[i] != 0) {
                continue;
            }
            if (sql.word(i, "FROM") || sql.word(i, "USING")) {
                return List.of();
            }
            if (end < 0 && sql.word(i, stop)) {
                end = i;
            }
        }
        List<Source> s = Source.parse(sql, start, end < 0 ? sql.size() : end, 0);
        return s.size() == 1 && s.getFirst().kind() == Kind.TABLE ? s : List.of();
    }

    /** Skips table hints: {@code WITH (NOLOCK)}, {@code (NOLOCK)}, MySQL index hints. */
    private static int skipHints(Sql sql, int j, int to) {
        if (sql.word(j, "WITH") && sql.sym(j + 1, '(') && sql.close(j + 1) > 0 && sql.close(j + 1) < to) {
            return sql.close(j + 1) + 1;
        }
        if (sql.wordIn(j, Set.of("USE", "FORCE", "IGNORE")) && sql.wordIn(j + 1, Set.of("INDEX", "KEY"))
                && sql.sym(j + 2, '(')) {
            return sql.close(j + 2) + 1;
        }
        return j;
    }
}
