package com.github.amber.dialect.sqlserver;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.ir.TargetSpec;
import com.github.amber.dialect.sqlserver.grammar.TSqlParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.Locale;
import java.util.Set;

/**
 * Meaning of the grammars-v4 T-SQL rules for Amber's IR. Covers batches, procedures, functions,
 * triggers, TRY/CATCH, WHILE, cursors, {@code #temp} tables, {@code @table} variables,
 * {@code SELECT … INTO}, {@code UPDATE alias … FROM} and dynamic SQL via {@code EXEC(@sql)} or
 * {@code sp_executesql}.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SqlServerIrRules extends IrRules {

    static final SqlServerIrRules INSTANCE = new SqlServerIrRules();

    /** Parents under which an {@code @name} token is a table variable. */
    private static final Set<String> TABLE_VARIABLE_PARENTS = Set.of("table_source_item", "declare_statement",
            "delete_statement_from");

    private SqlServerIrRules() {
        super(IrRules.builder(Dialect.SQLSERVER, TSqlParser.ruleNames)
                .statementParents("batch")
                .wrappers("sql_clauses", "dml_clause", "ddl_clause", "cfl_statement", "another_statement",
                        "batch_level_statement", "create_or_alter_trigger")
                // queries and DML
                .kind(StatementKind.SELECT, "select_statement_standalone")
                .kind(StatementKind.INSERT, "insert_statement")
                .kind(StatementKind.UPDATE, "update_statement")
                .kind(StatementKind.DELETE, "delete_statement")
                .kind(StatementKind.MERGE, "merge_statement")
                // DDL
                .kind(StatementKind.CREATE_TABLE, "create_table")
                .kind(StatementKind.CREATE_VIEW, "create_view")
                .kind(StatementKind.CREATE_INDEX, "create_index", "create_xml_index", "create_columnstore_index",
                        "create_nonclustered_columnstore_index")
                .kind(StatementKind.CREATE_SEQUENCE, "create_sequence")
                .kind(StatementKind.CREATE_SYNONYM, "create_synonym")
                .kind(StatementKind.CREATE_SCHEMA, "create_schema")
                .kind(StatementKind.CREATE_TYPE, "create_type")
                .kind(StatementKind.ALTER, "alter_table")
                .kind(StatementKind.TRUNCATE, "truncate_table")
                // programs and control flow
                .kind(StatementKind.CREATE_PROCEDURE, "create_or_alter_procedure")
                .kind(StatementKind.CREATE_FUNCTION, "create_or_alter_function")
                .kind(StatementKind.CREATE_TRIGGER, "create_or_alter_dml_trigger", "create_or_alter_ddl_trigger")
                .kind(StatementKind.BLOCK, "block_statement")
                .kind(StatementKind.IF, "if_statement")
                .kind(StatementKind.LOOP, "while_statement")
                .kind(StatementKind.TRY, "try_catch_statement")
                // procedural
                .kind(StatementKind.CURSOR, "cursor_statement")
                .kind(StatementKind.DECLARE, "declare_statement")
                .kind(StatementKind.ASSIGNMENT, "set_statement")
                .kind(StatementKind.CALL, "execute_statement", "execute_body_batch")
                .kind(StatementKind.RETURN, "return_statement")
                .kind(StatementKind.TRANSACTION, "transaction_statement")
                .kind(StatementKind.SESSION, "use_statement")
                // names
                .nameRules("full_table_name", "table_name", "ddl_object")
                .subqueryRules("select_statement", "subquery", "derived_table")
                .opaqueRules("full_column_name")
                .cte("common_table_expression", "id_")
                .aliasRules("table_alias")
                // what statements act on
                .target(TargetSpec.of("insert_statement", "ddl_object", Access.INSERT))
                .target(TargetSpec.of("update_statement", "ddl_object", Access.UPDATE))
                .target(TargetSpec.of("delete_statement", "delete_statement_from", Access.DELETE))
                .target(TargetSpec.of("merge_statement", "ddl_object", Access.MERGE))
                .target(TargetSpec.of("query_specification", "table_name", Access.CREATE))
                .target(TargetSpec.of("create_table", "table_name", Access.CREATE))
                .target(TargetSpec.of("create_view", "simple_name", Access.CREATE, ObjectKind.VIEW))
                .target(TargetSpec.of("create_index", "id_", Access.CREATE, ObjectKind.INDEX))
                .target(TargetSpec.of("create_index", "table_name", Access.ALTER))
                .target(TargetSpec.of("alter_table", "table_name", Access.ALTER))
                .target(TargetSpec.of("truncate_table", "table_name", Access.TRUNCATE))
                .target(TargetSpec.all("drop_table", "table_name", Access.DROP, ObjectKind.TABLE))
                .target(TargetSpec.all("drop_view", "simple_name", Access.DROP, ObjectKind.VIEW))
                .target(TargetSpec.all("drop_procedure", "func_proc_name_schema", Access.DROP, ObjectKind.PROCEDURE))
                .target(TargetSpec.all("drop_function", "func_proc_name_schema", Access.DROP, ObjectKind.FUNCTION))
                .target(TargetSpec.of("create_sequence", "simple_name", Access.CREATE, ObjectKind.SEQUENCE))
                .target(TargetSpec.of("create_or_alter_procedure", "func_proc_name_schema", Access.CREATE, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("create_or_alter_function", "func_proc_name_schema", Access.CREATE, ObjectKind.FUNCTION))
                .target(TargetSpec.of("create_or_alter_dml_trigger", "simple_name", Access.CREATE, ObjectKind.TRIGGER))
                .target(TargetSpec.of("execute_body", "func_proc_name_server_database_schema", Access.EXECUTE, ObjectKind.ROUTINE))
                .target(TargetSpec.of("execute_body_batch", "func_proc_name_server_database_schema", Access.EXECUTE, ObjectKind.ROUTINE)));
    }

    @Override
    public com.github.amber.dialect.lineage.LineageRules lineage() {
        return SqlServerLineageRules.INSTANCE;
    }

    @Override
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        if (kind == StatementKind.SELECT && selectsInto(ctx)) {
            return StatementKind.SELECT_INTO;
        }
        if (kind == StatementKind.CALL && isDynamic(ctx)) {
            return StatementKind.DYNAMIC_SQL;
        }
        return kind;
    }

    @Override
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        if (ref.kind() != ObjectKind.TABLE) {
            return ref.kind();
        }
        String simple = ref.name().simpleName();
        if (simple.startsWith("#")) {
            return ObjectKind.TEMP_TABLE;
        }
        if (simple.startsWith("@")) {
            return ObjectKind.TABLE_VARIABLE;
        }
        return ObjectKind.TABLE;
    }

    /** {@code @t} in {@code FROM @t} reads a table variable; in {@code DECLARE @t TABLE (…)} it creates one. */
    @Override
    public Access nameToken(TerminalNode token) {
        String text = token.getText();
        if (!text.startsWith("@") || text.startsWith("@@")
                || !(token.getParent() instanceof ParserRuleContext parent)) {
            return null;
        }
        String rule = ruleName(parent);
        if (!TABLE_VARIABLE_PARENTS.contains(rule)) {
            return null;
        }
        if (rule.equals("declare_statement")) {
            return hasChild(parent, "table_type_definition") ? Access.CREATE : null;
        }
        return Access.READ;
    }

    private boolean selectsInto(ParserRuleContext ctx) {
        if (ruleName(ctx).equals("query_specification")) {
            for (int i = 0; i < ctx.getChildCount(); i++) {
                if (ctx.getChild(i) instanceof TerminalNode t && t.getText().equalsIgnoreCase("INTO")) {
                    return true;
                }
            }
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child instanceof ParserRuleContext c && !ruleName(c).equals("subquery") && selectsInto(c)) {
                return true;
            }
        }
        return false;
    }

    /** {@code EXEC (@sql)}, {@code EXEC @sql} or {@code EXEC sp_executesql …}. */
    private boolean isDynamic(ParserRuleContext ctx) {
        if (hasDescendant(ctx, "execute_var_string")) {
            return true;
        }
        String text = ctx.getText().toLowerCase(Locale.ROOT);
        return text.contains("sp_executesql");
    }

    private boolean hasChild(ParserRuleContext ctx, String rule) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c && ruleName(c).equals(rule)) {
                return true;
            }
        }
        return false;
    }
}
