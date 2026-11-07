package com.github.amber.dialect.sqlserver;

import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.lineage.VariableRules;

import java.util.Set;

/**
 * Column-lineage mapping of the grammars-v4 T-SQL rules.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SqlServerLineageRules {

    /**
     * T-SQL: {@code @name} is always a variable of the procedure or batch ({@code DECLARE} inside
     * {@code BEGIN … END} does not open a scope); {@code SELECT @v = …} items are handled by the extractor.
     */
    private static final VariableRules VARIABLES = new VariableRules(Set.of("procedure_param"), Set.of("declare_local"),
            Set.of("set_statement"), Set.of(), Set.of("fetch_cursor"), false, false, VariableRules.AtNames.ROUTINE);
    static final LineageRules INSTANCE = new LineageRules(
            Set.of("query_specification"),
            Set.of("select_statement", "select_statement_standalone", "subquery", "derived_table"),
            "select_list_elem",
            Set.of("column_alias"),
            Set.of("full_column_name"),
            "table_sources",
            Set.of("search_condition"),
            Set.of("join_on"),
            Set.of("group_by_item"),
            "common_table_expression",
            "id_",
            "id_",
            "insert_column_name_list",
            "id_",
            Set.of("update_elem", "update_elem_merge"),
            "column_definition",
            "expression_list_",
            "merge_not_matched",
            Set.of("CURRENT_TIMESTAMP", "CURRENT_USER", "SESSION_USER", "SYSTEM_USER", "USER", "NULL"),
            new LineageRules.Hooks() {
                @Override
                public VariableRules variables() {
                    return VARIABLES;
                }
            });

    private SqlServerLineageRules() {
    }
}
