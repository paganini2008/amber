package com.github.amber.core.lineage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Column lists known for tables: from the script's own DDL (CREATE TABLE, CREATE TABLE AS, SELECT
 * INTO, views) and, later, from imported metadata. Used to expand {@code *}, to map {@code INSERT}
 * without a column list, and to resolve unqualified columns when several tables are joined.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Catalog {

    private final Map<String, List<String>> byKey = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bySimpleKey = new ConcurrentHashMap<>();
    private final Map<String, com.github.amber.core.meta.TableMeta> metaByKey = new ConcurrentHashMap<>();
    private final Map<String, com.github.amber.core.meta.TableMeta> metaBySimpleKey = new ConcurrentHashMap<>();
    /** Upper-case simple names of routines the analyzed script may define (calls of them are followed). */
    private final java.util.Set<String> routines = ConcurrentHashMap.newKeySet();
    /** Columns the analyzed script shows its tables to have; null until a script is built with this catalog. */
    private volatile ColumnEvidence evidence;

    /** A catalog pre-filled with imported metadata (DDL files, packs, online reads). */
    public static Catalog of(java.util.Collection<com.github.amber.core.meta.TableMeta> tables) {
        Catalog c = new Catalog();
        tables.forEach(c::add);
        return c;
    }

    /** Adds full table metadata; its columns also become the table's column list. */
    public void add(com.github.amber.core.meta.TableMeta t) {
        String simple = t.name().toUpperCase(Locale.ROOT);
        String key = t.schema() == null || t.schema().isBlank() ? simple
                : t.schema().toUpperCase(Locale.ROOT) + "." + simple;
        metaByKey.put(key, t);
        metaBySimpleKey.putIfAbsent(simple, t);
        if (t.catalog() != null && !t.catalog().isBlank()) {
            metaByKey.put(t.catalog().toUpperCase(Locale.ROOT) + "." + key, t);
        }
        if (!t.columnNames().isEmpty()) {
            put(key, simple, t.columnNames());
        }
    }

    /** Full metadata of a table, when known from imports or online mode. */
    public Optional<com.github.amber.core.meta.TableMeta> meta(String key, String simpleKey) {
        var m = metaByKey.get(key);
        if (m == null) {
            m = metaBySimpleKey.get(simpleKey);
        }
        return Optional.ofNullable(m);
    }

    /** Every table with full metadata (for saving a metadata pack). */
    public List<com.github.amber.core.meta.TableMeta> tables() {
        return new ArrayList<>(new java.util.LinkedHashSet<>(metaByKey.values()));
    }

    /** A copy, so one analysis cannot change the shared imported catalog. */
    public Catalog copy() {
        Catalog c = new Catalog();
        c.byKey.putAll(byKey);
        c.bySimpleKey.putAll(bySimpleKey);
        c.metaByKey.putAll(metaByKey);
        c.metaBySimpleKey.putAll(metaBySimpleKey);
        c.routines.addAll(routines);
        c.evidence = evidence;
        return c;
    }

    /**
     * The script's own column evidence (see {@link ColumnEvidence}): set when a script is built with this
     * catalog, null before. It never adds columns to a table; it only places unqualified columns that no
     * metadata can.
     */
    public ColumnEvidence evidence() {
        return evidence;
    }

    /** Sets the script's column evidence (the IR builder does, once per script). */
    public void setEvidence(ColumnEvidence evidence) {
        this.evidence = evidence;
    }

    /**
     * Notes that the script may define a routine with this simple name, so lineage follows the arguments
     * of its calls (see {@code CallLineage}); the call is linked or dropped once the whole script is read.
     */
    public void addRoutine(String simpleName) {
        routines.add(simpleName.toUpperCase(Locale.ROOT));
    }

    /** Whether {@link #addRoutine} named {@code simpleName}. */
    public boolean isRoutine(String simpleName) {
        return !routines.isEmpty() && routines.contains(simpleName.toUpperCase(Locale.ROOT));
    }

    /** Whether any routine name is known. */
    public boolean hasRoutines() {
        return !routines.isEmpty();
    }

    /** Records the columns of a table; {@code key} and {@code simpleKey} as in {@link com.github.amber.core.ir.ObjectName}. */
    public void put(String key, String simpleKey, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        List<String> copy = List.copyOf(columns);
        byKey.put(key, copy);
        bySimpleKey.put(simpleKey, copy);
    }

    public Optional<List<String>> columns(String key, String simpleKey) {
        List<String> c = byKey.get(key);
        if (c == null) {
            c = bySimpleKey.get(simpleKey);
        }
        return Optional.ofNullable(c);
    }

    public boolean hasColumn(String key, String simpleKey, String column) {
        return columns(key, simpleKey).map(cols -> containsIgnoreCase(cols, column)).orElse(false);
    }

    static boolean containsIgnoreCase(List<String> columns, String column) {
        String c = column.toUpperCase(Locale.ROOT);
        for (String s : columns) {
            if (s.toUpperCase(Locale.ROOT).equals(c)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return byKey.size();
    }

    public List<String> keys() {
        return new ArrayList<>(byKey.keySet());
    }
}
