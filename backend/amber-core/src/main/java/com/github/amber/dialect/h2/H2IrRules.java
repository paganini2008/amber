package com.github.amber.dialect.h2;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.h2.grammar.H2Parser;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.ir.TargetSpec;
import com.github.amber.dialect.lineage.LineageRules;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Meaning of Amber's H2 grammar rules for the IR. {@code qualified_name} names tables, columns,
 * sequences and routines, so only table positions are accepted as object names.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class H2IrRules extends IrRules {

    static final H2IrRules INSTANCE = new H2IrRules();

    private static final Set<String> TABLE_POSITIONS = Set.of("table_primary", "explicit_table", "insert_statement",
            "replace_statement", "update_statement", "delete_statement", "merge_into_statement", "merge_using_statement",
            "create_table_statement", "create_view_statement", "create_materialized_view_statement",
            "create_index_statement", "create_sequence_statement", "create_trigger_statement",
            "create_linked_table_statement", "create_alias_statement", "drop_statement", "truncate_statement",
            "alter_table_statement");

    private static final Map<String, StatementKind> WITH_KINDS = Map.of(
            "query_body", StatementKind.SELECT,
            "insert_statement", StatementKind.INSERT,
            "update_statement", StatementKind.UPDATE,
            "delete_statement", StatementKind.DELETE,
            "merge_into_statement", StatementKind.MERGE,
            "merge_using_statement", StatementKind.MERGE,
            "create_table_statement", StatementKind.CREATE_TABLE_AS);

    private H2IrRules() {
        super(IrRules.builder(Dialect.H2, H2Parser.ruleNames)
                .statementParents("script")
                .wrappers("statement", "create_statement", "alter_statement")
                .kind(StatementKind.SELECT, "query_statement", "with_statement")
                .kind(StatementKind.INSERT, "insert_statement", "replace_statement")
                .kind(StatementKind.UPDATE, "update_statement")
                .kind(StatementKind.DELETE, "delete_statement")
                .kind(StatementKind.MERGE, "merge_into_statement", "merge_using_statement")
                .kind(StatementKind.CALL, "call_statement")
                .kind(StatementKind.DYNAMIC_SQL, "execute_immediate_statement")
                .kind(StatementKind.CREATE_TABLE, "create_table_statement", "create_linked_table_statement")
                .kind(StatementKind.CREATE_VIEW, "create_view_statement")
                .kind(StatementKind.CREATE_MATERIALIZED_VIEW, "create_materialized_view_statement")
                .kind(StatementKind.CREATE_INDEX, "create_index_statement")
                .kind(StatementKind.CREATE_SEQUENCE, "create_sequence_statement")
                .kind(StatementKind.CREATE_SCHEMA, "create_schema_statement")
                .kind(StatementKind.CREATE_FUNCTION, "create_alias_statement")
                .kind(StatementKind.CREATE_TRIGGER, "create_trigger_statement")
                .kind(StatementKind.DROP, "drop_statement")
                .kind(StatementKind.TRUNCATE, "truncate_statement")
                .kind(StatementKind.ALTER, "alter_table_statement")
                .nameRules("qualified_name")
                .subqueryRules("query")
                .cte("common_table_expression", "identifier")
                .aliasRules("table_alias", "table_alias_no_columns")
                .target(TargetSpec.of("insert_statement", "qualified_name", Access.INSERT))
                .target(TargetSpec.of("replace_statement", "qualified_name", Access.INSERT))
                .target(TargetSpec.of("update_statement", "qualified_name", Access.UPDATE))
                .target(TargetSpec.of("delete_statement", "qualified_name", Access.DELETE))
                .target(TargetSpec.of("merge_into_statement", "qualified_name", Access.MERGE))
                .target(TargetSpec.of("merge_using_statement", "qualified_name", Access.MERGE))
                .target(TargetSpec.of("create_table_statement", "qualified_name", Access.CREATE))
                .target(TargetSpec.of("create_linked_table_statement", "qualified_name", Access.CREATE))
                .target(TargetSpec.of("create_view_statement", "qualified_name", Access.CREATE, ObjectKind.VIEW))
                .target(TargetSpec.of("create_materialized_view_statement", "qualified_name", Access.CREATE,
                        ObjectKind.MATERIALIZED_VIEW))
                .target(TargetSpec.all("create_index_statement", "qualified_name", Access.ALTER, ObjectKind.TABLE))
                .target(TargetSpec.of("create_sequence_statement", "qualified_name", Access.CREATE, ObjectKind.SEQUENCE))
                .target(TargetSpec.of("create_trigger_statement", "qualified_name", Access.CREATE, ObjectKind.TRIGGER))
                .target(TargetSpec.of("create_alias_statement", "qualified_name", Access.CREATE, ObjectKind.FUNCTION))
                .target(TargetSpec.all("drop_statement", "qualified_name", Access.DROP, ObjectKind.TABLE))
                .target(TargetSpec.of("truncate_statement", "qualified_name", Access.TRUNCATE))
                .target(TargetSpec.of("alter_table_statement", "qualified_name", Access.ALTER)));
    }

    @Override
    public boolean acceptName(ParserRuleContext node) {
        ParserRuleContext parent = node.getParent();
        if (parent == null || !TABLE_POSITIONS.contains(ruleName(parent))) {
            return false;
        }
        if (ruleName(parent).equals("create_index_statement")) {
            int idx = parent.children.indexOf(node);
            return idx > 0 && parent.getChild(idx - 1) instanceof TerminalNode on && on.getText().equalsIgnoreCase("ON");
        }
        return true;
    }

    @Override
    public LineageRules lineage() {
        return H2LineageRules.INSTANCE;
    }

    @Override
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        String rule = ruleName(ctx);
        if (rule.equals("with_statement")) {
            for (int i = 0; i < ctx.getChildCount(); i++) {
                if (ctx.getChild(i) instanceof ParserRuleContext c && WITH_KINDS.containsKey(ruleName(c))) {
                    return WITH_KINDS.get(ruleName(c));
                }
            }
        }
        if (kind == StatementKind.CREATE_TABLE && rule.equals("create_table_statement") && hasDescendant(ctx, "query")) {
            return StatementKind.CREATE_TABLE_AS;
        }
        return kind;
    }

    @Override
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        if (ref.access() == Access.CREATE && ref.kind() == ObjectKind.TABLE
                && (leadingTokensContain(statementNode, 4, "TEMP") || leadingTokensContain(statementNode, 4, "TEMPORARY"))) {
            return ObjectKind.TEMP_TABLE;
        }
        if (ref.access() == Access.DROP && ruleName(statementNode).equals("drop_statement")) {
            String object = statementNode.getChild(1).getText().toUpperCase(Locale.ROOT);
            return switch (object) {
                case "VIEW" -> ObjectKind.VIEW;
                case "MATERIALIZED" -> ObjectKind.MATERIALIZED_VIEW;
                case "INDEX" -> ObjectKind.INDEX;
                case "SEQUENCE" -> ObjectKind.SEQUENCE;
                case "TRIGGER" -> ObjectKind.TRIGGER;
                case "ALIAS" -> ObjectKind.FUNCTION;
                case "TABLE" -> ObjectKind.TABLE;
                default -> ObjectKind.OTHER;
            };
        }
        return ref.kind();
    }
}
