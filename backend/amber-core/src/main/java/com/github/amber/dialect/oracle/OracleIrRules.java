package com.github.amber.dialect.oracle;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.ir.TargetSpec;
import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.oracle.grammar.PlSqlParser;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.Locale;
import java.util.Set;

/**
 * Meaning of the grammars-v4 PL/SQL rules for Amber's IR.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class OracleIrRules extends IrRules {

    static final OracleIrRules INSTANCE = new OracleIrRules();

    private OracleIrRules() {
        super(IrRules.builder(Dialect.ORACLE, PlSqlParser.ruleNames)
                .statementParents("sql_unit", "plsql_unit")
                .wrappers("data_manipulation_language_statements", "sql_statement")
                // queries and DML
                .kind(StatementKind.SELECT, "select_statement")
                .kind(StatementKind.INSERT, "insert_statement")
                .kind(StatementKind.UPDATE, "update_statement")
                .kind(StatementKind.DELETE, "delete_statement")
                .kind(StatementKind.MERGE, "merge_statement")
                .kind(StatementKind.LOCK, "lock_table_statement")
                .kind(StatementKind.OTHER, "explain_statement")
                // DDL
                .kind(StatementKind.CREATE_TABLE, "create_table")
                .kind(StatementKind.CREATE_VIEW, "create_view")
                .kind(StatementKind.CREATE_MATERIALIZED_VIEW, "create_materialized_view")
                .kind(StatementKind.CREATE_INDEX, "create_index")
                .kind(StatementKind.CREATE_SEQUENCE, "create_sequence")
                .kind(StatementKind.CREATE_SYNONYM, "create_synonym")
                .kind(StatementKind.CREATE_TYPE, "create_type")
                .kind(StatementKind.TRUNCATE, "truncate_table", "truncate_cluster")
                .kind(StatementKind.RENAME, "rename_object")
                .kind(StatementKind.COMMENT, "comment_on_table", "comment_on_column", "comment_on_materialized")
                .kind(StatementKind.GRANT, "grant_statement")
                .kind(StatementKind.REVOKE, "revoke_statement")
                .kind(StatementKind.TRANSACTION, "transaction_control_statements")
                .kind(StatementKind.SESSION, "alter_session")
                .kind(StatementKind.SESSION, "set_role_command")
                .kind(StatementKind.CLIENT_COMMAND, "sql_plus_command")
                // programs
                .kind(StatementKind.BLOCK, "anonymous_block")
                .kind(StatementKind.CREATE_PROCEDURE, "create_procedure_body")
                .kind(StatementKind.CREATE_FUNCTION, "create_function_body")
                .kind(StatementKind.CREATE_PACKAGE, "create_package")
                .kind(StatementKind.CREATE_PACKAGE_BODY, "create_package_body")
                .kind(StatementKind.CREATE_TRIGGER, "create_trigger")
                .kind(StatementKind.CREATE_TYPE_BODY, "create_type_body")
                .kind(StatementKind.ROUTINE, "procedure_body", "function_body")
                .kind(StatementKind.BLOCK, "body", "block")
                .onlyUnder("body", "statement")
                .onlyUnder("block", "statement")
                // control flow and procedural statements
                .kind(StatementKind.IF, "if_statement")
                .kind(StatementKind.LOOP, "loop_statement", "forall_statement")
                .kind(StatementKind.CASE, "case_statement")
                .kind(StatementKind.EXCEPTION_HANDLER, "exception_handler")
                .kind(StatementKind.CURSOR, "cursor_declaration", "open_for_statement")
                .kind(StatementKind.DYNAMIC_SQL, "execute_immediate")
                .kind(StatementKind.CALL, "call_statement")
                // names
                .nameRules("tableview_name")
                .subqueryRules("select_statement", "select_only_statement", "subquery", "subquery_no_with")
                .cte("subquery_factoring_clause", "query_name")
                .aliasRules("table_alias")
                // what statements act on
                .target(TargetSpec.of("insert_into_clause", "general_table_ref", Access.INSERT))
                .target(TargetSpec.of("update_statement", "general_table_ref", Access.UPDATE))
                .target(TargetSpec.of("delete_statement", "general_table_ref", Access.DELETE))
                .target(TargetSpec.of("merge_statement", "selected_tableview", Access.MERGE))
                .target(TargetSpec.of("create_table", "table_name", Access.CREATE))
                .target(TargetSpec.of("create_view", "id_expression", Access.CREATE, ObjectKind.VIEW))
                .target(TargetSpec.of("create_materialized_view", "tableview_name", Access.CREATE, ObjectKind.MATERIALIZED_VIEW))
                .target(TargetSpec.of("create_index", "index_name", Access.CREATE, ObjectKind.INDEX))
                .target(TargetSpec.of("table_index_clause", "tableview_name", Access.ALTER))
                .target(TargetSpec.of("create_sequence", "sequence_name", Access.CREATE, ObjectKind.SEQUENCE))
                .target(TargetSpec.of("create_synonym", "synonym_name", Access.CREATE, ObjectKind.SYNONYM))
                .target(TargetSpec.of("alter_table", "tableview_name", Access.ALTER))
                .target(TargetSpec.of("drop_table", "tableview_name", Access.DROP))
                .target(TargetSpec.of("drop_view", "tableview_name", Access.DROP, ObjectKind.VIEW))
                .target(TargetSpec.of("drop_materialized_view", "tableview_name", Access.DROP, ObjectKind.MATERIALIZED_VIEW))
                .target(TargetSpec.of("drop_sequence", "sequence_name", Access.DROP, ObjectKind.SEQUENCE))
                .target(TargetSpec.of("drop_index", "index_name", Access.DROP, ObjectKind.INDEX))
                .target(TargetSpec.of("drop_procedure", "procedure_name", Access.DROP, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("drop_function", "function_name", Access.DROP, ObjectKind.FUNCTION))
                .target(TargetSpec.of("drop_package", "package_name", Access.DROP, ObjectKind.PACKAGE))
                .target(TargetSpec.of("drop_trigger", "trigger_name", Access.DROP, ObjectKind.TRIGGER))
                .target(TargetSpec.of("truncate_table", "tableview_name", Access.TRUNCATE))
                .target(TargetSpec.of("create_procedure_body", "procedure_name", Access.CREATE, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("create_function_body", "function_name", Access.CREATE, ObjectKind.FUNCTION))
                .target(TargetSpec.of("create_package", "package_name", Access.CREATE, ObjectKind.PACKAGE))
                .target(TargetSpec.of("create_package_body", "package_name", Access.CREATE, ObjectKind.PACKAGE))
                .target(TargetSpec.of("create_trigger", "trigger_name", Access.CREATE, ObjectKind.TRIGGER))
                .target(TargetSpec.of("create_type_body", "type_name", Access.CREATE, ObjectKind.TYPE))
                .target(TargetSpec.of("procedure_body", "identifier", Access.CREATE, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("function_body", "identifier", Access.CREATE, ObjectKind.FUNCTION))
                .target(TargetSpec.of("call_statement", "routine_name", Access.EXECUTE, ObjectKind.ROUTINE)));
    }

    private static final Set<String> TRANSACTION_WORDS = Set.of("COMMIT", "ROLLBACK", "SAVEPOINT");

    @Override
    public LineageRules lineage() {
        return OracleLineageRules.INSTANCE;
    }

    @Override
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        // The grammar's optional CALL keyword lets a bare COMMIT or ROLLBACK parse as a routine call.
        if (kind == StatementKind.CALL && TRANSACTION_WORDS.contains(ctx.getStart().getText().toUpperCase(Locale.ROOT))
                && ctx.getChildCount() <= 2) {
            return StatementKind.TRANSACTION;
        }
        if (kind == StatementKind.CREATE_TABLE && hasDescendant(ctx, "select_only_statement")) {
            return StatementKind.CREATE_TABLE_AS;
        }
        return kind;
    }

    @Override
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        if (ref.access() == Access.CREATE && ref.kind() == ObjectKind.TABLE
                && leadingTokensContain(statementNode, 4, "TEMPORARY")) {
            return ObjectKind.TEMP_TABLE;              // CREATE GLOBAL|PRIVATE TEMPORARY TABLE
        }
        return ref.kind();
    }
}
