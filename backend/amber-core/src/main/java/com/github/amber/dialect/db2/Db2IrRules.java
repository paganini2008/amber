package com.github.amber.dialect.db2;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.db2.grammar.Db2Parser;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.ir.TargetSpec;
import com.github.amber.dialect.lineage.LineageRules;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.Locale;
import java.util.Set;

/**
 * Meaning of Amber's Db2 grammar rules for the IR. {@code qualified_name} names tables, columns,
 * routines and sequences, so only table positions are accepted as object names.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class Db2IrRules extends IrRules {

    static final Db2IrRules INSTANCE = new Db2IrRules();

    private static final Set<String> TABLE_POSITIONS = Set.of("table_primary", "insert_statement", "update_statement",
            "delete_statement", "merge_statement", "create_table_statement", "create_global_temporary_table_statement",
            "declare_global_temporary_table_statement", "create_view_statement", "create_index_statement",
            "create_sequence_statement", "create_alias_statement", "create_trigger_statement", "drop_statement",
            "truncate_statement", "alter_table_statement", "refresh_table_statement", "lock_table_statement",
            "call_statement");

    private Db2IrRules() {
        super(IrRules.builder(Dialect.DB2, Db2Parser.ruleNames)
                .statementParents("script")
                .wrappers("statement", "statement_body", "create_statement", "alter_statement", "data_change_statement",
                        "compound_item")
                .kind(StatementKind.SELECT, "select_statement", "values_into_statement")
                .kind(StatementKind.INSERT, "insert_statement")
                .kind(StatementKind.UPDATE, "update_statement")
                .kind(StatementKind.DELETE, "delete_statement")
                .kind(StatementKind.MERGE, "merge_statement")
                .kind(StatementKind.TRUNCATE, "truncate_statement")
                .kind(StatementKind.CALL, "call_statement")
                .kind(StatementKind.CREATE_TABLE, "create_table_statement", "create_global_temporary_table_statement",
                        "declare_global_temporary_table_statement")
                .kind(StatementKind.CREATE_VIEW, "create_view_statement")
                .kind(StatementKind.CREATE_INDEX, "create_index_statement")
                .kind(StatementKind.CREATE_SEQUENCE, "create_sequence_statement")
                .kind(StatementKind.CREATE_SYNONYM, "create_alias_statement")
                .kind(StatementKind.CREATE_SCHEMA, "create_schema_statement")
                .kind(StatementKind.CREATE_PROCEDURE, "create_procedure_statement")
                .kind(StatementKind.CREATE_FUNCTION, "create_function_statement")
                .kind(StatementKind.CREATE_TRIGGER, "create_trigger_statement")
                .kind(StatementKind.DROP, "drop_statement")
                .kind(StatementKind.ALTER, "alter_table_statement")
                .kind(StatementKind.BLOCK, "compound_statement")
                .kind(StatementKind.IF, "if_statement")
                .kind(StatementKind.CASE, "case_statement")
                .kind(StatementKind.LOOP, "for_statement", "while_statement", "loop_statement", "repeat_statement")
                .kind(StatementKind.DECLARE, "declaration")
                .kind(StatementKind.CURSOR, "open_statement", "fetch_statement", "close_statement")
                .kind(StatementKind.DYNAMIC_SQL, "execute_immediate_statement", "prepare_statement", "execute_statement")
                .kind(StatementKind.TRANSACTION, "commit_statement", "rollback_statement", "savepoint_statement",
                        "release_savepoint_statement")
                .kind(StatementKind.SESSION, "set_statement")
                .kind(StatementKind.RETURN, "return_statement")
                .kind(StatementKind.GRANT, "grant_statement")
                .kind(StatementKind.REVOKE, "revoke_statement")
                .kind(StatementKind.LOCK, "lock_table_statement")
                .kind(StatementKind.COMMENT, "comment_statement", "label_on_statement")
                .nameRules("qualified_name")
                .subqueryRules("query")
                .cte("common_table_expression", "identifier")
                .aliasRules("correlation_clause")
                .target(TargetSpec.of("insert_statement", "qualified_name", Access.INSERT))
                .target(TargetSpec.of("update_statement", "qualified_name", Access.UPDATE))
                .target(TargetSpec.of("delete_statement", "qualified_name", Access.DELETE))
                .target(TargetSpec.of("merge_statement", "qualified_name", Access.MERGE))
                .target(TargetSpec.of("create_table_statement", "qualified_name", Access.CREATE))
                .target(TargetSpec.of("create_global_temporary_table_statement", "qualified_name", Access.CREATE,
                        ObjectKind.TEMP_TABLE))
                .target(TargetSpec.of("declare_global_temporary_table_statement", "qualified_name", Access.CREATE,
                        ObjectKind.TEMP_TABLE))
                .target(TargetSpec.of("create_view_statement", "qualified_name", Access.CREATE, ObjectKind.VIEW))
                .target(TargetSpec.all("create_index_statement", "qualified_name", Access.ALTER, ObjectKind.TABLE))
                .target(TargetSpec.of("create_sequence_statement", "qualified_name", Access.CREATE, ObjectKind.SEQUENCE))
                .target(TargetSpec.of("create_alias_statement", "qualified_name", Access.CREATE, ObjectKind.SYNONYM))
                .target(TargetSpec.of("create_trigger_statement", "qualified_name", Access.CREATE, ObjectKind.TRIGGER))
                .target(TargetSpec.of("procedure_definition", "qualified_name", Access.CREATE, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("function_definition", "qualified_name", Access.CREATE, ObjectKind.FUNCTION))
                .target(TargetSpec.of("drop_statement", "qualified_name", Access.DROP))
                .target(TargetSpec.of("truncate_statement", "qualified_name", Access.TRUNCATE))
                .target(TargetSpec.of("alter_table_statement", "qualified_name", Access.ALTER))
                .target(TargetSpec.of("call_statement", "qualified_name", Access.EXECUTE, ObjectKind.ROUTINE)));
    }

    @Override
    public boolean acceptName(ParserRuleContext node) {
        ParserRuleContext parent = node.getParent();
        if (parent == null || !TABLE_POSITIONS.contains(ruleName(parent))) {
            return false;
        }
        String rule = ruleName(parent);
        if (rule.equals("create_index_statement") || rule.equals("create_trigger_statement")) {
            int idx = parent.children.indexOf(node);
            boolean afterOn = idx > 0 && parent.getChild(idx - 1) instanceof TerminalNode on
                    && on.getText().equalsIgnoreCase("ON");
            return rule.equals("create_index_statement") ? afterOn : true;
        }
        return true;
    }

    @Override
    public LineageRules lineage() {
        return Db2LineageRules.INSTANCE;
    }

    @Override
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        String text = ctx.getText().toUpperCase(Locale.ROOT);
        if (kind == StatementKind.CREATE_TABLE && ruleName(ctx).equals("create_table_statement")
                && hasDescendant(ctx, "query")) {
            return StatementKind.CREATE_TABLE_AS;
        }
        if (kind == StatementKind.DECLARE) {
            if (text.contains("HANDLERFOR")) {
                return StatementKind.EXCEPTION_HANDLER;
            }
            if (text.contains("CURSOR")) {
                return StatementKind.CURSOR;
            }
        }
        return kind;
    }

    @Override
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        if (ref.access() == Access.DROP && ruleName(statementNode).equals("drop_statement")
                && statementNode.getChildCount() > 1) {
            String object = statementNode.getChild(1).getText().toUpperCase(Locale.ROOT);
            return switch (object) {
                case "TABLE" -> ObjectKind.TABLE;
                case "VIEW" -> ObjectKind.VIEW;
                case "INDEX" -> ObjectKind.INDEX;
                case "SEQUENCE" -> ObjectKind.SEQUENCE;
                case "TRIGGER" -> ObjectKind.TRIGGER;
                case "PROCEDURE" -> ObjectKind.PROCEDURE;
                case "FUNCTION" -> ObjectKind.FUNCTION;
                case "ALIAS" -> ObjectKind.SYNONYM;
                default -> ObjectKind.OTHER;
            };
        }
        return ref.kind();
    }
}
