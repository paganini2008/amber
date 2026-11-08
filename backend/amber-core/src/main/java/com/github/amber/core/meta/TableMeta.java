package com.github.amber.core.meta;

import java.util.List;

/**
 * What Amber knows about one table or view: columns, keys, indexes and size. Comes from DDL, a
 * metadata pack or a database catalog.
 *
 * @param catalog     database / catalog name, or null
 * @param schema      schema / owner, or null
 * @param name        table name
 * @param kind        TABLE, VIEW, …
 * @param rowEstimate estimated rows, or -1 when unknown
 * @param source      where the metadata came from (a file, a pack, a data source id)
 * @param foreignKeys declared foreign keys of this table (DDL {@code REFERENCES} / {@code FOREIGN KEY},
 *                    {@code DatabaseMetaData.getImportedKeys}); empty when none are known — packs written
 *                    before this field existed load with an empty list
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record TableMeta(String catalog, String schema, String name, String kind, List<ColumnMeta> columns,
                        List<String> primaryKey, List<List<String>> uniqueKeys, List<IndexMeta> indexes,
                        long rowEstimate, String source, List<ForeignKeyMeta> foreignKeys) {

    /** The same metadata without foreign keys (the shape before {@code foreignKeys} was added). */
    public TableMeta(String catalog, String schema, String name, String kind, List<ColumnMeta> columns,
                     List<String> primaryKey, List<List<String>> uniqueKeys, List<IndexMeta> indexes,
                     long rowEstimate, String source) {
        this(catalog, schema, name, kind, columns, primaryKey, uniqueKeys, indexes, rowEstimate, source, null);
    }

    public record ColumnMeta(String name, String type, boolean nullable) {
    }

    public record IndexMeta(String name, List<String> columns, boolean unique) {
    }

    /**
     * A foreign key: {@code columns} of this table reference {@code refColumns} of the referenced table
     * (pairwise, in order). {@code refColumns} is empty when the DDL names only the table
     * ({@code REFERENCES customers}), which means its primary key.
     *
     * @param name constraint name, or null
     */
    public record ForeignKeyMeta(String name, List<String> columns, String refCatalog, String refSchema,
                                 String refTable, List<String> refColumns) {

        public ForeignKeyMeta {
            columns = columns == null ? List.of() : List.copyOf(columns);
            refColumns = refColumns == null ? List.of() : List.copyOf(refColumns);
        }
    }

    public TableMeta {
        columns = columns == null ? List.of() : List.copyOf(columns);
        primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        uniqueKeys = uniqueKeys == null ? List.of() : uniqueKeys.stream().map(List::copyOf).toList();
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
    }

    /** A copy with these foreign keys. */
    public TableMeta withForeignKeys(List<ForeignKeyMeta> keys) {
        return new TableMeta(catalog, schema, name, kind, columns, primaryKey, uniqueKeys, indexes, rowEstimate,
                source, keys);
    }

    /** Qualified display name: {@code catalog.schema.name} without empty parts. */
    public String qualifiedName() {
        StringBuilder sb = new StringBuilder();
        if (catalog != null && !catalog.isBlank()) {
            sb.append(catalog).append('.');
        }
        if (schema != null && !schema.isBlank()) {
            sb.append(schema).append('.');
        }
        return sb.append(name).toString();
    }

    /** The column with this name, matched case-insensitively. */
    public java.util.Optional<ColumnMeta> column(String name) {
        String n = name == null ? "" : name.replaceAll("^[\"`\\[]|[\"`\\]]$", "");
        return columns.stream().filter(c -> c.name().equalsIgnoreCase(n)).findFirst();
    }

    public List<String> columnNames() {
        return columns.stream().map(ColumnMeta::name).toList();
    }

    /** True when {@code cols} cover the primary key or a unique key (so a join on them cannot fan out). */
    public boolean isUnique(List<String> cols) {
        List<String> upper = cols.stream().map(c -> c.toUpperCase(java.util.Locale.ROOT)).toList();
        if (!primaryKey.isEmpty() && upper.containsAll(upper(primaryKey))) {
            return true;
        }
        for (List<String> uk : uniqueKeys) {
            if (!uk.isEmpty() && upper.containsAll(upper(uk))) {
                return true;
            }
        }
        for (IndexMeta ix : indexes) {
            if (ix.unique() && !ix.columns().isEmpty() && upper.containsAll(upper(ix.columns()))) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code column} is the leading column of some index. */
    public boolean isIndexedLeading(String column) {
        String c = column.toUpperCase(java.util.Locale.ROOT);
        if (!primaryKey.isEmpty() && primaryKey.get(0).equalsIgnoreCase(c)) {
            return true;
        }
        return indexes.stream().anyMatch(ix -> !ix.columns().isEmpty() && ix.columns().get(0).equalsIgnoreCase(c));
    }

    private static List<String> upper(List<String> l) {
        return l.stream().map(s -> s.toUpperCase(java.util.Locale.ROOT)).toList();
    }
}
