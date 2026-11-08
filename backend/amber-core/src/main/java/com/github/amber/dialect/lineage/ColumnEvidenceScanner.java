package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.lineage.ColumnEvidence;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a script's {@link ColumnEvidence} in one pass over its tokens, before the IR is built, so that every
 * statement can use evidence written anywhere in the script (also later).
 *
 * <p>The text is cut into statements at {@code ;}, {@code GO} and top-level {@code INSERT / UPDATE / DELETE /
 * MERGE / CREATE / ALTER / DROP / TRUNCATE} that do not continue the statement before (scripts without
 * semicolons). Each statement is read twice: first its table references ({@code FROM}, {@code JOIN}, comma
 * lists, {@code UPDATE}, {@code INTO}, {@code USING}, {@code DELETE}, {@code MERGE}) with their aliases, then
 * <ul>
 *   <li>{@code q.col} and {@code s.t.col} whose qualifier is one of those tables or aliases (an alias written
 *       for two different tables in the statement is ignored);</li>
 *   <li>{@code INSERT [INTO] t (c1, c2)} (also Oracle {@code INSERT ALL INTO t (…)}, T-SQL {@code OUTPUT … INTO
 *       @t (…)}) and MySQL {@code INSERT INTO t SET c = …};</li>
 *   <li>single-table {@code UPDATE t [alias] SET c = …} (T-SQL {@code UPDATE a SET … FROM t a} through the
 *       alias; multi-table MySQL updates only by qualified columns), PostgreSQL {@code ON CONFLICT … DO UPDATE
 *       SET}, MySQL {@code ON DUPLICATE KEY UPDATE};</li>
 *   <li>{@code MERGE INTO t … THEN UPDATE SET c = …} and {@code … THEN INSERT (c, …)}.</li>
 * </ul>
 * Tables created with a column list ({@code CREATE … TABLE t (…)}, {@code DECLARE @t TABLE (…)}) are noted so
 * that their DDL, not evidence, decides. PostgreSQL {@code $$} bodies are read as well. Time is linear in
 * the script's length.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class ColumnEvidenceScanner {

    private static final Set<String> INTRODUCERS = Set.of("FROM", "JOIN", "UPDATE", "INTO", "USING", "DELETE",
            "MERGE");
    /** Introducers after which {@code name(} is a table function, not a table with a column list. */
    private static final Set<String> READS = Set.of("FROM", "JOIN", "USING", ",");
    private static final Set<String> STARTERS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER",
            "DROP", "TRUNCATE");
    /** Words after which a statement keyword continues the statement (MERGE actions, triggers, grants …). */
    private static final Set<String> CONTINUES = Set.of("THEN", "KEY", "DO", "FOR", "OF", "OR", "AFTER", "BEFORE",
            "ON", "GRANT", "REVOKE", "INSTEAD", "EACH", "AND", "BY", "NOT", "TO", "IF", "EXISTS", "WITH");
    /** Words after which a top-level SELECT continues the statement (set operations, cursors, views). */
    private static final Set<String> SELECT_CONTINUES = Set.of("UNION", "ALL", "DISTINCT", "INTERSECT", "EXCEPT",
            "MINUS", "AS", "FOR", "THEN", "ELSE", "IS");
    /** Words that end a FROM or UPDATE list at its own parenthesis level. */
    private static final Set<String> LIST_END = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "UNION", "INTERSECT",
            "EXCEPT", "MINUS", "SET", "VALUES", "SELECT", "LIMIT", "CONNECT", "START", "WINDOW", "QUALIFY",
            "RETURNING", "WHEN", "FOR", "INTO", "OPTION", "FETCH", "OFFSET", "PIVOT", "UNPIVOT", "MODEL", "OUTPUT");
    /** Words that end an UPDATE … SET list. */
    private static final Set<String> SET_END = Set.of("WHERE", "FROM", "RETURNING", "OUTPUT", "WHEN", "OPTION",
            "LIMIT", "ORDER", "SELECT", "ON");
    /** Words that are no table name and no alias. */
    private static final Set<String> RESERVED = Set.of("SELECT", "FROM", "WHERE", "SET", "ON", "USING", "JOIN",
            "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "NATURAL", "WITH", "VALUES", "WHEN", "GROUP", "ORDER",
            "HAVING", "UNION", "LIMIT", "RETURNING", "OUTPUT", "DEFAULT", "PARTITION", "INTO", "OPTION", "APPLY",
            "EXCEPT", "INTERSECT", "MINUS", "BEGIN", "END", "THEN", "DO", "FOR", "CONNECT", "START", "WINDOW",
            "LATERAL", "TABLESAMPLE", "PIVOT", "UNPIVOT", "SAMPLE", "TOP", "AND", "OR", "NOT", "IS", "IN", "AS",
            "LOG", "IGNORE", "KEY", "TABLE", "VIEW", "IF", "EXISTS", "ONLY", "FETCH", "OFFSET", "STRAIGHT_JOIN",
            "QUALIFY", "MODEL", "NOLOCK", "LOOP", "UPDATE", "DELETE", "INSERT", "MERGE", "SKIP", "WAIT", "CASE",
            "ELSE", "LOCK", "TEMP", "TEMPORARY", "UNLOGGED", "ALL", "FIRST", "DISTINCT", "NULL", "BULK", "COLLECT",
            "STRICT", "OUTFILE", "DUMPFILE", "LOW_PRIORITY", "DELAYED", "HIGH_PRIORITY", "OVERWRITE", "TRUNCATE",
            "WHILE", "RETURN", "EXEC", "EXECUTE", "CALL", "DECLARE", "CURSOR", "GO", "USE", "FORCE", "INDEX", "OF",
            "NOWAIT");
    private static final Set<String> INSERT_MODIFIERS = Set.of("IGNORE", "LOW_PRIORITY", "DELAYED", "HIGH_PRIORITY",
            "OVERWRITE");
    private static final int MAX_NESTED_BODIES = 3;

    private final Dialect dialect;
    private final SourceText source;
    private final ColumnEvidence evidence = new ColumnEvidence();

    private ColumnEvidenceScanner(Dialect dialect, SourceText source) {
        this.dialect = dialect;
        this.source = source;
    }

    /** The column evidence of the whole script. */
    public static ColumnEvidence scan(Dialect dialect, SourceText source) {
        ColumnEvidenceScanner s = new ColumnEvidenceScanner(dialect, source);
        s.read(source.text(), 0, 0);
        return s.evidence;
    }

    // ----- statements ---------------------------------------------------------------------------------

    /** Reads {@code text} (the script, or a {@code $$} body starting at {@code base}). */
    private void read(String text, int base, int nesting) {
        SqlScanner sc = new SqlScanner(text, ScannerOptions.forDialect(dialect));
        List<ScanToken> statement = new ArrayList<>();
        int depth = 0;
        boolean fromAtTop = false;
        ScanToken t;
        while ((t = sc.next()) != null) {
            if (t.type() == ScanToken.Type.UNTERMINATED) {
                break;
            }
            if (t.type() == ScanToken.Type.STRING && t.text().startsWith("$") && nesting < MAX_NESTED_BODIES) {
                body(t, base, nesting);
                statement.add(shift(t, base));
                continue;
            }
            if (t.isSymbol(';')) {
                statement(statement);
                statement.clear();
                depth = 0;
                fromAtTop = false;
                continue;
            }
            if (t.type() == ScanToken.Type.WORD && depth <= 0 && !statement.isEmpty() && startsStatement(t,
                    statement.get(statement.size() - 1), fromAtTop)) {
                statement(statement);
                statement.clear();
                depth = 0;
                fromAtTop = false;
                if (t.isWord("GO")) {
                    continue;
                }
            }
            if (depth <= 0 && t.isWord("FROM")) {
                fromAtTop = true;
            }
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth--;
            }
            statement.add(shift(t, base));
        }
        statement(statement);
    }

    /** A PostgreSQL {@code $tag$ … $tag$} body: read its inside as statements of their own. */
    private void body(ScanToken t, int base, int nesting) {
        String s = t.text();
        int open = s.indexOf('$', 1);
        if (open < 0 || s.length() < 2 * (open + 1)) {
            return;
        }
        String tag = s.substring(0, open + 1);
        if (!s.endsWith(tag)) {
            return;
        }
        int from = open + 1;
        int to = s.length() - tag.length();
        if (to > from) {
            read(s.substring(from, to), base + t.start() + from, nesting + 1);
        }
    }

    private static ScanToken shift(ScanToken t, int base) {
        return base == 0 ? t : new ScanToken(t.type(), t.start() + base, t.end() + base, t.text());
    }

    /**
     * Whether {@code t} (a word at the top level) starts a new statement: {@code GO}, a statement keyword that
     * does not continue the one before, or a {@code SELECT} after a top-level FROM that is no set operation.
     */
    private static boolean startsStatement(ScanToken t, ScanToken prev, boolean fromAtTop) {
        if (t.isWord("GO")) {
            return true;
        }
        if (t.isWordIn(STARTERS)) {
            return !(prev.isSymbol(',') || prev.isSymbol('(') || prev.isWordIn(CONTINUES));
        }
        return fromAtTop && t.isWord("SELECT") && !prev.isSymbol('(') && !prev.isWordIn(SELECT_CONTINUES);
    }

    /** A table as one statement refers to it. */
    private record Table(ObjectName name, String display) {
    }

    /** Reads one statement's tables and aliases, then its evidence. */
    private void statement(List<ScanToken> t) {
        int n = t.size();
        if (n < 3) {
            return;
        }
        Segment s = new Segment(t);
        s.tables();
        s.evidence();
    }

    /** One statement's tokens and what pass 1 learned about them. */
    private final class Segment {
        final List<ScanToken> t;
        final int n;
        /** Tokens that are part of a table name (not qualified columns). */
        final boolean[] inName;
        /** Upper-case alias → table; null value when the alias names two different tables. */
        final Map<String, Table> aliases = new HashMap<>();
        /** Full and simple table key → table; null value when two different tables share it. */
        final Map<String, Table> tables = new HashMap<>();
        Table merge;
        Table insert;

        Segment(List<ScanToken> t) {
            this.t = t;
            this.n = t.size();
            this.inName = new boolean[n];
        }

        // ----- pass 1: tables and aliases ---------------------------------------------------------

        void tables() {
            Deque<Integer> lists = new ArrayDeque<>();
            int depth = 0;
            for (int i = 0; i < n; i++) {
                ScanToken tok = t.get(i);
                if (tok.isSymbol('(')) {
                    depth++;
                    continue;
                }
                if (tok.isSymbol(')')) {
                    depth--;
                    while (!lists.isEmpty() && lists.peek() > depth) {
                        lists.pop();
                    }
                    continue;
                }
                if (tok.isWordIn(LIST_END) && !lists.isEmpty() && lists.peek() == depth) {
                    lists.pop();
                }
                if (tok.isWord("CREATE")) {
                    created(i);
                } else if (tok.type() == ScanToken.Type.WORD && tok.text().startsWith("@")) {
                    tableVariable(i);
                }
                boolean afterComma = i > 0 && t.get(i - 1).isSymbol(',') && !lists.isEmpty() && lists.peek() == depth;
                boolean position = afterComma || i > 0 && t.get(i - 1).isWordIn(INTRODUCERS)
                        || i > 1 && t.get(i - 1).isWord("ONLY") && t.get(i - 2).isWordIn(INTRODUCERS);
                if (tok.isWord("FROM") || tok.isWord("UPDATE")) {
                    if (lists.isEmpty() || lists.peek() != depth) {
                        lists.push(depth);
                    }
                }
                if (!position || !isName(tok) || reserved(tok)) {
                    continue;
                }
                int e = nameEnd(i);
                String intro = afterComma ? "," : t.get(i - 1).isWord("ONLY") ? t.get(i - 2).upper() : t.get(i - 1).upper();
                if (e + 1 < n && t.get(e + 1).isSymbol('(') && READS.contains(intro)) {
                    continue;                               // a table function
                }
                Table table = table(i, e);
                if (table == null) {
                    continue;
                }
                put(tables, table.name().key(), table);
                put(tables, table.name().simpleKey(), table);
                int a = e + 1;
                if (a < n && t.get(a).isWord("AS")) {
                    a++;
                }
                if (a < n && isName(t.get(a)) && !reserved(t.get(a))
                        && !(a + 1 < n && (t.get(a + 1).isSymbol('.') || t.get(a + 1).isSymbol('(')))) {
                    put(aliases, part(t.get(a)).toUpperCase(java.util.Locale.ROOT), table);
                }
                i = e;
            }
        }

        /** {@code CREATE [OR REPLACE] [GLOBAL TEMPORARY …] TABLE [IF NOT EXISTS] t (…)}. */
        private void created(int i) {
            int k = i + 1;
            while (k < n && k <= i + 5 && t.get(k).type() == ScanToken.Type.WORD && !t.get(k).isWord("TABLE")) {
                k++;
            }
            if (k >= n || !t.get(k).isWord("TABLE")) {
                return;
            }
            k++;
            if (k + 2 < n && t.get(k).isWord("IF") && t.get(k + 1).isWord("NOT") && t.get(k + 2).isWord("EXISTS")) {
                k += 3;
            }
            if (k >= n || !isName(t.get(k))) {
                return;
            }
            int e = nameEnd(k);
            mark(k, e);
            if (e + 1 < n && t.get(e + 1).isSymbol('(')) {
                Table table = table(k, e);
                if (table != null) {
                    evidence.define(table.name().key(), table.name().simpleKey());
                }
            }
        }

        /** T-SQL {@code DECLARE @t [AS] TABLE (…)}. */
        private void tableVariable(int i) {
            int k = i + 1;
            if (k < n && t.get(k).isWord("AS")) {
                k++;
            }
            if (k + 1 < n && t.get(k).isWord("TABLE") && t.get(k + 1).isSymbol('(')) {
                Table table = table(i, i);
                if (table != null) {
                    evidence.define(table.name().key(), table.name().simpleKey());
                }
            }
        }

        // ----- pass 2: evidence -------------------------------------------------------------------

        void evidence() {
            for (int i = 0; i < n; i++) {
                ScanToken tok = t.get(i);
                if (inName[i]) {
                    continue;
                }
                if (isName(tok) && i + 2 < n && t.get(i + 1).isSymbol('.') && (i == 0 || !t.get(i - 1).isSymbol('.'))) {
                    int e = nameEnd(i);
                    if (e > i) {
                        qualified(i, e);
                        i = e;
                        continue;
                    }
                }
                if (tok.type() != ScanToken.Type.WORD) {
                    continue;
                }
                switch (tok.upper()) {
                    case "MERGE" -> merge(i);
                    case "UPDATE" -> update(i);
                    case "INSERT" -> insert(i);
                    case "INTO" -> into(i);
                    default -> {
                    }
                }
            }
        }

        /** {@code q.col} / {@code s.t.col} at tokens [i, e]. */
        private void qualified(int i, int e) {
            if (e + 1 < n && t.get(e + 1).isSymbol('(')
                    && !(e + 3 < n && t.get(e + 2).isSymbol('+') && t.get(e + 3).isSymbol(')'))) {
                return;                                     // a function or method call
            }
            Table table;
            if (e - i == 2) {
                String q = part(t.get(i)).toUpperCase(java.util.Locale.ROOT);
                table = aliases.containsKey(q) ? aliases.get(q) : tables.get(q);
            } else {
                StringBuilder q = new StringBuilder();
                for (int k = i; k < e; k += 2) {
                    q.append(k == i ? "" : ".").append(part(t.get(k)).toUpperCase(java.util.Locale.ROOT));
                }
                table = tables.get(q.toString());
            }
            if (table != null) {
                add(table, e, ColumnEvidence.Kind.QUALIFIED_REFERENCE);
            }
        }

        private void merge(int i) {
            int k = i + 1;
            if (k < n && t.get(k).isWord("INTO")) {
                k++;
            }
            if (k < n && isName(t.get(k)) && !reserved(t.get(k))) {
                merge = table(k, nameEnd(k));
            }
        }

        private void update(int i) {
            int k = i + 1;
            if (k < n && t.get(k).isWord("SET")) {
                if (merge != null) {
                    setList(k + 1, merge, ColumnEvidence.Kind.MERGE_ACTION);
                } else if (insert != null) {
                    setList(k + 1, insert, ColumnEvidence.Kind.UPDATE_SET);      // ON CONFLICT … DO UPDATE SET
                }
                return;
            }
            if (i >= 2 && t.get(i - 1).isWord("KEY") && t.get(i - 2).isWord("DUPLICATE")) {
                setList(k, insert, ColumnEvidence.Kind.UPDATE_SET);               // ON DUPLICATE KEY UPDATE
                return;
            }
            if (k < n && t.get(k).isWord("ONLY")) {
                k++;
            }
            if (k >= n || !isName(t.get(k)) || reserved(t.get(k))) {
                return;
            }
            int e = nameEnd(k);
            Table target = table(k, e);
            if (e == k) {
                Table aliased = aliases.get(part(t.get(k)).toUpperCase(java.util.Locale.ROOT));
                if (aliased != null) {
                    target = aliased;                       // T-SQL UPDATE a SET … FROM t a
                }
            }
            k = e + 1;
            if (k < n && t.get(k).isWord("AS")) {
                k++;
            }
            if (k < n && isName(t.get(k)) && !reserved(t.get(k))) {
                k++;                                        // alias
            }
            if (k < n && t.get(k).isWord("SET")) {
                setList(k + 1, target, ColumnEvidence.Kind.UPDATE_SET);
            }
        }

        private void insert(int i) {
            int k = i + 1;
            if (k < n && t.get(k).isSymbol('(')) {
                if (merge != null) {
                    columnList(k, merge, ColumnEvidence.Kind.MERGE_ACTION);
                }
                return;
            }
            while (k < n && t.get(k).isWordIn(INSERT_MODIFIERS)) {
                k++;
            }
            if (k < n && !t.get(k).isWord("INTO") && isName(t.get(k)) && !reserved(t.get(k))) {
                target(k, true);                            // T-SQL INSERT t (…)
            }
        }

        private void into(int i) {
            if (i + 1 >= n || !isName(t.get(i + 1)) || reserved(t.get(i + 1)) || i > 0 && t.get(i - 1).isWord("MERGE")) {
                return;
            }
            boolean insertInto = false;
            for (int k = i - 1; k >= 0 && k >= i - 3; k--) {
                if (t.get(k).isWord("INSERT")) {
                    insertInto = true;
                    break;
                }
                if (!t.get(k).isWordIn(INSERT_MODIFIERS)) {
                    break;
                }
            }
            target(i + 1, insertInto);
        }

        /** The table written at {@code k} and what follows it: {@code [[AS] alias] (cols)} or {@code SET c = …}. */
        private void target(int k, boolean insertTarget) {
            int e = nameEnd(k);
            Table table = table(k, e);
            if (table == null) {
                return;
            }
            if (insertTarget) {
                insert = table;
            }
            int j = e + 1;
            if (j < n && t.get(j).isWord("AS")) {
                j++;
                if (j < n && isName(t.get(j)) && !reserved(t.get(j))) {
                    j++;
                }
            }
            if (j < n && t.get(j).isSymbol('(')) {
                columnList(j, table, ColumnEvidence.Kind.INSERT_COLUMN_LIST);
            } else if (insertTarget && j < n && t.get(j).isWord("SET")) {
                setList(j + 1, table, ColumnEvidence.Kind.INSERT_COLUMN_LIST);     // MySQL INSERT … SET
            }
        }

        /** {@code (c1, c2, …)} at {@code k}: every item a plain name, else nothing is noted. */
        private void columnList(int k, Table table, ColumnEvidence.Kind kind) {
            List<Integer> cols = new ArrayList<>();
            int j = k + 1;
            while (j < n) {
                ScanToken tok = t.get(j);
                if (!isName(tok) || reserved(tok) || tok.text().startsWith("@")) {
                    return;
                }
                int e = nameEnd(j);
                cols.add(e);
                j = e + 1;
                if (j < n && t.get(j).isSymbol(',')) {
                    j++;
                } else if (j < n && t.get(j).isSymbol(')')) {
                    break;
                } else {
                    return;
                }
            }
            if (j >= n) {
                return;
            }
            for (int c : cols) {
                add(table, c, kind);
            }
        }

        /** {@code c = expr, c2 = expr …} from {@code k}: each bare name before {@code =} at the list's level. */
        private void setList(int k, Table table, ColumnEvidence.Kind kind) {
            if (table == null) {
                return;
            }
            int depth = 0;
            int cases = 0;
            boolean itemStart = true;
            for (int j = k; j < n; j++) {
                ScanToken tok = t.get(j);
                if (tok.isSymbol('(')) {
                    depth++;
                } else if (tok.isSymbol(')')) {
                    if (--depth < 0) {
                        return;
                    }
                } else if (tok.isWord("CASE")) {
                    cases++;
                } else if (tok.isWord("END") && cases > 0) {
                    cases--;
                } else if (depth == 0 && cases == 0) {
                    if (tok.isSymbol(',')) {
                        itemStart = true;
                        continue;
                    }
                    if (tok.isWordIn(SET_END)) {
                        return;
                    }
                    if (itemStart && isName(tok) && !tok.text().startsWith("@") && !reserved(tok)
                            && j + 1 < n && t.get(j + 1).isSymbol('=')) {
                        add(table, j, kind);
                    }
                }
                itemStart = false;
            }
        }

        // ----- helpers ----------------------------------------------------------------------------

        /** Last token of the dotted name starting at {@code i}. */
        private int nameEnd(int i) {
            int e = i;
            while (e + 2 < n && t.get(e + 1).isSymbol('.') && isName(t.get(e + 2))) {
                e += 2;
            }
            return e;
        }

        private void mark(int i, int e) {
            for (int k = i; k <= e; k++) {
                inName[k] = true;
            }
        }

        /** The table named by tokens [i, e]; they are marked as a name. */
        private Table table(int i, int e) {
            List<ObjectName.Part> parts = new ArrayList<>();
            StringBuilder display = new StringBuilder();
            for (int k = i; k <= e; k += 2) {
                ScanToken tok = t.get(k);
                String p = part(tok);
                if (p.isEmpty()) {
                    return null;
                }
                parts.add(new ObjectName.Part(p, tok.type() == ScanToken.Type.QUOTED_IDENTIFIER));
                display.append(k == i ? "" : ".").append(p);
            }
            mark(i, e);
            return new Table(ObjectName.of(dialect, parts), display.toString());
        }

        private void add(Table table, int columnToken, ColumnEvidence.Kind kind) {
            ObjectName name = table.name();
            String column = part(t.get(columnToken));
            if (column.isEmpty() || evidence.has(name.key(), name.simpleKey(), column)) {
                return;
            }
            int line = source.positionAt(Math.min(t.get(columnToken).start(), source.length())).line();
            evidence.add(name.key(), name.simpleKey(), table.display(), column, kind, line);
        }
    }

    /**
     * Adds {@code table} under {@code key}; a key already taken by a different table becomes ambiguous (null),
     * except that a qualified name replaces its unqualified twin.
     */
    private static void put(Map<String, Table> map, String key, Table table) {
        if (!map.containsKey(key)) {
            map.put(key, table);
            return;
        }
        Table have = map.get(key);
        if (have == null || have.name().key().equals(table.name().key())) {
            return;
        }
        if (have.name().sameObject(table.name())) {
            if (table.name().parts().size() > have.name().parts().size()) {
                map.put(key, table);
            }
            return;
        }
        map.put(key, null);
    }

    private static boolean isName(ScanToken t) {
        return t.type() == ScanToken.Type.WORD || t.type() == ScanToken.Type.QUOTED_IDENTIFIER;
    }

    private static boolean reserved(ScanToken t) {
        return t.type() == ScanToken.Type.WORD && t.isWordIn(RESERVED);
    }

    /** A name token unquoted ({@code "a""b"} → {@code a"b}, {@code [x]} → {@code x}). */
    private static String part(ScanToken t) {
        String s = t.text();
        if (t.type() != ScanToken.Type.QUOTED_IDENTIFIER || s.length() < 2) {
            return s;
        }
        char close = s.charAt(s.length() - 1);
        String inner = s.substring(1, s.length() - 1);
        return inner.replace(String.valueOf(close) + close, String.valueOf(close));
    }
}
