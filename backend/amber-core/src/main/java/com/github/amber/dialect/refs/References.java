package com.github.amber.dialect.refs;

import com.github.amber.core.analysis.Lifecycles;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.QueryBlock;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Find references" and "go to definition" inside one script: every place a table, view, temp table,
 * CTE, procedure or a column is read or written, and where the script defines it. In a legacy script of
 * thousands of lines this answers "where is this used?" before changing it.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class References {

    /** One occurrence: {@code statement} is the step index, {@code access} READ / INSERT / … or WRITE for columns. */
    /** {@code via}: set when the statement reaches the object through a procedure or function of the script. */
    public record Occurrence(int statement, String access, TextRange range, String line, String via) {

        public Occurrence(int statement, String access, TextRange range, String line) {
            this(statement, access, range, line, null);
        }
    }

    /**
     * @param definition where the script defines it (CREATE, CTE definition, DECLARE of a table variable),
     *                   or null when it comes from outside the script
     */
    public record Result(String name, String kind, Occurrence definition, List<Occurrence> occurrences) {
    }

    private References() {
    }

    /** References of an object (table, view, temp table, CTE, procedure…), matched case-insensitively. */
    public static Result ofObject(Script script, String name) {
        ObjectName wanted = ObjectName.parse(script.dialect(), name);
        if (wanted == null) {
            throw new IllegalArgumentException("Not an object name: " + name);
        }
        List<Statement> steps = Lifecycles.steps(script);
        SourceText src = script.source();
        List<Occurrence> found = new ArrayList<>();
        Occurrence definition = null;
        String kind = null;
        for (int id = 0; id < steps.size(); id++) {
            Statement st = steps.get(id);
            for (ObjectRef r : st.refs()) {
                if (!matches(r.name(), wanted)) {
                    continue;
                }
                kind = kind == null ? r.kind().name() : kind;
                Occurrence o = r.via() == null ? occurrence(src, id, r.access().name(), r.range())
                        : withVia(occurrence(src, id, r.access().name(), r.range()), r.via().toString());
                found.add(o);
                if (definition == null && r.via() == null && (r.access() == Access.CREATE || r.kind() == ObjectKind.CTE)) {
                    definition = r.kind() == ObjectKind.CTE ? cteDefinition(src, id, st, wanted, r.range()) : o;
                }
            }
            if (definition == null && st.name() != null && matches(st.name(), wanted)
                    && st.kind().name().startsWith("CREATE_")) {
                definition = occurrence(src, id, "CREATE", st.range());
                kind = kind == null ? st.kind().name().substring("CREATE_".length()) : kind;
            }
        }
        dedupe(found);
        return new Result(wanted.toString(), kind, definition, found);
    }

    /** References of {@code owner.column}: statements whose lineage uses it, located by its name in their text. */
    public static Result ofColumn(Script script, String owner, String column) {
        ObjectName wanted = ObjectName.parse(script.dialect(), owner);
        if (wanted == null || column == null || column.isBlank()) {
            throw new IllegalArgumentException("Give a table and a column");
        }
        List<Statement> steps = Lifecycles.steps(script);
        SourceText src = script.source();
        List<Occurrence> found = new ArrayList<>();
        Occurrence definition = null;
        ScannerOptions options = ScannerOptions.forDialect(script.dialect());
        for (int id = 0; id < steps.size(); id++) {
            Statement st = steps.get(id);
            if (!st.children().isEmpty()) {
                continue;                                     // containers: their children are visited themselves
            }
            boolean reads = false;
            boolean writes = false;
            if (st.lineage() != null) {
                for (ColumnEdge e : st.lineage().edges()) {
                    reads |= sameColumn(e.from(), wanted, column);
                    writes |= sameColumn(e.to(), wanted, column);
                }
            }
            boolean ddl = st.refs().stream().anyMatch(r -> matches(r.name(), wanted)
                    && (r.access() == Access.CREATE || r.access() == Access.ALTER));
            if (!reads && !writes && !ddl) {
                continue;
            }
            Set<String> qualifiers = qualifiers(st, wanted);
            boolean target = st.refs().stream().anyMatch(r -> matches(r.name(), wanted) && r.access() != Access.READ);
            java.util.function.IntPredicate inScope = offset -> {
                QueryBlock box = innermost(st.structure(), offset);
                return box == null ? target : box.sources().stream().anyMatch(s -> sameSource(s, wanted));
            };
            for (TextRange r : columnTokens(src, st.range(), column, qualifiers, options, inScope)) {
                Occurrence o = occurrence(src, id, ddl && !reads && !writes ? "CREATE" : writes ? "WRITE" : "READ", r);
                found.add(o);
                if (definition == null && ddl && st.kind().name().startsWith("CREATE_TABLE")) {
                    definition = o;
                }
            }
        }
        dedupe(found);
        return new Result(wanted + "." + column, "COLUMN", definition, found);
    }

    private static boolean matches(ObjectName a, ObjectName wanted) {
        return a.sameObject(wanted) || (wanted.parts().size() == 1 && a.simpleKey().equals(wanted.simpleKey()));
    }

    private static boolean sameColumn(ColumnNode n, ObjectName owner, String column) {
        if (n.isRows() || !n.column().equalsIgnoreCase(column)) {
            return false;
        }
        ObjectName o = ObjectName.parse(com.github.amber.core.Dialect.POSTGRESQL, n.owner());
        String key = n.ownerKey().toUpperCase(Locale.ROOT);
        return key.equals(owner.key()) || key.endsWith("." + owner.simpleKey()) || key.equals(owner.simpleKey())
                || (o != null && owner.parts().size() == 1 && o.simpleKey().equals(owner.simpleKey()));
    }

    /** Names a column of {@code owner} can be qualified with in a statement: the table name and its aliases. */
    private static Set<String> qualifiers(Statement st, ObjectName owner) {
        Set<String> q = new HashSet<>();
        q.add(owner.simpleKey());
        q.add(owner.key());
        collectAliases(st.structure(), owner, q);
        return q;
    }

    private static void collectAliases(List<QueryBlock> blocks, ObjectName owner, Set<String> out) {
        for (QueryBlock b : blocks) {
            for (QueryBlock.Source s : b.sources()) {
                ObjectName n = ObjectName.parse(com.github.amber.core.Dialect.POSTGRESQL, s.name());
                if (n != null && s.alias() != null && (n.sameObject(owner) || n.simpleKey().equals(owner.simpleKey()))) {
                    out.add(s.alias().toUpperCase(Locale.ROOT));
                }
            }
            collectAliases(b.children(), owner, out);
        }
    }

    /** {@code column} tokens in the range, unqualified or qualified by one of {@code qualifiers}. */
    private static List<TextRange> columnTokens(SourceText src, TextRange range, String column, Set<String> qualifiers,
                                                ScannerOptions options, java.util.function.IntPredicate inScope) {
        String text = src.slice(range);
        int base = range.start().offset();
        List<ScanToken> tokens = new ArrayList<>();
        SqlScanner sc = new SqlScanner(text, options);
        for (ScanToken t = sc.next(); t != null; t = sc.next()) {
            tokens.add(t);
        }
        String want = strip(column);
        List<TextRange> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            ScanToken t = tokens.get(i);
            if ((t.type() != ScanToken.Type.WORD && t.type() != ScanToken.Type.QUOTED_IDENTIFIER)
                    || !strip(t.text()).equalsIgnoreCase(want)) {
                continue;
            }
            boolean qualified = i >= 2 && tokens.get(i - 1).isSymbol('.');
            if (qualified && !qualifiers.contains(strip(tokens.get(i - 2).text()).toUpperCase(Locale.ROOT))) {
                continue;                                     // another table's column with the same name
            }
            if (i + 1 < tokens.size() && tokens.get(i + 1).isSymbol('.')) {
                continue;                                     // a qualifier itself (schema or alias)
            }
            if (i + 1 < tokens.size() && tokens.get(i + 1).isSymbol('(')) {
                continue;                                     // a function with the same name
            }
            if (!qualified && !inScope.test(base + t.start())) {
                continue;                                     // unqualified, in a query block that does not read the table
            }
            out.add(src.range(base + t.start(), base + t.end()));
        }
        return out;
    }

    /** The innermost structure box whose range contains {@code offset}, or null. */
    private static QueryBlock innermost(List<QueryBlock> blocks, int offset) {
        for (QueryBlock b : blocks) {
            if (b.range() != null && b.range().start().offset() <= offset && offset < b.range().end().offset()) {
                QueryBlock inner = innermost(b.children(), offset);
                if (inner != null) {
                    return inner;
                }
                return b.kind() == QueryBlock.Kind.SET ? null : b;
            }
        }
        return null;
    }

    private static boolean sameSource(QueryBlock.Source s, ObjectName owner) {
        ObjectName n = ObjectName.parse(com.github.amber.core.Dialect.POSTGRESQL, s.name());
        return n != null && (n.sameObject(owner) || n.simpleKey().equals(owner.simpleKey()));
    }

    private static String strip(String s) {
        String n = s.strip();
        if (n.length() >= 2 && "\"[`".indexOf(n.charAt(0)) >= 0) {
            n = n.substring(1, n.length() - 1);
        }
        return n;
    }

    /** The CTE definition (its name in {@code WITH name AS (…)}) in the statement, else the reference itself. */
    private static Occurrence cteDefinition(SourceText src, int id, Statement st, ObjectName cte, TextRange fallback) {
        QueryBlock def = findCte(st.structure(), cte.simpleKey());
        return def == null ? occurrence(src, id, "CREATE", fallback) : occurrence(src, id, "CREATE", def.range());
    }

    private static QueryBlock findCte(List<QueryBlock> blocks, String key) {
        for (QueryBlock b : blocks) {
            if (b.kind() == QueryBlock.Kind.CTE && b.name() != null && b.name().equalsIgnoreCase(key)) {
                return b;
            }
            QueryBlock inner = findCte(b.children(), key);
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    private static Occurrence occurrence(SourceText src, int id, String access, TextRange r) {
        int line = r.start().line();
        String text = src.text();
        int from = r.start().offset();
        while (from > 0 && text.charAt(from - 1) != '\n') {
            from--;
        }
        int to = text.indexOf('\n', r.start().offset());
        String snippet = text.substring(from, to < 0 ? text.length() : to).strip();
        if (snippet.length() > 160) {
            snippet = snippet.substring(0, 157) + "…";
        }
        return new Occurrence(id, access, r, line + ": " + snippet);
    }

    private static Occurrence withVia(Occurrence o, String via) {
        return new Occurrence(o.statement(), o.access(), o.range(), o.line(), via);
    }

    private static void dedupe(List<Occurrence> list) {
        Set<String> seen = new LinkedHashSet<>();
        list.removeIf(o -> !seen.add(o.range().start().offset() + ":" + o.range().end().offset() + ":" + o.access() + ":" + o.via()));
        list.sort(Comparator.comparingInt((Occurrence o) -> o.range().start().offset()));
    }
}
