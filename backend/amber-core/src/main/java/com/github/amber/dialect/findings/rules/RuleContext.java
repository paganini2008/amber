package com.github.amber.dialect.findings.rules;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.Finding.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the extra rules share for one script: the source, the imported metadata, the tables and indexes
 * the script creates itself, per-statement tokens and the {@code -- amber:ignore} check.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class RuleContext {

    private static final Pattern IGNORE = Pattern.compile(
            "(?i)amber:ignore\\s+([A-Z]+-[A-Z]\\d{3}(?:\\s*,\\s*[A-Z]+-[A-Z]\\d{3})*)");

    final Script script;
    final Dialect dialect;
    final SourceText source;
    final Catalog catalog;
    final List<Finding> out = new ArrayList<>();
    private final Map<Statement, Toks> tokens = new IdentityHashMap<>();
    /** Keys (full and simple) of tables the script creates: their imported metadata may not apply. */
    private final Set<String> created = new HashSet<>();
    /** Upper-case simple table name → upper-case leading columns of indexes the script creates. */
    private final Map<String, Set<String>> scriptIndexes = new HashMap<>();

    RuleContext(Script script, Catalog catalog) {
        this.script = script;
        this.dialect = script.dialect();
        this.source = script.source();
        this.catalog = catalog == null ? new Catalog() : catalog;
        for (Statement st : script.flatten()) {
            for (ObjectRef r : st.refs()) {
                if (r.access() == Access.CREATE && !r.indirect()) {
                    created.add(r.name().key());
                    created.add(r.name().simpleKey());
                }
            }
            if (st.kind() == StatementKind.CREATE_INDEX || st.kind() == StatementKind.ALTER) {
                readIndex(toks(st));
            }
        }
    }

    /** {@code CREATE [UNIQUE] INDEX … ON t (col …)} or {@code ALTER TABLE t ADD … KEY (col …)}. */
    private void readIndex(Toks k) {
        for (int i = 0; i + 2 < k.size(); i++) {
            boolean index = k.word(i, "ON") && k.isName(i + 1);
            boolean key = k.word(i, "ALTER") && k.word(i + 1, "TABLE") && k.isName(i + 2);
            if (!index && !key) {
                continue;
            }
            int nameStart = index ? i + 1 : i + 2;
            int nameEnd = k.nameEnd(nameStart);
            String table = Toks.key(k.tok(nameEnd).text());
            for (int j = nameEnd + 1; j + 1 < k.size(); j++) {
                if (k.sym(j, '(') && k.isName(j + 1) && (index ? j == nameEnd + 1
                        : k.wordIn(j - 1, Set.of("KEY", "UNIQUE")))) {
                    scriptIndexes.computeIfAbsent(table, x -> new HashSet<>()).add(Toks.key(k.tok(j + 1).text()));
                    if (index) {
                        break;
                    }
                }
            }
            return;
        }
    }

    Toks toks(Statement st) {
        return tokens.computeIfAbsent(st, s -> new Toks(dialect, source.text(), s.range().start().offset(),
                s.range().end().offset()));
    }

    /** Tokens of a container's own text: from its start to its first child (its header). */
    Toks header(Statement st) {
        int end = st.children().isEmpty() ? st.range().end().offset() : Integer.MAX_VALUE;
        for (Statement c : st.children()) {
            end = Math.min(end, c.range().start().offset());   // PL/pgSQL IF / LOOP ranges hold only the keyword
        }
        return new Toks(dialect, source.text(), st.range().start().offset(), end);
    }

    /**
     * Tokens of a routine or block up to its first {@code BEGIN} at depth 0 (its declarations and
     * parameters), or to its first child when there is none.
     */
    Toks declarations(Statement st) {
        int start = st.range().start().offset();
        int end = st.range().end().offset();
        int first = end;
        for (Statement c : st.children()) {
            first = Math.min(first, c.range().start().offset());
        }
        com.github.amber.dialect.split.SqlScanner sc = new com.github.amber.dialect.split.SqlScanner(source.text(),
                com.github.amber.dialect.split.ScannerOptions.forDialect(dialect));
        sc.reset(start);
        int level = 0;
        int stop = first;
        com.github.amber.dialect.split.ScanToken t;
        while ((t = sc.next()) != null && t.start() < end) {
            if (t.isSymbol('(')) {
                level++;
            } else if (t.isSymbol(')')) {
                level--;
            } else if (level == 0 && t.isWord("BEGIN")) {
                stop = t.start();
                break;
            }
        }
        return new Toks(dialect, source.text(), start, stop);
    }

    /** Imported metadata of a table named as written; empty for unknown tables and tables the script creates. */
    Optional<TableMeta> meta(String tableName) {
        ObjectName n = tableName == null ? null : ObjectName.parse(dialect, tableName);
        if (n == null || created.contains(n.key()) || created.contains(n.simpleKey())) {
            return Optional.empty();
        }
        return catalog.meta(n.key(), n.simpleKey());
    }

    /** True when the table (per metadata or an index the script creates) has an index led by {@code column}. */
    boolean indexedLeading(String tableName, TableMeta m, String column) {
        if (m.isIndexedLeading(column)) {
            return true;
        }
        for (List<String> uk : m.uniqueKeys()) {
            if (!uk.isEmpty() && uk.get(0).equalsIgnoreCase(column)) {
                return true;
            }
        }
        ObjectName n = ObjectName.parse(dialect, tableName);
        String simple = n == null ? tableName.toUpperCase(Locale.ROOT) : Toks.key(n.simpleName());
        return scriptIndexes.getOrDefault(simple, Set.of()).contains(column.toUpperCase(Locale.ROOT));
    }

    /** True when metadata names any key or index of the table, so the absence of one means something. */
    static boolean hasIndexInfo(TableMeta m) {
        return !m.primaryKey().isEmpty() || !m.uniqueKeys().isEmpty() || !m.indexes().isEmpty();
    }

    /** Whether {@code -- amber:ignore RULE} is in the statement or on the line above it. */
    boolean ignored(Statement st, String rule) {
        String text = source.text();
        int start = st.range().start().offset();
        int i = start;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        int prev = i;
        if (i > 0) {
            prev = i - 1;
            while (prev > 0 && text.charAt(prev - 1) != '\n') {
                prev--;
            }
        }
        Matcher m = IGNORE.matcher(text.substring(Math.min(prev, start), st.range().end().offset()));
        while (m.find()) {
            for (String id : m.group(1).split("\\s*,\\s*")) {
                if (id.equalsIgnoreCase(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    TextRange range(Toks k, int from, int to) {
        return source.range(k.tok(from).start(), Math.max(k.tok(from).start(), k.tok(to).end()));
    }

    void add(String rule, Severity severity, String title, String message, String suggestion, Statement st,
             TextRange range, Confidence confidence) {
        add(rule, severity, title, message, suggestion, st, range, confidence, null);
    }

    void add(String rule, Severity severity, String title, String message, String suggestion, Statement st,
             TextRange range, Confidence confidence, Finding.SuggestedSql suggested) {
        out.add(new Finding(rule, severity, title, message, suggestion, st, range, confidence, suggested));
    }

    /** Source text of a statement. */
    String text(Statement st) {
        return source.text().substring(st.range().start().offset(), st.range().end().offset());
    }

    /** The statement's text with script offsets [start, end) replaced. */
    String replaced(Statement st, int start, int end, String replacement) {
        return SqlCheck.replace(source.text(), st.range().start().offset(), st.range().end().offset(), start, end,
                replacement);
    }

    /** Plain DML and queries that the token rules read (not containers, client commands or data). */
    static boolean readable(Statement st) {
        return !st.kind().isContainer() && st.kind() != StatementKind.CLIENT_COMMAND && st.kind() != StatementKind.DATA
                && st.kind() != StatementKind.UNPARSED && st.kind() != StatementKind.DYNAMIC_SQL;
    }
}
