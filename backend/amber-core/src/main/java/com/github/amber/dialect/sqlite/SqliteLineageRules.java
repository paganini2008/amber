package com.github.amber.dialect.sqlite;

import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.sqlite.grammar.SQLiteLexer;
import com.github.amber.dialect.sqlite.grammar.SQLiteParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Column-lineage mapping of the grammars-v4 SQLite rules. Column references are an alternative of
 * {@code expr_base}, and WHERE / HAVING / GROUP BY are labelled fields of {@code select_core}.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SqliteLineageRules {

    private static final Pattern COLUMN = Pattern.compile("[\\p{L}_\"`\\[][\\p{L}\\p{N}_$\"`\\[\\] ]*(\\.[\\p{L}\\p{N}_$\"`\\[\\] ]+){0,2}");

    static final LineageRules INSTANCE = new LineageRules(
            Set.of("select_core"),
            Set.of("select_stmt"),
            "result_column",
            Set.of("column_alias"),
            Set.of("expr_base"),
            "join_clause",
            Set.of(),
            Set.of("join_constraint"),
            Set.of(),
            "common_table_expression",
            "cte_table_name",
            "column_name",
            "insert_stmt",
            "column_name",
            Set.of(),
            "column_def",
            null,
            null,
            Set.of("CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "NULL", "TRUE", "FALSE"),
            new LineageRules.Hooks() {
                @Override
                public boolean isExpression(String rule) {
                    return rule.equals("expr");
                }

                /** {@code expr_base} is a column only when it is a (qualified) name, not a literal or call. */
                @Override
                public boolean isColumnRef(ParserRuleContext node, String text) {
                    return node.getChildCount() > 0 && COLUMN.matcher(text).matches() && !text.startsWith("'");
                }

                @Override
                public List<ParserRuleContext> filterNodes(ParserRuleContext block) {
                    List<ParserRuleContext> out = new ArrayList<>();
                    if (block instanceof SQLiteParser.Select_coreContext core) {
                        if (core.where_expr != null) {
                            out.add(core.where_expr);
                        }
                        if (core.having_expr != null) {
                            out.add(core.having_expr);
                        }
                        return out;
                    }
                    ParserRuleContext dml = dml(block);
                    if (dml != null) {
                        for (int i = 1; i < dml.getChildCount(); i++) {
                            if (dml.getChild(i - 1) instanceof TerminalNode t && t.getSymbol().getType() == SQLiteLexer.WHERE_
                                    && dml.getChild(i) instanceof ParserRuleContext e) {
                                out.add(e);
                            }
                        }
                    }
                    return out;
                }

                /** {@code SET a = expr, (b, c) = (…)} of {@code update_stmt}: target, ASSIGN, expr. */
                @Override
                public List<Map.Entry<ParserRuleContext, ParserRuleContext>> assignments(ParserRuleContext statement) {
                    ParserRuleContext dml = dml(statement);
                    List<Map.Entry<ParserRuleContext, ParserRuleContext>> out = new ArrayList<>();
                    if (!(dml instanceof SQLiteParser.Update_stmtContext)) {
                        return out;
                    }
                    for (int i = 2; i < dml.getChildCount(); i++) {
                        if (dml.getChild(i - 1) instanceof TerminalNode t && t.getSymbol().getType() == SQLiteLexer.ASSIGN
                                && dml.getChild(i - 2) instanceof ParserRuleContext target
                                && dml.getChild(i) instanceof ParserRuleContext expr) {
                            out.add(Map.entry(target, expr));
                        }
                    }
                    return out;
                }

                @Override
                public List<ParserRuleContext> groupNodes(ParserRuleContext block) {
                    return block instanceof SQLiteParser.Select_coreContext core
                            ? new ArrayList<>(core.group_by_expr) : List.of();
                }
            });

    /** The UPDATE or DELETE statement at or right below {@code node} (statement nodes may be wrappers). */
    private static ParserRuleContext dml(ParserRuleContext node) {
        for (ParserRuleContext n = node; n != null; ) {
            if (n instanceof SQLiteParser.Update_stmtContext || n instanceof SQLiteParser.Delete_stmtContext) {
                return n;
            }
            ParserRuleContext next = null;
            for (int i = 0; i < n.getChildCount(); i++) {
                if (n.getChild(i) instanceof ParserRuleContext c) {
                    next = c;
                    break;
                }
            }
            if (n.getChildCount() != 1 && !(n instanceof SQLiteParser.Sql_stmtContext)) {
                return null;
            }
            n = next;
        }
        return null;
    }

    private SqliteLineageRules() {
    }
}
