package com.github.amber.ai;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replaces object names, column names and string literals with neutral aliases before SQL is sent to
 * an external AI provider, and restores them in the answer. Only known identifiers are replaced, so SQL
 * keywords and function names stay readable. The mapping stays on the user's machine.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Masking {

    private final Dialect dialect;
    private final Map<String, String> objects = new LinkedHashMap<>();   // upper-case name part -> alias
    private final Map<String, String> columns = new LinkedHashMap<>();
    private final Map<String, String> literals = new LinkedHashMap<>();
    private final Map<String, String> reverse = new LinkedHashMap<>();   // alias -> original text

    public Masking(Dialect dialect) {
        this.dialect = dialect;
    }

    /** Registers an object name part (schema, table, view, procedure). */
    public void object(String part) {
        register(objects, part, "T");
    }

    /** Registers a column name. */
    public void column(String name) {
        if (!objects.containsKey(name.toUpperCase(Locale.ROOT))) {
            register(columns, name, "C");
        }
    }

    private void register(Map<String, String> map, String name, String prefix) {
        String key = strip(name).toUpperCase(Locale.ROOT);
        if (key.isEmpty() || key.equals("*") || key.equals("*ROWS*")) {
            return;
        }
        map.computeIfAbsent(key, k -> {
            String alias = prefix + (map.size() + 1);
            reverse.put(alias, strip(name));
            return alias;
        });
    }

    /** SQL (or any text that quotes SQL) with registered names and all string literals replaced. */
    public String mask(String sql) {
        StringBuilder out = new StringBuilder();
        SqlScanner sc = new SqlScanner(sql, ScannerOptions.forDialect(dialect));
        int pos = 0;
        ScanToken t;
        while ((t = sc.next()) != null) {
            String replacement = null;
            if (t.type() == ScanToken.Type.WORD || t.type() == ScanToken.Type.QUOTED_IDENTIFIER) {
                String key = strip(t.text()).toUpperCase(Locale.ROOT);
                replacement = objects.containsKey(key) ? objects.get(key) : columns.get(key);
            } else if (t.type() == ScanToken.Type.STRING) {
                String key = t.text();
                String alias = literals.computeIfAbsent(key, k -> {
                    String a = "S" + (literals.size() + 1);
                    reverse.put("'" + a + "'", k);
                    return a;
                });
                replacement = "'" + alias + "'";
            }
            if (replacement != null) {
                out.append(sql, pos, t.start()).append(replacement);
                pos = t.end();
            }
        }
        return out.append(sql.substring(pos)).toString();
    }

    /** Restores original names and literals in an AI answer. */
    public static String unmask(Dialect dialect, String sql, Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return sql;
        }
        StringBuilder out = new StringBuilder();
        SqlScanner sc = new SqlScanner(sql, ScannerOptions.forDialect(dialect));
        int pos = 0;
        ScanToken t;
        while ((t = sc.next()) != null) {
            String original = null;
            if (t.type() == ScanToken.Type.WORD) {
                original = mapping.get(t.text().toUpperCase(Locale.ROOT));
                if (original == null) {
                    original = mapping.get(t.text());
                }
            } else if (t.type() == ScanToken.Type.STRING) {
                original = mapping.get(t.text());
            }
            if (original != null) {
                out.append(sql, pos, t.start()).append(original);
                pos = t.end();
            }
        }
        return out.append(sql.substring(pos)).toString();
    }

    /**
     * Replaces the names of {@code mapping} (alias → original) in free text the developer wrote, e.g. notes
     * or a follow-up question, so they reach the model under the same aliases as the context. Whole words
     * only, case-insensitive, longest names first; literals in the text are left as written.
     */
    public static String maskText(String text, Map<String, String> mapping) {
        if (text == null || text.isBlank() || mapping == null || mapping.isEmpty()) {
            return text;
        }
        List<Map.Entry<String, String>> names = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (!e.getKey().startsWith("'") && e.getValue().length() > 1) {
                names.add(e);
            }
        }
        names.sort((a, b) -> b.getValue().length() - a.getValue().length());
        String out = text;
        for (Map.Entry<String, String> e : names) {
            out = java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_$#])" + java.util.regex.Pattern.quote(e.getValue())
                            + "(?![\\p{L}\\p{N}_$#])", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                    .matcher(out).replaceAll(java.util.regex.Matcher.quoteReplacement(e.getKey()));
        }
        return out;
    }

    /** alias → original, to keep locally and pass to {@code ai.check}. */
    public Map<String, String> mapping() {
        return Map.copyOf(reverse);
    }

    private static String strip(String name) {
        String n = name.strip();
        if (n.length() >= 2 && Set.of('"', '[', '`').contains(n.charAt(0))) {
            n = n.substring(1, n.length() - 1);
        }
        return n;
    }
}
