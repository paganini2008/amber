package com.github.amber.dialect.db2;

import com.github.amber.dialect.db2.grammar.Db2Parser;
import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.lineage.VariableRules;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.Set;

/**
 * Column-lineage mapping of Amber's Db2 grammar.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Db2LineageRules {

    /** SQL PL: {@code DECLARE v … DEFAULT}, {@code SET v = …}, {@code SELECT … INTO}, {@code FETCH c INTO}. */
    private static final VariableRules VARIABLES = new VariableRules(Set.of("parameter_declaration"),
            Set.of("declaration"), Set.of("set_statement"), Set.of("into_clause"), Set.of("fetch_statement"), true, false,
            VariableRules.AtNames.NONE, Set.of("return_statement"), Set.of());
    static final LineageRules INSTANCE = new LineageRules(
            Set.of("subselect"),
            Set.of("query", "select_statement"),
            "select_item",
            Set.of("alias_name"),
            Set.of("qualified_name"),
            "from_clause",
            Set.of("where_clause", "having_clause", "update_where_clause"),
            Set.of("join_part"),
            Set.of("group_by_clause"),
            "common_table_expression",
            "identifier",
            "identifier",
            "column_name_list",
            "identifier",
            Set.of("assignment"),
            "column_definition",
            "expression_list",
            "merge_action",
            Set.of("USER", "NULL"),
            new LineageRules.Hooks() {
                /** {@code qualified_name} is a column only as a primary expression. */
                @Override
                public boolean isColumnRef(ParserRuleContext node, String text) {
                    return node.getParent() instanceof Db2Parser.Column_referenceContext;
                }

                @Override
                public VariableRules variables() {
                    return VARIABLES;
                }

                /** {@code expr AS identifier}: the identifier after AS. */
                @Override
                public ParserRuleContext selectAlias(ParserRuleContext item) {
                    for (int i = 0; i + 1 < item.getChildCount(); i++) {
                        if (item.getChild(i) instanceof TerminalNode t && t.getText().equalsIgnoreCase("AS")
                                && item.getChild(i + 1) instanceof ParserRuleContext alias) {
                            return alias;
                        }
                    }
                    return null;
                }
            });

    private Db2LineageRules() {
    }
}
