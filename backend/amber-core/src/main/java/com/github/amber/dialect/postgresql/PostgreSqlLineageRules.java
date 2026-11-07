package com.github.amber.dialect.postgresql;

import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.lineage.VariableRules;

import java.util.Set;

/**
 * Column-lineage mapping of the grammars-v4 PostgreSQL rules.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class PostgreSqlLineageRules {

    static final LineageRules INSTANCE = new LineageRules(
            Set.of("simple_select_pramary"),
            Set.of("select_with_parens", "select_no_parens", "selectstmt"),
            "target_el",
            Set.of("colLabel", "bareColLabel"),
            Set.of("columnref"),
            "from_clause",
            Set.of("where_clause", "having_clause", "where_or_current_clause"),
            Set.of("join_qual"),
            Set.of("group_by_item"),
            "common_table_expr",
            "name",
            "name",
            "insert_column_list",
            "colid",
            Set.of("set_clause"),
            "columnDef",
            "expr_list",
            "merge_insert_clause",
            Set.of("CURRENT_DATE", "CURRENT_TIMESTAMP", "CURRENT_TIME", "LOCALTIME", "LOCALTIMESTAMP", "CURRENT_USER",
                    "SESSION_USER", "USER", "CURRENT_SCHEMA", "CURRENT_CATALOG", "NULL", "TRUE", "FALSE"),
            new LineageRules.Hooks() {
                /** PL/pgSQL bodies are parsed statement by statement; their variables come from {@code PlPgSqlParser}. */
                @Override
                public VariableRules variables() {
                    return VariableRules.NONE;
                }
            });

    private PostgreSqlLineageRules() {
    }
}
