package com.github.amber.dialect.mysql;

import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.lineage.VariableRules;

import java.util.Set;

/**
 * Column-lineage mapping of the grammars-v4 MySQL (Oracle) rules.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class MySqlLineageRules {

    /** Stored-program locals and parameters; {@code @name} user variables live for the session. */
    private static final VariableRules VARIABLES = new VariableRules(Set.of("functionParameter"),
            Set.of("variableDeclaration"), Set.of("setStatement"), Set.of("intoClause"), Set.of("cursorFetch"), true, false,
            VariableRules.AtNames.SESSION, Set.of("returnStatement"), Set.of());
    static final LineageRules INSTANCE = new LineageRules(
            Set.of("querySpecification"),
            Set.of("queryExpression", "queryExpressionParens", "subquery", "selectStatement"),
            "selectItem",
            Set.of("selectAlias"),
            Set.of("columnRef"),
            "fromClause",
            Set.of("whereClause", "havingClause"),
            Set.of("joinedTable"),
            Set.of("groupByClause"),
            "commonTableExpression",
            "identifier",
            "identifier",
            "fields",
            "insertIdentifier",
            Set.of("updateElement"),
            "columnDefinition",
            "values",
            null,
            Set.of("CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "LOCALTIME", "LOCALTIMESTAMP",
                    "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP", "NULL", "TRUE", "FALSE"),
            new LineageRules.Hooks() {
                @Override
                public VariableRules variables() {
                    return VARIABLES;
                }
            });

    private MySqlLineageRules() {
    }
}
