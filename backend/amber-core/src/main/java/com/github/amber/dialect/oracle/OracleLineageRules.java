package com.github.amber.dialect.oracle;

import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.lineage.VariableRules;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.Set;

/**
 * Column-lineage mapping of the grammars-v4 PL/SQL rules.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class OracleLineageRules {

    /** PL/SQL: {@code v := expr}, {@code SELECT … [BULK COLLECT] INTO}, {@code FETCH c INTO}; every block is a scope. */
    private static final VariableRules VARIABLES = new VariableRules(Set.of("parameter"), Set.of("variable_declaration"),
            Set.of("assignment_statement"), Set.of("into_clause"), Set.of("fetch_statement"), true, true,
            VariableRules.AtNames.NONE);

    static final LineageRules INSTANCE = new LineageRules(
            Set.of("query_block"),
            Set.of("select_statement", "select_only_statement", "subquery", "subquery_no_with"),
            "select_list_elements",
            Set.of("column_alias"),
            Set.of("general_element"),
            "from_clause",
            Set.of("where_clause", "having_clause"),
            Set.of("join_on_part", "join_using_part"),
            Set.of("group_by_elements"),
            "subquery_factoring_clause",
            "query_name",
            "column_name",
            "insert_into_clause",
            "column_name",
            Set.of("column_based_update_set_clause", "merge_element"),
            "column_definition",
            "expressions_",
            "merge_insert_clause",
            Set.of("SYSDATE", "SYSTIMESTAMP", "ROWNUM", "ROWID", "LEVEL", "USER", "UID", "CURRENT_DATE",
                    "CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "DBTIMEZONE", "SESSIONTIMEZONE", "NULL", "TRUE", "FALSE",
                    "SQLERRM", "SQLCODE", "NEXTVAL", "CURRVAL"),
            new LineageRules.Hooks() {
                /** {@code general_element} also models calls: {@code nvl(a, b)}, {@code pkg.fn(x)}. */
                @Override
                public boolean isColumnRef(ParserRuleContext node, String text) {
                    return text.indexOf('(') < 0;
                }

                @Override
                public VariableRules variables() {
                    return VARIABLES;
                }
            });

    private OracleLineageRules() {
    }
}
