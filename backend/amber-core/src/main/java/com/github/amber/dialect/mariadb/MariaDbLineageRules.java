package com.github.amber.dialect.mariadb;

import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.lineage.VariableRules;
import com.github.amber.dialect.mariadb.grammar.MariaDBParser;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.List;
import java.util.Set;

/**
 * Column-lineage mapping of the grammars-v4 MariaDB rules. The WHERE expression is a labelled field
 * of {@code fromClause}, and select aliases are a trailing generic {@code uid}, so both come from hooks.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class MariaDbLineageRules {

    /** Stored-program locals and parameters; {@code @name} user variables live for the session. */
    private static final VariableRules VARIABLES = new VariableRules(Set.of("procedureParameter", "functionParameter"),
            Set.of("declareVariable"), Set.of("setStatement"), Set.of("selectIntoExpression"), Set.of("cursorStatement"),
            true, false, VariableRules.AtNames.SESSION, Set.of("returnStatement"), Set.of());
    static final LineageRules INSTANCE = new LineageRules(
            Set.of("querySpecification", "querySpecificationNointo"),
            Set.of("selectStatement", "withStatement"),
            "selectElement",
            Set.of(),
            Set.of("fullColumnName"),
            "tableSources",
            Set.of("havingClause"),
            Set.of("joinPart"),
            Set.of("groupByItem"),
            "commonTableExpressions",
            "cteName",
            "cteColumnName",
            "uidList",
            "uid",
            Set.of("updatedElement"),
            "createDefinition",
            "expressionsWithDefaults",
            null,
            Set.of("CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "LOCALTIME", "LOCALTIMESTAMP",
                    "NULL", "TRUE", "FALSE"),
            new LineageRules.Hooks() {
                /** {@code expr [AS] uid}: a trailing uid after the expression is the alias. */
                @Override
                public ParserRuleContext selectAlias(ParserRuleContext item) {
                    int n = item.getChildCount();
                    return n > 1 && item.getChild(n - 1) instanceof MariaDBParser.UidContext uid ? uid : null;
                }

                @Override
                public VariableRules variables() {
                    return VARIABLES;
                }

                @Override
                public List<ParserRuleContext> filterNodes(ParserRuleContext block) {
                    for (int i = 0; i < block.getChildCount(); i++) {
                        if (block.getChild(i) instanceof MariaDBParser.FromClauseContext from && from.whereExpr != null) {
                            return List.of(from.whereExpr);
                        }
                    }
                    return List.of();
                }
            });

    private MariaDbLineageRules() {
    }
}
