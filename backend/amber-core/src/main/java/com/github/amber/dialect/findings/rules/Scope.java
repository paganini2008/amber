package com.github.amber.dialect.findings.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One query block read from tokens: a {@code SELECT} (with its clauses at the block's own parenthesis
 * level) or the target of a single-table {@code UPDATE} / {@code DELETE}. It knows its FROM items, their
 * aliases and join conditions, so a column reference can be tied to one table. Anything the reader does
 * not understand makes the block {@link #opaque}; rules then stay silent.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class Scope {

    /**
     * One FROM item.
     *
     * @param name    table name as written, null unless a plain table
     * @param exposed upper-case name other clauses use (alias, else the last name part), or null
     * @param on      first token of the join condition (after ON), or -1
     * @param onEnd   exclusive end of the join condition
     * @param join    the join words before the item ({@code LEFT JOIN}, {@code ,}), empty for the first
     */
    record Src(String name, int nameStart, int nameEnd, String exposed, int on, int onEnd, String join,
               boolean derived, int end) {

        boolean table() {
            return name != null;
        }
    }

    /** A column reference {@code [q.]col} at tokens [start, end] tied to a FROM item. */
    record ColRef(Src src, String column, int start, int end) {
    }

    static final Set<String> SET_OPERATORS = Set.of("UNION", "EXCEPT", "INTERSECT", "MINUS");
    private static final Set<String> JOIN_WORDS = Set.of("LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS",
            "NATURAL", "JOIN", "APPLY", "STRAIGHT_JOIN");
    private static final Set<String> NOT_ALIAS = Set.of("ON", "USING", "WHERE", "LEFT", "RIGHT", "INNER", "JOIN",
            "FULL", "CROSS", "OUTER", "NATURAL", "GROUP", "ORDER", "HAVING", "UNION", "EXCEPT", "INTERSECT", "MINUS",
            "LIMIT", "FETCH", "OFFSET", "CONNECT", "START", "FOR", "WINDOW", "QUALIFY", "PIVOT", "UNPIVOT", "WITH",
            "APPLY", "SET", "INTO", "VALUES", "RETURNING", "LATERAL", "SAMPLE", "PARTITION", "TABLESAMPLE",
            "STRAIGHT_JOIN", "USE", "FORCE", "IGNORE", "WHEN", "THEN", "MODEL", "LOCK", "AS", "SELECT", "FROM",
            "OUTPUT", "OPTION", "AND", "OR", "NOT");
    private static final Set<String> FOR_CLAUSE = Set.of("UPDATE", "XML", "JSON", "BROWSE", "READ", "SHARE", "NO", "KEY");

    final Toks k;
    final int start;
    final int end;
    final int depth;
    final boolean select;
    int distinct = -1;
    int listStart = -1;
    int listEnd = -1;
    int from = -1;
    int fromEnd = -1;
    int where = -1;
    int whereEnd = -1;
    int groupBy = -1;
    int having = -1;
    int orderBy = -1;
    boolean limited;
    boolean opaque;
    /** Oracle {@code (+)} outer joins in WHERE. */
    boolean oldOuterJoin;
    List<Src> sources = List.of();

    private Scope(Toks k, int start, int end, int depth, boolean select) {
        this.k = k;
        this.start = start;
        this.end = end;
        this.depth = depth;
        this.select = select;
    }

    /** Every SELECT block of the statement, outermost first. */
    static List<Scope> blocks(Toks k) {
        List<Scope> out = new ArrayList<>();
        for (int i = 0; i < k.size(); i++) {
            if (k.word(i, "SELECT")) {
                out.add(select(k, i));
            }
        }
        return out;
    }

    static Scope select(Toks k, int start) {
        int d = k.depth[start];
        int e = start + 1;
        while (e < k.size() && k.depth[e] >= d) {
            if (k.depth[e] == d && (k.wordIn(e, SET_OPERATORS) || k.sym(e, ';'))) {
                break;
            }
            e++;
        }
        Scope s = new Scope(k, start, e, d, true);
        s.readSelect();
        return s;
    }

    private void readSelect() {
        int i = start + 1;
        if (k.wordIn(i, Set.of("DISTINCT", "UNIQUE"))) {
            distinct = i++;
            if (k.word(i, "ON")) {
                opaque = true;
            }
        } else if (k.word(i, "ALL")) {
            i++;
        }
        if (k.word(i, "TOP")) {
            limited = true;
            i++;
            i = k.sym(i, '(') && k.close(i) > i ? k.close(i) + 1 : i + 1;
            while (k.wordIn(i, Set.of("PERCENT", "WITH", "TIES"))) {
                i++;
            }
        }
        listStart = i;
        List<Integer> clauses = new ArrayList<>();
        int into = -1;
        for (int j = i; j < end; j++) {
            if (k.depth[j] != depth) {
                continue;
            }
            if (k.word(j, "INTO") && from < 0 && into < 0) {
                into = j;
                clauses.add(j);
            } else if (k.word(j, "FROM") && from < 0) {
                from = j;
                clauses.add(j);
            } else if (k.word(j, "WHERE") && where < 0 && from >= 0) {
                where = j;
                clauses.add(j);
            } else if (k.word(j, "GROUP") && k.word(j + 1, "BY")) {
                groupBy = j;
                clauses.add(j);
            } else if (k.word(j, "HAVING")) {
                having = j;
                clauses.add(j);
            } else if (k.word(j, "ORDER") && (k.word(j + 1, "BY") || k.word(j + 1, "SIBLINGS"))) {
                orderBy = j;
                clauses.add(j);
            } else if ((k.word(j, "CONNECT") && k.word(j + 1, "BY")) || (k.word(j, "START") && k.word(j + 1, "WITH"))) {
                opaque = true;
                clauses.add(j);
            } else if (k.wordIn(j, Set.of("LIMIT", "OFFSET", "FETCH"))) {
                limited = true;
                clauses.add(j);
            } else if (k.word(j, "FOR") && k.wordIn(j + 1, FOR_CLAUSE)) {
                clauses.add(j);
            } else if (k.wordIn(j, Set.of("QUALIFY", "MODEL", "RETURNING", "LOCK", "WINDOW", "PIVOT", "UNPIVOT",
                    "MATCH_RECOGNIZE"))) {
                opaque = true;
                clauses.add(j);
            } else if (k.word(j, "ROWNUM")) {
                limited = true;
            }
        }
        listEnd = clauses.isEmpty() ? end : clauses.get(0);
        if (from >= 0) {
            fromEnd = next(clauses, from);
            sources = parseSources(from + 1, fromEnd);
        }
        if (where >= 0) {
            whereEnd = next(clauses, where);
            for (int j = where + 1; j + 2 < whereEnd; j++) {
                if (k.sym(j, '(') && k.sym(j + 1, '+') && k.sym(j + 2, ')')) {
                    oldOuterJoin = true;
                }
            }
        }
    }

    private int next(List<Integer> clauses, int clause) {
        for (int c : clauses) {
            if (c > clause) {
                return c;
            }
        }
        return end;
    }

    /**
     * The single target of {@code UPDATE t [a] SET … [WHERE …]} or {@code DELETE [FROM] t [a] [WHERE …]} at
     * depth 0, or null when the statement names several tables (UPDATE … FROM, joins, USING).
     */
    static Scope dml(Toks k) {
        int first;
        String stop;
        if (k.word(0, "UPDATE")) {
            first = 1;
            stop = "SET";
        } else if (k.word(0, "DELETE")) {
            first = k.word(1, "FROM") ? 2 : 1;
            stop = "WHERE";
        } else {
            return null;
        }
        int stopAt = -1;
        int where = -1;
        int whereEnd = k.size();
        for (int i = first; i < k.size(); i++) {
            if (k.depth[i] != 0) {
                continue;
            }
            if (k.wordIn(i, Set.of("FROM", "USING", "JOIN", "OUTPUT")) && i > first) {
                return null;
            }
            if (stopAt < 0 && k.word(i, stop)) {
                stopAt = i;
            }
            if (k.word(i, "WHERE") && where < 0) {
                where = i;
            } else if (where >= 0 && (k.wordIn(i, Set.of("RETURNING", "OPTION", "LIMIT", "ORDER")) || k.sym(i, ';'))) {
                whereEnd = Math.min(whereEnd, i);
            }
        }
        Scope s = new Scope(k, 0, k.size(), 0, false);
        s.sources = s.parseSources(first, stopAt < 0 ? (where < 0 ? k.size() : where) : stopAt);
        if (s.sources.size() != 1 || !s.sources.get(0).table()) {
            return null;
        }
        if (where >= 0 && !k.word(where + 1, "CURRENT")) {
            s.where = where;
            s.whereEnd = whereEnd;
        }
        return s;
    }

    private List<Src> parseSources(int from, int to) {
        List<Src> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            StringBuilder join = new StringBuilder();
            while (i < to && (k.wordIn(i, JOIN_WORDS) || k.sym(i, ','))) {
                join.append(join.length() == 0 ? "" : " ").append(k.sym(i, ',') ? "," : k.tok(i).upper());
                i++;
            }
            if (i >= to) {
                break;
            }
            if (k.wordIn(i, Set.of("LATERAL", "ONLY"))) {
                i++;
            }
            String name = null;
            boolean derived = false;
            int nameStart = i;
            int nameEnd;
            if (k.sym(i, '(')) {
                nameEnd = k.close(i);
                if (nameEnd < 0 || nameEnd >= to) {
                    opaque = true;
                    return List.of();
                }
                derived = k.word(i + 1, "SELECT") || k.word(i + 1, "WITH");
            } else if (k.isName(i)) {
                nameEnd = k.nameEnd(i);
                if (k.sym(nameEnd + 1, '(')) {
                    nameEnd = k.close(nameEnd + 1);          // table function
                    if (nameEnd < 0) {
                        opaque = true;
                        return List.of();
                    }
                } else {
                    name = k.slice(nameStart, nameEnd);
                }
            } else {
                opaque = true;
                return List.of();
            }
            int j = nameEnd + 1;
            if (name != null && k.sym(j, '@')) {
                name = null;                                  // database link
                int link = k.nameEnd(j + 1);
                if (link < 0) {
                    opaque = true;
                    return List.of();
                }
                j = link + 1;
            }
            j = skipHints(j, to);
            String exposed = null;
            if (k.word(j, "AS") && k.isName(j + 1)) {
                j++;
            }
            if (j < to && k.isName(j) && !k.wordIn(j, NOT_ALIAS)) {
                exposed = Toks.key(k.tok(j).text());
                j++;
                if (k.sym(j, '(')) {
                    name = null;                              // column alias list renames the columns
                    if (k.close(j) < 0) {
                        opaque = true;                        // unbalanced: do not guess
                        return List.of();
                    }
                    j = k.close(j) + 1;
                }
            } else if (name != null) {
                exposed = Toks.key(k.tok(nameEnd).text());
            }
            j = skipHints(j, to);
            int on = -1;
            while (j < to && !k.sym(j, ',') && !(k.wordIn(j, JOIN_WORDS) && k.depth[j] == k.depth[from])) {
                if (on < 0 && k.word(j, "ON")) {
                    on = j + 1;
                } else if (on < 0) {
                    name = null;                              // USING (…) or something we do not read
                    exposed = derived ? exposed : null;
                }
                j = k.sym(j, '(') && k.close(j) > j ? k.close(j) + 1 : j + 1;
            }
            if (j <= i) {
                opaque = true;                                // no progress: broken text, give up safely
                return List.of();
            }
            out.add(new Src(name, nameStart, nameEnd, exposed, on, j, join.toString(), derived, j));
            i = j;
        }
        return out;
    }

    private int skipHints(int j, int to) {
        if (k.word(j, "WITH") && k.sym(j + 1, '(') && k.close(j + 1) > 0 && k.close(j + 1) < to) {
            return k.close(j + 1) + 1;
        }
        if (k.wordIn(j, Set.of("USE", "FORCE", "IGNORE")) && k.wordIn(j + 1, Set.of("INDEX", "KEY"))
                && k.sym(j + 2, '(') && k.close(j + 2) > j + 2) {
            return k.close(j + 2) + 1;
        }
        return j;
    }

    /** The FROM item other clauses call {@code exposed}, or null. */
    Src byExposed(String exposed) {
        for (Src s : sources) {
            if (exposed.equals(s.exposed())) {
                return s;
            }
        }
        return null;
    }

    boolean hasCommaJoin() {
        return sources.stream().anyMatch(s -> s.join().equals(","));
    }

    /** Select items as token ranges {@code [from, to)} split on top-level commas. */
    List<int[]> items() {
        List<int[]> out = new ArrayList<>();
        if (!select || listStart < 0) {
            return out;
        }
        int s = listStart;
        for (int i = listStart; i < listEnd; i++) {
            if (k.depth[i] == depth && k.sym(i, ',')) {
                out.add(new int[]{s, i});
                s = i + 1;
            }
        }
        if (s < listEnd) {
            out.add(new int[]{s, listEnd});
        }
        return out;
    }

    /**
     * Ranges {@code [from, to)} of the predicates of this block: the WHERE clause and every ON condition.
     * Each comes with a flag telling whether it is a join condition.
     */
    List<int[]> predicateRanges() {
        List<int[]> out = new ArrayList<>();
        if (where >= 0) {
            out.add(new int[]{where + 1, whereEnd, 0});
        }
        for (Src s : sources) {
            if (s.on() >= 0) {
                out.add(new int[]{s.on(), s.onEnd(), 1});
            }
        }
        return out;
    }

    /**
     * Splits [from, to) on {@code AND} at the block's depth (BETWEEN … AND … is kept together). Returns
     * null when the range has an {@code OR} at that depth.
     */
    List<int[]> conjuncts(int from, int to) {
        int d = from < k.size() ? k.depth[from] : depth;
        List<int[]> out = new ArrayList<>();
        int s = from;
        boolean between = false;
        for (int i = from; i < to; i++) {
            if (k.depth[i] != d) {
                continue;
            }
            if (k.word(i, "OR")) {
                return null;
            }
            if (k.word(i, "BETWEEN")) {
                between = true;
            } else if (k.word(i, "AND")) {
                if (between) {
                    between = false;
                } else {
                    out.add(new int[]{s, i});
                    s = i + 1;
                }
            }
        }
        if (s < to) {
            out.add(new int[]{s, to});
        }
        return out;
    }

    /**
     * Ties the column reference ending at token {@code end} (a plain name, possibly qualified) to a FROM
     * item of this block: by its qualifier, or — unqualified — to the only table, or to the only table whose
     * metadata has the column. Null when it cannot be tied for sure.
     */
    ColRef column(int first, int end, RuleContext ctx) {
        if (!k.isPlainName(end) || k.tok(end).text().startsWith(":")) {
            return null;
        }
        String column = Toks.unquote(k.tok(end).text());
        if (end - first == 2 && k.sym(first + 1, '.')) {
            Src s = byExposed(Toks.key(k.tok(first).text()));
            return s == null || !s.table() ? null : new ColRef(s, column, first, end);
        }
        if (end != first || k.sym(first - 1, '.')) {
            return null;
        }
        Src only = null;
        for (Src s : sources) {
            if (!s.table()) {
                return null;
            }
            var m = ctx.meta(s.name());
            if (sources.size() == 1 || (m.isPresent() && m.get().column(column).isPresent())) {
                if (only != null) {
                    return null;
                }
                only = s;
            } else if (m.isEmpty()) {
                return null;
            }
        }
        return only == null ? null : new ColRef(only, column, first, end);
    }

    /**
     * The start of a column reference {@code [q.]col} ending at {@code end}, or -1.
     */
    int refStart(int end) {
        if (!k.isPlainName(end)) {
            return -1;
        }
        if (k.sym(end - 1, '.') && k.isName(end - 2) && !k.sym(end - 3, '.')) {
            return end - 2;
        }
        return k.sym(end - 1, '.') ? -1 : end;
    }

    /** End of the column reference {@code [q.]col} starting at {@code first}, or -1. */
    int refEnd(int first) {
        if (!k.isName(first) || k.wordIn(first, Toks.KEYWORDS) || k.tok(first).text().startsWith("@")) {
            return -1;
        }
        int e = k.nameEnd(first);
        if (e - first > 2 || k.sym(e + 1, '(') || !k.isPlainName(e)) {
            return -1;
        }
        return e;
    }
}
