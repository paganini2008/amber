package com.github.amber.dialect.sqlite;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.ir.TargetSpec;
import com.github.amber.dialect.sqlite.grammar.SQLiteParser;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.Locale;

/**
 * Meaning of the grammars-v4 SQLite rules for Amber's IR.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SqliteIrRules extends IrRules {

    static final SqliteIrRules INSTANCE = new SqliteIrRules();

    private SqliteIrRules() {
        super(IrRules.builder(Dialect.SQLITE, SQLiteParser.ruleNames)
                .statementParents("sql_stmt_list")
                .wrappers("sql_stmt")
                .kind(StatementKind.SELECT, "select_stmt")
                .kind(StatementKind.INSERT, "insert_stmt")
                .kind(StatementKind.UPDATE, "update_stmt")
                .kind(StatementKind.DELETE, "delete_stmt")
                .kind(StatementKind.CREATE_TABLE, "create_table_stmt", "create_virtual_table_stmt")
                .kind(StatementKind.CREATE_VIEW, "create_view_stmt")
                .kind(StatementKind.CREATE_INDEX, "create_index_stmt")
                .kind(StatementKind.CREATE_TRIGGER, "create_trigger_stmt")
                .kind(StatementKind.DROP, "drop_stmt")
                .kind(StatementKind.ALTER, "alter_table_stmt")
                .kind(StatementKind.TRANSACTION, "begin_stmt", "commit_stmt", "rollback_stmt", "savepoint_stmt",
                        "release_stmt")
                .nameRules("table_name")
                .subqueryRules("select_stmt")
                .opaqueRules("cte_table_name")
                .cte("common_table_expression", "cte_table_name")
                .aliasRules("table_alias", "alias", "table_alias_excluding_joins")
                .target(TargetSpec.of("insert_stmt", "table_name", Access.INSERT))
                .target(TargetSpec.of("update_stmt", "qualified_table_name", Access.UPDATE))
                .target(TargetSpec.of("delete_stmt", "qualified_table_name", Access.DELETE))
                .target(TargetSpec.of("create_table_stmt", "table_name", Access.CREATE))
                .target(TargetSpec.of("create_virtual_table_stmt", "table_name", Access.CREATE))
                .target(TargetSpec.of("create_view_stmt", "view_name", Access.CREATE, ObjectKind.VIEW))
                .target(TargetSpec.of("create_index_stmt", "index_name", Access.CREATE, ObjectKind.INDEX))
                .target(TargetSpec.of("create_index_stmt", "table_name", Access.ALTER))
                .target(TargetSpec.of("create_trigger_stmt", "trigger_name", Access.CREATE, ObjectKind.TRIGGER))
                .target(TargetSpec.of("drop_stmt", "any_name", Access.DROP))
                .target(TargetSpec.of("alter_table_stmt", "table_name", Access.ALTER)));
    }

    /**
     * {@code table_name} under {@code expr_base} qualifies a column ({@code t.col}) and under
     * {@code result_column} a star ({@code t.*}); neither is a table reference.
     */
    @Override
    public boolean acceptName(ParserRuleContext node) {
        return !parentIs(node, "expr_base") && !parentIs(node, "expr") && !parentIs(node, "result_column");
    }

    @Override
    public com.github.amber.dialect.lineage.LineageRules lineage() {
        return SqliteLineageRules.INSTANCE;
    }

    @Override
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        if (kind == StatementKind.CREATE_TABLE && hasDescendant(ctx, "select_stmt")) {
            return StatementKind.CREATE_TABLE_AS;
        }
        return kind;
    }

    @Override
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        if (ref.access() == Access.CREATE && ref.kind() == ObjectKind.TABLE
                && (leadingTokensContain(statementNode, 3, "TEMP") || leadingTokensContain(statementNode, 3, "TEMPORARY"))) {
            return ObjectKind.TEMP_TABLE;
        }
        if (ref.access() == Access.DROP && ruleName(statementNode).equals("drop_stmt")) {
            String object = statementNode.getChild(1).getText().toUpperCase(Locale.ROOT);
            return switch (object) {
                case "VIEW" -> ObjectKind.VIEW;
                case "INDEX" -> ObjectKind.INDEX;
                case "TRIGGER" -> ObjectKind.TRIGGER;
                default -> ObjectKind.TABLE;
            };
        }
        return ref.kind();
    }
}
