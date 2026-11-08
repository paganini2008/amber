package com.github.amber.core.lineage;

import com.github.amber.core.ir.ObjectName;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Columns a script itself shows its tables to have, read offline from its own text: qualified references
 * ({@code orders.cust_id}, {@code o.cust_id} with {@code o} an alias of {@code orders}), {@code INSERT INTO t
 * (c1, c2)} column lists, {@code UPDATE t SET c = …} and the {@code UPDATE SET} / {@code INSERT (…)} actions of a
 * {@code MERGE}. Built once per script (see {@code ColumnEvidenceScanner}) and kept on the script's
 * {@link Catalog}.
 *
 * <p>The evidence only places an unqualified column that several tables in scope could own and no metadata
 * decides: when exactly one of the candidate tables is known to have it, the column is attributed there
 * (never with certainty). Real metadata (the script's DDL, DDL files, packs, online reads) always comes first:
 * a table whose columns are known is judged by them alone, and a table the script creates with a column list
 * blocks the evidence until its DDL has been read.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class ColumnEvidence {

    /** What shows that a table has a column. */
    public enum Kind {
        /** {@code t.col}, or {@code a.col} with {@code a} an alias of {@code t}. */
        QUALIFIED_REFERENCE,
        /** {@code INSERT INTO t (c1, c2, …)} (also MySQL {@code INSERT INTO t SET c = …}). */
        INSERT_COLUMN_LIST,
        /** {@code UPDATE t SET c = …} (also PostgreSQL {@code ON CONFLICT … DO UPDATE SET}). */
        UPDATE_SET,
        /** {@code MERGE INTO t … WHEN MATCHED THEN UPDATE SET c = …} or {@code … THEN INSERT (c, …)}. */
        MERGE_ACTION
    }

    /**
     * The first place the script shows a column of a table.
     *
     * @param table  the table as written there ({@code sales.orders})
     * @param column the column as written there, unquoted
     * @param line   1-based line of the column
     */
    public record Fact(String table, String column, Kind kind, int line) {
    }

    /** The candidate the evidence places a column in. */
    public record Choice(int index, Fact fact) {
    }

    /** No evidence at all. */
    public static final ColumnEvidence EMPTY = new ColumnEvidence();

    /** Simple table key → full table key → upper-case column → first fact. */
    private final Map<String, Map<String, Map<String, Fact>>> facts = new HashMap<>();
    /** Simple table key → full keys of tables the script creates with a column list. */
    private final Map<String, Map<String, Boolean>> defined = new HashMap<>();
    private int size;

    /**
     * Notes that the table has the column; only the first fact per table and column is kept.
     *
     * @param key       the table's full key (upper case, as {@link ObjectName#key()})
     * @param simpleKey the table's simple key
     * @param display   the table as written
     * @param line      1-based line of the column
     */
    public void add(String key, String simpleKey, String display, String column, Kind kind, int line) {
        String c = normalize(column);
        if (c.isEmpty()) {
            return;
        }
        Map<String, Fact> cols = facts.computeIfAbsent(simpleKey, k -> new HashMap<>())
                .computeIfAbsent(key, k -> new HashMap<>());
        if (!cols.containsKey(c)) {
            cols.put(c, new Fact(display, unquote(column), kind, line));
            size++;
        }
    }

    /** Whether a fact for this table and column is already known (callers skip computing its line). */
    public boolean has(String key, String simpleKey, String column) {
        Map<String, Map<String, Fact>> byKey = facts.get(simpleKey);
        if (byKey == null) {
            return false;
        }
        Map<String, Fact> cols = byKey.get(key);
        return cols != null && cols.containsKey(normalize(column));
    }

    /** Notes a table the script creates with a column list ({@code CREATE TABLE t (…)}, {@code DECLARE @t TABLE (…)}). */
    public void define(String key, String simpleKey) {
        defined.computeIfAbsent(simpleKey, k -> new HashMap<>()).put(key, Boolean.TRUE);
    }

    /** Number of table / column facts. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * The fact that shows {@code table} has {@code column}, or null. An unqualified name and a qualified name
     * with the same last part denote the same table (as {@link ObjectName#sameObject}).
     */
    public Fact find(ObjectName table, String column) {
        Map<String, Map<String, Fact>> byKey = facts.get(table.simpleKey());
        if (byKey == null) {
            return null;
        }
        String c = normalize(column);
        for (Map.Entry<String, Map<String, Fact>> e : byKey.entrySet()) {
            Fact f = e.getValue().get(c);
            if (f != null && compatible(e.getKey(), table)) {
                return f;
            }
        }
        return null;
    }

    /** Whether the script creates {@code table} with a column list. */
    public boolean definedByScript(ObjectName table) {
        Map<String, Boolean> keys = defined.get(table.simpleKey());
        if (keys == null) {
            return false;
        }
        for (String k : keys.keySet()) {
            if (compatible(k, table)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The only one of {@code candidates} the evidence shows to have {@code column}, or null when none or
     * several do. The candidates are the sources in scope whose columns no metadata knows; a {@code null}
     * element is a source the evidence cannot speak for (a derived table or CTE with unknown columns), and it,
     * like a table the script creates with a column list (its DDL decides once read), makes the answer null.
     */
    public Choice choose(List<ObjectName> candidates, String column) {
        if (size == 0 || candidates.isEmpty()) {
            return null;
        }
        Choice found = null;
        for (int i = 0; i < candidates.size(); i++) {
            ObjectName n = candidates.get(i);
            if (n == null || definedByScript(n)) {
                return null;
            }
            Fact f = find(n, column);
            if (f != null) {
                if (found != null) {
                    return null;                    // several candidates have it: still ambiguous
                }
                found = new Choice(i, f);
            }
        }
        return found;
    }

    private static boolean compatible(String key, ObjectName table) {
        return key.equals(table.key()) || key.indexOf('.') < 0 || table.parts().size() == 1;
    }

    static String normalize(String column) {
        return unquote(column).toUpperCase(Locale.ROOT);
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if (a == '"' && b == '"' || a == '`' && b == '`' || a == '[' && b == ']') {
                return t.substring(1, t.length() - 1);
            }
        }
        return t;
    }
}
