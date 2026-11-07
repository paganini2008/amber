package com.github.amber.dialect.h2;

import com.github.amber.dialect.h2.grammar.H2Parser;
import com.github.amber.dialect.lineage.LineageRules;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Column-lineage mapping of Amber's H2 grammar. Column references are the {@code column_reference}
 * alternative of {@code primary_expression}; WHERE / HAVING are labelled fields.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class H2LineageRules {

    static final LineageRules INSTANCE = new LineageRules(
            Set.of("query_specification"),
            Set.of("query", "query_body"),           // query_body: a top-level SELECT statement
            "select_item",
            Set.of("alias_name"),
            Set.of("qualified_name"),
            "table_reference_list",
            Set.of(),
            Set.of("join_specification"),
            Set.of("group_by_list"),
            "common_table_expression",
            "identifier",
            "identifier",
            "column_name_list",
            "identifier",
            Set.of("assignment"),
            "column_definition",
            "row_value",
            "merge_insert",
            Set.of("CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP", "CURRENT_USER",
                    "NULL", "TRUE", "FALSE"),
            new LineageRules.Hooks() {
                @Override
                public boolean isColumnRef(ParserRuleContext node, String text) {
                    return node.getParent() instanceof H2Parser.Column_referenceContext;
                }

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

                @Override
                public List<ParserRuleContext> filterNodes(ParserRuleContext block) {
                    List<ParserRuleContext> out = new ArrayList<>();
                    if (block instanceof H2Parser.Query_specificationContext q) {
                        if (q.where_condition != null) {
                            out.add(q.where_condition);
                        }
                        if (q.having_condition != null) {
                            out.add(q.having_condition);
                        }
                    }
                    return out;
                }
            });

    private H2LineageRules() {
    }
}
