package com.github.amber.core.lineage;

import java.util.Locale;

/**
 * A column of something that holds data: a table, a CTE, a derived table, a query result, or a
 * procedural variable or parameter.
 *
 * <p>Variables and parameters are nodes of their own: {@code owner} is the display name of the scope
 * they belong to (the routine {@code dw.p_load}, an anonymous {@code block@L25}, a T-SQL
 * {@code batch@L1}, or {@code session} for MySQL user variables), {@code column} is the variable name,
 * and {@code ownerKey} ({@code var:<scope key>:<NAME>}) is unique to the variable, so that each variable
 * has its own {@link #ROWS} node for the filters of the query that assigned it.
 *
 * @param ownerKind what the owner is
 * @param owner     display name of the owner ({@code sales.orders}, {@code paid_orders}, {@code result},
 *                  the variable's scope {@code dw.p_load})
 * @param ownerKey  stable key of the owner: the normalized table key, or a key unique to the CTE,
 *                  subquery, result or variable within the script
 * @param column    column name as written (the variable name for variables); {@link #ROWS} is the pseudo
 *                  column that receives indirect edges
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record ColumnNode(OwnerKind ownerKind, String owner, String ownerKey, String column) {

    /** Pseudo column standing for "which rows": the target of filter, join and group edges. */
    public static final String ROWS = "*rows*";

    public enum OwnerKind {
        TABLE, TEMP_TABLE, TABLE_VARIABLE, VIEW, MATERIALIZED_VIEW, CTE, SUBQUERY, RESULT,
        /** A procedural variable (PL/SQL, T-SQL {@code @v}, PL/pgSQL, SQL PL, MySQL locals and user variables). */
        VARIABLE,
        /** A routine parameter ({@code p_day} of {@code procedure p(p_day date)}, T-SQL {@code @from}, PostgreSQL {@code $1}). */
        PARAMETER,
        /** The owner could not be determined (ambiguous unqualified column without metadata). */
        UNKNOWN
    }

    /** Key that identifies this column across the script (column names compared case-insensitively). */
    public String key() {
        return ownerKey + "." + column.toUpperCase(Locale.ROOT);
    }

    public boolean isRows() {
        return ROWS.equals(column);
    }

    @Override
    public String toString() {
        return owner + "." + column;
    }
}
