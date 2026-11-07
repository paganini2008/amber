package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.ScanToken;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a rule sees of one statement: its tokens, the script around it and the metadata known for its
 * tables, plus helpers to build edits that keep the author's keyword case.
 *
 * @param catalog  imported metadata plus the tables the script itself creates (filled by the IR build)
 * @param parent   the container holding the statement (procedure, loop, block), or null at top level
 * @param creates  per table key: how many statements of the script create, alter or drop it
 * @param firstCreate per table key: step index of the first statement that creates it
 * @param steps    all statements of the script, by step index
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
record RewriteContext(Dialect dialect, SourceText source, Statement statement, int id, Sql sql, Catalog catalog,
                      Statement parent, Map<String, Integer> creates, Map<String, Integer> firstCreate,
                      List<Statement> steps) {

    private static final Pattern IGNORE = Pattern.compile("(?i)amber:ignore\\s+([A-Z]+-[A-Z]\\d{3}(?:\\s*,\\s*[A-Z]+-[A-Z]\\d{3})*)");

    // ----- edits ------------------------------------------------------------------------------------

    Edit edit(int start, int end, String replacement) {
        return new Edit(source.range(start, end), replacement);
    }

    Edit insert(int offset, String text) {
        return edit(offset, offset, text);
    }

    /** Replaces tokens {@code from..to} (inclusive). */
    Edit replace(int from, int to, String replacement) {
        return edit(sql.tok(from).start(), sql.tok(to).end(), replacement);
    }

    /** Deletes token {@code i} and the whitespace after it. */
    Edit deleteWord(int i) {
        return edit(sql.tok(i).start(), sql.spaceAfter(sql.tok(i).end()), "");
    }

    /** {@code word} in the case the author uses for keywords (taken from {@code reference}). */
    static String kw(String word, ScanToken reference) {
        String r = reference.text();
        return r.equals(r.toLowerCase(Locale.ROOT)) && !r.equals(r.toUpperCase(Locale.ROOT))
                ? word.toLowerCase(Locale.ROOT) : word.toUpperCase(Locale.ROOT);
    }

    /** {@code word} in the case of the statement's first keyword. */
    String kw(String word) {
        return kw(word, sql.tok(0));
    }

    // ----- metadata ---------------------------------------------------------------------------------

    Optional<TableMeta> meta(String tableName) {
        ObjectName n = tableName == null ? null : ObjectName.parse(dialect, tableName);
        return n == null ? Optional.empty() : catalog.meta(n.key(), n.simpleKey());
    }

    Optional<TableMeta> meta(Source s) {
        return s.kind() == Source.Kind.TABLE ? meta(s.name()) : Optional.empty();
    }

    /** NOT NULL per metadata: TRUE nullable, FALSE not null, null unknown. */
    Boolean nullable(Source s, String column) {
        return meta(s).flatMap(m -> m.column(column)).map(TableMeta.ColumnMeta::nullable).orElse(null);
    }

    /** Declared type of a column, upper-case, or null when unknown. */
    String type(Source s, String column) {
        return meta(s).flatMap(m -> m.column(column)).map(TableMeta.ColumnMeta::type)
                .filter(t -> t != null && !t.isBlank()).map(t -> t.toUpperCase(Locale.ROOT)).orElse(null);
    }

    /** Known column list of a table at this statement, or empty when unknown or ambiguous. */
    Optional<List<String>> columns(Source s) {
        if (s.kind() != Source.Kind.TABLE) {
            return Optional.empty();
        }
        ObjectName n = ObjectName.parse(dialect, s.name());
        if (n == null) {
            return Optional.empty();
        }
        Integer changes = creates.get(n.key());
        if (changes != null && (changes > 1 || firstCreate.getOrDefault(n.key(), Integer.MAX_VALUE) >= id)) {
            return Optional.empty();                // redefined in the script, or created only later
        }
        Optional<List<String>> cols = catalog.columns(n.key(), n.simpleKey()).filter(c -> !c.isEmpty());
        Integer created = firstCreate.get(n.key());
        if (cols.isPresent() && created != null && !writtenIn(steps.get(created), cols.get())) {
            return Optional.empty();                // the names read from the DDL do not match its text
        }
        return cols;
    }

    /** True when every column name is a name token of the statement that created the table. */
    private boolean writtenIn(Statement create, List<String> columns) {
        Sql ddl = new Sql(dialect, source.text(), create.range().start().offset(), create.range().end().offset());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < ddl.size(); i++) {
            if (ddl.isName(i)) {
                names.add(Sql.key(ddl.tok(i).text()));
            }
        }
        return columns.stream().allMatch(c -> names.contains(Sql.key(c)));
    }

    /** True when the metadata of the table came from a database or a pack (not from the script's DDL). */
    boolean catalogNamesExact(Source s) {
        return meta(s).map(m -> m.source() == null || !m.source().equals("script DDL")).orElse(false);
    }

    // ----- script ------------------------------------------------------------------------------------

    /** True when {@code -- amber:ignore RULE} is in the statement or on the line above it. */
    boolean ignored(String rule) {
        String text = source.text();
        int start = statement.range().start().offset();
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
        Matcher m = IGNORE.matcher(text.substring(prev, statement.range().end().offset()));
        while (m.find()) {
            for (String r : m.group(1).split("\\s*,\\s*")) {
                if (r.equalsIgnoreCase(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the statement runs once per row of a loop or is a cursor declaration. */
    boolean inCursorOrLoop() {
        return statement.kind() == com.github.amber.core.ir.StatementKind.CURSOR
                || (parent != null && parent.kind() == com.github.amber.core.ir.StatementKind.LOOP);
    }

    static Map<String, Integer> counts(List<Statement> steps, boolean firstIndex) {
        Map<String, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            for (ObjectRef r : steps.get(i).refs()) {
                if (r.access() == Access.CREATE || r.access() == Access.ALTER || r.access() == Access.DROP) {
                    if (firstIndex) {
                        if (r.access() == Access.CREATE) {
                            out.putIfAbsent(r.name().key(), i);
                        }
                    } else if (r.access() != Access.DROP) {
                        out.merge(r.name().key(), 1, Integer::sum);
                    }
                }
            }
        }
        return out;
    }
}
