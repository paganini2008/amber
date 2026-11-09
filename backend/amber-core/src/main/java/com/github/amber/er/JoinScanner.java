package com.github.amber.er;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.QueryBlock;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnEvidence;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.Transform;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.er.Tokens.Kind;
import com.github.amber.er.Tokens.Tok;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds equality join conditions between columns of two table references: {@code ON} (also nested
 * joins and MERGE … ON), {@code USING (…)}, {@code WHERE} equi-joins of comma joins, and correlated
 * subqueries ({@code t.k = o.k} with {@code o} from an enclosing query or the UPDATE / DELETE target).
 *
 * <p>Each query block of a statement's structure is read on its own, with nested queries masked; its
 * data sources (with aliases) come from the structure, and those of the statement itself (UPDATE / DELETE /
 * MERGE targets, T-SQL {@code UPDATE … FROM}) from its refs. A column of a CTE or derived table counts
 * only when lineage maps it 1:1 (a single PASS_THROUGH edge) to a base table column. Conditions joined
 * by OR, compared through functions, casts or arithmetic, or with an unresolvable side are skipped. An
 * unqualified column that several tables without metadata could own is tied to the one the script's column
 * evidence shows to have it ({@link ColumnEvidence}); the observation keeps that evidence for the note.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class JoinScanner {

    /** A column of a base table (or view / temp table) on one side of an equality. */
    record Side(ObjectName table, String kind, String column) {
    }

    /**
     * Equalities of one condition (one ON, USING, WHERE) between the same two table references: a
     * composite key when there are several. {@code left.get(i) = right.get(i)}.
     */
    record Observation(int statement, TextRange range, List<Side> left, List<Side> right,
                       List<ColumnEvidence.Fact> evidence) {

        Observation {
            evidence = List.copyOf(evidence);
        }

        Observation(int statement, TextRange range, List<Side> left, List<Side> right) {
            this(statement, range, left, right, List.of());
        }
    }

    static final Set<String> BASE_KINDS = Set.of("TABLE", "TEMP_TABLE", "TABLE_VARIABLE", "VIEW", "MATERIALIZED_VIEW");

    private static final Set<String> TERMINATORS = Set.copyOf(List.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS",
            "NATURAL", "OUTER", "APPLY", "STRAIGHT_JOIN", "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "INTERSECT",
            "EXCEPT", "MINUS", "WHEN", "THEN", "SET", "VALUES", "LIMIT", "CONNECT", "START", "WINDOW", "QUALIFY",
            "RETURNING", "OFFSET", "FETCH", "INTO", "OPTION", "FOR", "ON", "USING", "SELECT", "FROM", "PIVOT",
            "UNPIVOT", "OUTPUT", "LATERAL", "ELSE", "LOOP", "BEGIN", "DO", "MODEL", "INSERT", "UPDATE", "DELETE",
            "MERGE", "LOG"));

    /** Words that are no alias after a table name. */
    private static final Set<String> NOT_ALIAS = Set.copyOf(List.of("SET", "WHERE", "ON", "USING", "JOIN", "INNER", "LEFT", "RIGHT",
            "FULL", "CROSS", "OUTER", "NATURAL", "WITH", "VALUES", "SELECT", "FROM", "WHEN", "GROUP", "ORDER",
            "HAVING", "UNION", "LIMIT", "RETURNING", "OUTPUT", "DEFAULT", "PARTITION", "INTO", "OPTION", "APPLY",
            "EXCEPT", "INTERSECT", "MINUS", "BEGIN", "END", "THEN", "DO", "FOR", "CONNECT", "START", "WINDOW",
            "LATERAL", "TABLESAMPLE", "PIVOT", "UNPIVOT", "SAMPLE", "TOP", "AND", "OR", "NOT", "IS", "IN", "LOG",
            "IGNORE", "KEY", "ADD", "DROP", "ALTER", "COLUMN", "CONSTRAINT", "PRIMARY", "FOREIGN", "REFERENCES",
            "UNIQUE", "CHECK", "INDEX", "IF", "EXISTS", "CASCADE", "RESTRICT", "MATCHED", "BY", "ROWS", "FETCH",
            "OFFSET", "ONLY", "NOWAIT", "OF", "TO", "STRAIGHT_JOIN", "QUALIFY", "MODEL", "NOLOCK", "AS", "IS",
            "LOOP", "UPDATE", "DELETE", "INSERT", "MERGE", "TRUNCATE", "SKIP", "WAIT", "CASE", "ELSE", "LOCK",
            "SHARE", "MODE", "COMMIT", "PRESERVE", "TABLE", "VIEW"));

    /** Words that are values, not columns. */
    private static final Set<String> NOT_COLUMN = Set.copyOf(List.of("NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "CURRENT_TIME", "SYSDATE", "SYSTIMESTAMP", "LOCALTIMESTAMP", "LOCALTIME", "CURRENT_USER", "USER",
            "SESSION_USER", "SYSTEM_USER", "ROWNUM", "LEVEL", "DEFAULT", "UNKNOWN", "NOT", "CASE", "EXISTS", "ANY",
            "ALL", "SOME", "PRIOR", "DATE", "TIMESTAMP", "INTERVAL", "ROWID", "SQLCODE", "SQLERRM", "SQL%ROWCOUNT"));

    private static final Set<ObjectKind> REF_KINDS = Set.of(ObjectKind.TABLE, ObjectKind.TEMP_TABLE,
            ObjectKind.TABLE_VARIABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW);

    /** A data source visible in one query block. */
    private static final class Src {
        final int id;
        final String alias;            // upper-case, or null
        final ObjectName name;         // the table / CTE name, or null for an unnamed derived table
        final String display;          // owner display used by lineage (CTE name, derived alias, table name)
        final String kind;
        final List<String> columns;    // known column names, or null

        Src(int id, String alias, ObjectName name, String display, String kind, List<String> columns) {
            this.id = id;
            this.alias = alias;
            this.name = name;
            this.display = display;
            this.kind = kind;
            this.columns = columns;
        }

        Boolean has(String column) {
            if (columns == null) {
                return null;
            }
            for (String c : columns) {
                if (c.equalsIgnoreCase(column)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Scope(Scope parent, List<Src> sources) {
    }

    /** A column tied to a source; {@code evidence} is set when only the script's column evidence tied it. */
    private record Resolved(Src src, String column, ColumnEvidence.Fact evidence) {

        Resolved(Src src, String column) {
            this(src, column, null);
        }
    }

    private final Dialect dialect;
    private final SourceText source;
    private final Catalog catalog;
    private final ColumnEvidence evidence;
    private final List<Observation> out = new ArrayList<>();
    private final Set<ObjectRef> aliasRefs = Collections.newSetFromMap(new IdentityHashMap<>());
    private int equalities;
    private int unresolved;

    // per statement
    private int step;
    private int nextSrc;
    private Statement statement;
    private Map<String, List<ColumnEdge>> edgesInto;
    private List<QueryBlock> allBlocks;

    /** @param evidence the script's column evidence (places unqualified columns no metadata can), or null */
    JoinScanner(Dialect dialect, SourceText source, Catalog catalog, ColumnEvidence evidence) {
        this.dialect = dialect;
        this.source = source;
        this.catalog = catalog;
        this.evidence = evidence;
    }

    List<Observation> observations() {
        return out;
    }

    int equalities() {
        return equalities;
    }

    int unresolved() {
        return unresolved;
    }

    /** Refs that name an alias rather than a table (T-SQL {@code UPDATE o … FROM orders o}). */
    boolean isAlias(ObjectRef ref) {
        return aliasRefs.contains(ref);
    }

    /** Reads one statement ({@code step} is its id in the flattened script). */
    void scan(int step, Statement st) {
        this.step = step;
        this.statement = st;
        this.nextSrc = 0;
        this.edgesInto = null;
        this.allBlocks = new ArrayList<>();
        st.structure().forEach(b -> collect(b, allBlocks));
        List<QueryBlock> roots = st.structure();
        Scope top = statementScope(st, roots);
        if (scansOwnText(st.kind())) {
            List<int[]> masks = new ArrayList<>();
            for (QueryBlock b : roots) {
                if (b.range() != null) {
                    masks.add(new int[] {b.range().start().offset(), b.range().end().offset()});
                }
            }
            for (Statement c : st.children()) {
                masks.add(new int[] {c.range().start().offset(), c.range().end().offset()});
            }
            read(Tokens.of(source.text(), st.range().start().offset(), st.range().end().offset(), merged(masks)), top);
        }
        for (QueryBlock b : roots) {
            visit(b, top);
        }
    }

    private static void collect(QueryBlock b, List<QueryBlock> into) {
        into.add(b);
        b.children().forEach(c -> collect(c, into));
    }

    /** Statements whose own text (outside nested queries) may hold join conditions. */
    private static boolean scansOwnText(StatementKind k) {
        return switch (k.category()) {
            case QUERY, DML, PROCEDURAL, OTHER -> true;
            case DDL -> k == StatementKind.CREATE_TABLE_AS || k == StatementKind.CREATE_VIEW
                    || k == StatementKind.CREATE_MATERIALIZED_VIEW;
            default -> false;
        };
    }

    // ----- scopes ---------------------------------------------------------------------------------

    /**
     * Sources of the statement itself: tables it names outside its query blocks (UPDATE / DELETE / MERGE
     * targets and their FROM / USING tables) with the alias written after them, and derived tables at the
     * top. INSERT and CREATE targets are no sources.
     */
    private Scope statementScope(Statement st, List<QueryBlock> roots) {
        List<Src> sources = new ArrayList<>();
        List<ObjectRef> refs = new ArrayList<>();
        for (ObjectRef r : st.refs()) {
            if (r.via() == null && REF_KINDS.contains(r.kind()) && r.range() != null && !inside(r.range(), roots)
                    && r.access() != Access.INSERT
                    && r.access() != Access.CREATE) {
                refs.add(r);
            }
        }
        Set<String> aliases = new HashSet<>();
        Map<ObjectRef, String> aliasOf = new IdentityHashMap<>();
        for (ObjectRef r : refs) {
            String written = source.text().substring(r.range().start().offset(),
                    Math.min(source.length(), r.range().end().offset())).strip();
            ObjectName w = ObjectName.parse(dialect, written);
            String a = w != null && w.parts().size() == 1 && !w.simpleKey().equals(r.name().simpleKey())
                    ? w.simpleName()                        // T-SQL UPDATE o … FROM t o: the ref is written as its alias
                    : aliasAfter(r.range().end().offset(), st.range().end().offset());
            if (a != null && !a.equalsIgnoreCase(r.name().simpleName())) {
                aliasOf.put(r, a.toUpperCase(Locale.ROOT));
                aliases.add(a.toUpperCase(Locale.ROOT));
            }
        }
        for (ObjectRef r : refs) {
            if (r.name().parts().size() == 1 && aliases.contains(r.name().simpleKey()) && !aliasOf.containsKey(r)) {
                aliasRefs.add(r);                           // T-SQL UPDATE o … FROM orders o: "o" is an alias
                continue;
            }
            sources.add(new Src(nextSrc++, aliasOf.get(r), r.name(), r.name().toString(), r.kind().name(),
                    catalog.columns(r.name().key(), r.name().simpleKey()).orElse(null)));
        }
        for (QueryBlock b : roots) {
            if (b.kind() == QueryBlock.Kind.DERIVED && b.name() != null) {
                sources.add(derived(b));
            }
        }
        return new Scope(null, sources);
    }

    private static boolean inside(TextRange r, List<QueryBlock> blocks) {
        for (QueryBlock b : blocks) {
            if (b.range() != null && r.start().offset() >= b.range().start().offset()
                    && r.end().offset() <= b.range().end().offset()) {
                return true;
            }
        }
        return false;
    }

    /** {@code [AS] alias} right after a table name, or null. */
    private String aliasAfter(int from, int to) {
        List<Tok> toks = Tokens.of(source.text(), from, Math.min(to, from + 200), List.of());
        int i = 0;
        if (i < toks.size() && toks.get(i).is("AS")) {
            i++;
        }
        if (i < toks.size() && toks.get(i).isName()) {
            Tok t = toks.get(i);
            if (t.kind() == Kind.QUOTED || !NOT_ALIAS.contains(t.upper()) && !t.text().startsWith("@")) {
                return t.text();
            }
        }
        return null;
    }

    private Src derived(QueryBlock b) {
        List<String> cols = b.outputs().isEmpty() || b.outputs().contains("*") ? null : b.outputs();
        return new Src(nextSrc++, b.name().toUpperCase(Locale.ROOT), null, b.name(), "SUBQUERY", cols);
    }

    private void visit(QueryBlock b, Scope parent) {
        if (b.kind() == QueryBlock.Kind.SET || b.range() == null || b.sources().isEmpty() && b.phases().isEmpty()) {
            for (QueryBlock c : b.children()) {
                visit(c, parent);
            }
            return;
        }
        List<Src> sources = new ArrayList<>();
        for (QueryBlock.Source s : b.sources()) {
            sources.add(source(s, b));
        }
        Scope scope = new Scope(parent, sources);
        List<int[]> masks = new ArrayList<>();
        for (QueryBlock c : b.children()) {
            if (c.range() != null) {
                masks.add(new int[] {c.range().start().offset(), c.range().end().offset()});
            }
        }
        read(Tokens.of(source.text(), b.range().start().offset(), b.range().end().offset(), merged(masks)), scope);
        for (QueryBlock c : b.children()) {
            visit(c, scope);
        }
    }

    private Src source(QueryBlock.Source s, QueryBlock block) {
        String alias = s.alias() == null ? null : s.alias().toUpperCase(Locale.ROOT);
        switch (s.kind()) {
            case "CTE" -> {
                List<String> cols = null;
                for (QueryBlock b : allBlocks) {
                    if (b.kind() == QueryBlock.Kind.CTE && s.name().equalsIgnoreCase(b.name())) {
                        cols = b.outputs().isEmpty() || b.outputs().contains("*") ? null : b.outputs();
                    }
                }
                return new Src(nextSrc++, alias, ObjectName.parse(dialect, s.name()), s.name(), "CTE", cols);
            }
            case "SUBQUERY" -> {
                List<String> cols = null;
                for (QueryBlock c : block.children()) {
                    if (c.kind() == QueryBlock.Kind.DERIVED && s.name().equalsIgnoreCase(c.name())) {
                        cols = c.outputs().isEmpty() || c.outputs().contains("*") ? null : c.outputs();
                    }
                }
                return new Src(nextSrc++, alias != null ? alias : s.name().toUpperCase(Locale.ROOT), null, s.name(),
                        "SUBQUERY", cols);
            }
            default -> {
                ObjectName name = ObjectName.parse(dialect, s.name());
                List<String> cols = name == null ? null : catalog.columns(name.key(), name.simpleKey()).orElse(null);
                return new Src(nextSrc++, alias, name, s.name(), s.kind(), cols);
            }
        }
    }

    // ----- conditions -----------------------------------------------------------------------------

    /** Finds ON, USING and WHERE conditions in a block's own tokens. */
    private void read(List<Tok> toks, Scope scope) {
        int depth = 0;
        Src joined = null;
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.is("(") || t.is("CASE")) {
                depth++;
            } else if (t.is(")") || t.is("END") && depth > 0) {
                depth = Math.max(0, depth - 1);
            } else if (t.is("JOIN") || t.is("APPLY")) {
                joined = joinTarget(toks, i + 1, scope);
            } else if (t.is("ON") || t.is("WHERE")) {
                int end = conditionEnd(toks, i + 1, depth);
                condition(toks.subList(i + 1, end), scope);
                i = end - 1;
            } else if (t.is("USING") && joined != null && i + 1 < toks.size() && toks.get(i + 1).is("(")) {
                int end = using(toks, i + 2, scope, joined);
                if (end > i) {
                    i = end;
                }
            }
        }
    }

    private int conditionEnd(List<Tok> toks, int from, int startDepth) {
        int depth = startDepth;
        int j = from;
        for (; j < toks.size(); j++) {
            Tok t = toks.get(j);
            if (t.is("(") || t.is("CASE")) {
                depth++;
            } else if (t.is(")") || t.is("END") && depth > startDepth) {
                if (depth == startDepth) {
                    if (startDepth > 0) {
                        break;                                   // the parenthesis around a nested join ends
                    }
                    continue;
                }
                depth--;
            } else if (depth == startDepth && (t.is(",") || t.is(";") || t.kind() == Kind.IDENT
                    && TERMINATORS.contains(t.upper()))) {
                break;
            }
        }
        return j;
    }

    /** The source a JOIN brings in: the alias or name after the keyword. */
    private Src joinTarget(List<Tok> toks, int from, Scope scope) {
        List<String> name = new ArrayList<>();
        String alias = null;
        int depth = 0;
        for (int j = from; j < toks.size() && j < from + 40; j++) {
            Tok t = toks.get(j);
            if (t.is("(")) {
                depth++;
            } else if (t.is(")")) {
                depth--;
            } else if (depth > 0 || t.kind() == Kind.MASK) {
                continue;
            } else if (t.kind() == Kind.IDENT && (TERMINATORS.contains(t.upper()) || t.is("ON"))) {
                break;
            } else if (t.isName()) {
                if (name.isEmpty() || j > 0 && toks.get(j - 1).is(".")) {
                    if (name.isEmpty() && t.is("LATERAL")) {
                        continue;
                    }
                    name.add(t.text());
                } else if (!t.is("AS") && (t.kind() == Kind.QUOTED || !NOT_ALIAS.contains(t.upper()))) {
                    alias = t.text();
                }
            }
        }
        if (alias != null) {
            for (Src s : scope.sources()) {
                if (alias.equalsIgnoreCase(s.alias) || s.alias == null && s.name != null
                        && alias.equalsIgnoreCase(s.name.simpleName())) {
                    return s;
                }
            }
        }
        if (!name.isEmpty()) {
            return byName(scope, name);
        }
        return null;
    }

    /** {@code USING (a, b)}: each column of the joined source equals the same column of an earlier source. */
    private int using(List<Tok> toks, int from, Scope scope, Src right) {
        List<String> cols = new ArrayList<>();
        int j = from;
        for (boolean expectName = true; j < toks.size(); j++, expectName = !expectName) {
            Tok t = toks.get(j);
            if (!expectName && t.is(")")) {
                break;
            }
            if (expectName ? !t.isName() : !t.is(",")) {
                return -1;                                  // not a plain column list (MERGE … USING (subquery))
            }
            if (expectName) {
                cols.add(t.text());
            }
        }
        int at = scope.sources().indexOf(right);
        if (cols.isEmpty() || j >= toks.size() || at <= 0) {
            return cols.isEmpty() || j >= toks.size() ? -1 : j;
        }
        List<Src> before = scope.sources().subList(0, at);
        Map<Integer, List<Side[]>> byLeft = new LinkedHashMap<>();
        for (String c : cols) {
            Src left = null;
            for (Src s : before) {
                if (Boolean.TRUE.equals(s.has(c))) {
                    left = s;
                    break;
                }
            }
            if (left == null) {
                left = before.get(before.size() - 1);       // a JOIN b USING (k): usually the table just before
            }
            Side l = base(left, c);
            Side r = base(right, c);
            if (l == null || r == null) {
                unresolved++;
                continue;
            }
            equalities++;
            byLeft.computeIfAbsent(left.id, k -> new ArrayList<>()).add(new Side[] {l, r});
        }
        TextRange range = source.range(toks.get(from - 2).start(), toks.get(j).end());
        for (List<Side[]> pairs : byLeft.values()) {
            emit(range, pairs, List.of());
        }
        return j;
    }

    /** One ON / WHERE condition: its AND-ed equalities between columns of two different sources. */
    private void condition(List<Tok> toks, Scope scope) {
        List<List<Tok>> leaves = new ArrayList<>();
        if (!leaves(toks, leaves)) {
            return;                                         // a OR b at the top: no conjunct holds on its own
        }
        Map<String, List<Side[]>> groups = new LinkedHashMap<>();
        Map<String, int[]> spans = new HashMap<>();
        Map<String, List<ColumnEvidence.Fact>> facts = new HashMap<>();
        for (List<Tok> c : leaves) {
            int eq = topLevelEquals(c);
            if (eq < 0) {
                continue;
            }
            List<String> l = columnRef(c.subList(0, eq));
            List<String> r = columnRef(c.subList(eq + 1, c.size()));
            if (l == null || r == null) {
                continue;
            }
            Resolved a = resolve(l, scope);
            Resolved b = resolve(r, scope);
            if (a == null || b == null) {
                unresolved++;
                continue;
            }
            if (a.src() == b.src()) {
                continue;                                   // t.a = t.b: no join
            }
            Side sa = base(a.src(), a.column());
            Side sb = base(b.src(), b.column());
            if (sa == null || sb == null) {
                unresolved++;
                continue;
            }
            equalities++;
            boolean flip = a.src().id > b.src().id;
            Side first = flip ? sb : sa;
            Side second = flip ? sa : sb;
            String key = Math.min(a.src().id, b.src().id) + "|" + first.table().key() + "|"
                    + Math.max(a.src().id, b.src().id) + "|" + second.table().key();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new Side[] {first, second});
            for (Resolved x : List.of(a, b)) {
                if (x.evidence() != null) {
                    facts.computeIfAbsent(key, k -> new ArrayList<>()).add(x.evidence());
                }
            }
            int s = c.get(0).start();
            int e = c.get(c.size() - 1).end();
            spans.merge(key, new int[] {s, e}, (x, y) -> new int[] {Math.min(x[0], y[0]), Math.max(x[1], y[1])});
        }
        groups.forEach((key, pairs) -> emit(source.range(spans.get(key)[0], spans.get(key)[1]), pairs,
                facts.getOrDefault(key, List.of())));
    }

    /**
     * The AND-ed leaves of a condition, through parentheses; a parenthesized OR is left out, and false is
     * returned when the condition itself is an OR.
     */
    private static boolean leaves(List<Tok> toks, List<List<Tok>> out) {
        List<Tok> c = unwrap(toks);
        if (c.isEmpty()) {
            return true;
        }
        if (hasTopLevel(c, "OR")) {
            return false;
        }
        List<List<Tok>> parts = conjuncts(c);
        if (parts.size() == 1) {
            out.add(c);
            return true;
        }
        for (List<Tok> p : parts) {
            leaves(p, out);
        }
        return true;
    }

    private void emit(TextRange range, List<Side[]> pairs, List<ColumnEvidence.Fact> facts) {
        List<Side> left = new ArrayList<>();
        List<Side> right = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Side[] p : pairs) {
            if (seen.add(p[0].column().toUpperCase(Locale.ROOT) + "=" + p[1].column().toUpperCase(Locale.ROOT))) {
                left.add(p[0]);
                right.add(p[1]);
            }
        }
        out.add(new Observation(step, range, left, right, facts));
    }

    private static boolean hasTopLevel(List<Tok> toks, String word) {
        int depth = 0;
        for (Tok t : toks) {
            if (t.is("(") || t.is("CASE")) {
                depth++;
            } else if (t.is(")") || t.is("END")) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && t.kind() == Kind.IDENT && t.is(word)) {
                return true;
            }
        }
        return false;
    }

    private static List<List<Tok>> conjuncts(List<Tok> toks) {
        List<List<Tok>> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.is("(") || t.is("CASE")) {
                depth++;
            } else if (t.is(")") || t.is("END")) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && t.kind() == Kind.IDENT && t.is("AND")) {
                out.add(toks.subList(from, i));
                from = i + 1;
            }
        }
        out.add(toks.subList(from, toks.size()));
        return out;
    }

    /** Drops parentheses around the whole list. */
    private static List<Tok> unwrap(List<Tok> toks) {
        List<Tok> c = toks;
        while (c.size() >= 2 && c.get(0).is("(") && c.get(c.size() - 1).is(")") && closes(c)) {
            c = c.subList(1, c.size() - 1);
        }
        return c;
    }

    /** True when the first parenthesis closes at the last token. */
    private static boolean closes(List<Tok> c) {
        int depth = 0;
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i).is("(")) {
                depth++;
            } else if (c.get(i).is(")")) {
                depth--;
                if (depth == 0) {
                    return i == c.size() - 1;
                }
            }
        }
        return false;
    }

    /** Index of the only top-level {@code =}, or -1. */
    private static int topLevelEquals(List<Tok> toks) {
        int depth = 0;
        int at = -1;
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.is("(")) {
                depth++;
            } else if (t.is(")")) {
                depth--;
            } else if (depth == 0 && t.kind() == Kind.OP && t.text().equals("=")) {
                if (at >= 0) {
                    return -1;
                }
                at = i;
            }
        }
        return at;
    }

    /** {@code a.b.c} (with an Oracle {@code (+)} after it) as name parts, or null when it is anything else. */
    private static List<String> columnRef(List<Tok> toks) {
        int n = toks.size();
        if (n >= 3 && toks.get(n - 3).is("(") && toks.get(n - 2).is("+") && toks.get(n - 1).is(")")) {
            n -= 3;
        }
        if (n == 0 || n % 2 == 0) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Tok t = toks.get(i);
            if (i % 2 == 1) {
                if (!t.is(".")) {
                    return null;
                }
                continue;
            }
            if (t.kind() == Kind.QUOTED) {
                parts.add(t.text());
            } else if (t.kind() == Kind.IDENT && !t.text().startsWith("@") && !t.text().startsWith("$")
                    && !(n == 1 && NOT_COLUMN.contains(t.upper()))) {
                parts.add(t.text());
            } else {
                return null;
            }
        }
        return parts;
    }

    // ----- resolution -----------------------------------------------------------------------------

    /** The source of a column reference: by qualifier (alias, then name), else the one source that can have it. */
    private Resolved resolve(List<String> parts, Scope scope) {
        String column = parts.get(parts.size() - 1);
        if (parts.size() == 1) {
            for (Scope s = scope; s != null; s = s.parent()) {
                List<Src> has = new ArrayList<>();
                List<Src> unknown = new ArrayList<>();
                for (Src src : s.sources()) {
                    Boolean h = src.has(column);
                    if (h == null) {
                        unknown.add(src);
                    } else if (h) {
                        has.add(src);
                    }
                }
                if (has.size() == 1) {
                    return new Resolved(has.get(0), column);
                }
                if (has.size() > 1) {
                    return null;                            // ambiguous
                }
                if (unknown.size() > 1) {
                    return byEvidence(unknown, column);     // ambiguous unless the script's evidence tells
                }
                if (unknown.size() == 1) {
                    return new Resolved(unknown.get(0), column);
                }
            }
            return null;
        }
        List<String> qualifier = parts.subList(0, parts.size() - 1);
        for (Scope s = scope; s != null; s = s.parent()) {
            if (qualifier.size() == 1) {
                for (Src src : s.sources()) {
                    if (src.alias != null && src.alias.equalsIgnoreCase(qualifier.get(0))) {
                        return new Resolved(src, column);
                    }
                }
            }
            Src byName = byName(s, qualifier);
            if (byName != null) {
                return new Resolved(byName, column);
            }
        }
        return null;
    }

    /**
     * The only one of {@code unknown} (sources whose columns no metadata knows) the script's column evidence
     * shows to have {@code column}, or null. A CTE or derived table among them makes it null.
     */
    private Resolved byEvidence(List<Src> unknown, String column) {
        if (evidence == null) {
            return null;
        }
        List<ObjectName> names = new ArrayList<>(unknown.size());
        for (Src src : unknown) {
            names.add(BASE_KINDS.contains(src.kind) ? src.name : null);
        }
        ColumnEvidence.Choice c = evidence.choose(names, column);
        return c == null ? null : new Resolved(unknown.get(c.index()), column, c.fact());
    }

    private Src byName(Scope s, List<String> qualifier) {
        String q = String.join(".", qualifier).toUpperCase(Locale.ROOT);
        String last = qualifier.get(qualifier.size() - 1).toUpperCase(Locale.ROOT);
        Src simple = null;
        for (Src src : s.sources()) {
            if (src.name == null) {
                if (src.alias != null && qualifier.size() == 1 && src.alias.equals(last)) {
                    return src;
                }
                continue;
            }
            if (src.name.key().equals(q)) {
                return src;
            }
            if (src.name.simpleKey().equals(last) && simple == null) {
                simple = src;
            }
        }
        return simple;
    }

    /** The base-table column behind a source column: itself for tables, through a 1:1 lineage chain for CTEs. */
    private Side base(Src src, String column) {
        if (BASE_KINDS.contains(src.kind)) {
            return src.name == null ? null : new Side(src.name, src.kind, column);
        }
        ColumnNode.OwnerKind kind = "CTE".equals(src.kind) ? ColumnNode.OwnerKind.CTE : ColumnNode.OwnerKind.SUBQUERY;
        return trace(kind, src.display, column, 0);
    }

    private Side trace(ColumnNode.OwnerKind kind, String owner, String column, int depth) {
        if (depth > 8 || statement.lineage() == null) {
            return null;
        }
        List<ColumnEdge> in = edgesInto().getOrDefault(kind + "|" + owner.toUpperCase(Locale.ROOT) + "|"
                + column.toUpperCase(Locale.ROOT), List.of());
        if (in.size() != 1 || in.get(0).transform() != Transform.PASS_THROUGH) {
            return null;                                    // computed, aggregated or from several columns
        }
        ColumnNode from = in.get(0).from();
        if (from.isRows() || "*".equals(from.column())) {
            return null;
        }
        return switch (from.ownerKind()) {
            case TABLE, TEMP_TABLE, TABLE_VARIABLE, VIEW, MATERIALIZED_VIEW -> {
                ObjectName n = ObjectName.parse(dialect, from.owner());
                yield n == null ? null : new Side(n, from.ownerKind().name(), from.column());
            }
            case CTE, SUBQUERY -> trace(from.ownerKind(), from.owner(), from.column(), depth + 1);
            default -> null;
        };
    }

    private Map<String, List<ColumnEdge>> edgesInto() {
        if (edgesInto == null) {
            edgesInto = new HashMap<>();
            for (ColumnEdge e : statement.lineage().edges()) {
                ColumnNode to = e.to();
                if (e.isDirect() && (to.ownerKind() == ColumnNode.OwnerKind.CTE
                        || to.ownerKind() == ColumnNode.OwnerKind.SUBQUERY)) {
                    String k = to.ownerKind() + "|" + to.owner().toUpperCase(Locale.ROOT) + "|"
                            + to.column().toUpperCase(Locale.ROOT);
                    List<ColumnEdge> l = edgesInto.computeIfAbsent(k, x -> new ArrayList<>());
                    if (l.stream().noneMatch(x -> x.from().key().equals(e.from().key()))) {
                        l.add(e);
                    }
                }
            }
        }
        return edgesInto;
    }

    /** Sorted, merged copies of {@code [start, end)} ranges. */
    static List<int[]> merged(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(r -> r[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] r : sorted) {
            if (!out.isEmpty() && r[0] <= out.get(out.size() - 1)[1]) {
                out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], r[1]);
            } else {
                out.add(new int[] {r[0], r[1]});
            }
        }
        return out;
    }
}
