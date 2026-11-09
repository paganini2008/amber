package com.github.amber.dialect.mysql;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.ir.TargetSpec;
import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.mysql.grammar.MySQLParser;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.Map;

/**
 * Meaning of the grammars-v4 MySQL (Oracle) rules for Amber's IR. {@code CREATE}, {@code DROP} and
 * {@code ALTER} statements are anchored on their outer rule so ranges include the keyword; the
 * kind comes from the child rule.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class MySqlIrRules extends IrRules {

    static final MySqlIrRules INSTANCE = new MySqlIrRules();

    private static final Map<String, StatementKind> CREATE_KINDS = Map.of(
            "createTable", StatementKind.CREATE_TABLE,
            "createView", StatementKind.CREATE_VIEW,
            "createIndex", StatementKind.CREATE_INDEX,
            "createProcedure", StatementKind.CREATE_PROCEDURE,
            "createFunction", StatementKind.CREATE_FUNCTION,
            "createTrigger", StatementKind.CREATE_TRIGGER,
            "createEvent", StatementKind.ROUTINE,
            "createDatabase", StatementKind.CREATE_SCHEMA);

    private MySqlIrRules() {
        super(IrRules.builder(Dialect.MYSQL, MySQLParser.ruleNames)
                .statementParents("query")
                .wrappers("simpleStatement", "compoundStatement", "labeledBlock", "unlabeledBlock", "labeledControl",
                        "unlabeledControl", "storedRoutineBody")
                .kind(StatementKind.SELECT, "selectStatement")
                .kind(StatementKind.INSERT, "insertStatement", "replaceStatement")
                .kind(StatementKind.UPDATE, "updateStatement")
                .kind(StatementKind.DELETE, "deleteStatement")
                .kind(StatementKind.CREATE_OTHER, "createStatement")
                .kind(StatementKind.DROP, "dropStatement")
                .kind(StatementKind.ALTER, "alterStatement")
                .kind(StatementKind.RENAME, "renameTableStatement")
                .kind(StatementKind.TRUNCATE, "truncateTableStatement")
                .kind(StatementKind.CALL, "callStatement")
                .kind(StatementKind.TRANSACTION, "transactionOrLockingStatement")
                .kind(StatementKind.SESSION, "setStatement", "useCommand")
                .kind(StatementKind.BLOCK, "beginEndBlock")
                .kind(StatementKind.IF, "ifStatement")
                .kind(StatementKind.CASE, "caseStatement")
                .kind(StatementKind.LOOP, "loopBlock", "whileDoBlock", "repeatUntilBlock")
                .kind(StatementKind.CURSOR, "cursorDeclaration", "cursorOpen", "cursorClose", "cursorFetch")
                .kind(StatementKind.EXCEPTION_HANDLER, "handlerDeclaration")
                .kind(StatementKind.RETURN, "returnStatement")
                .nameRules("tableRef", "tableName", "tableRefWithWildcard")
                .subqueryRules("subquery")
                .cte("commonTableExpression", "identifier")
                .aliasRules("tableAlias")
                .target(TargetSpec.of("insertStatement", "tableRef", Access.INSERT))
                .target(TargetSpec.of("replaceStatement", "tableRef", Access.INSERT))
                .target(TargetSpec.firstName("updateStatement", "tableReferenceList", Access.UPDATE))
                .target(TargetSpec.of("deleteStatement", "tableRef", Access.DELETE))
                .target(TargetSpec.all("tableAliasRefList", "tableRefWithWildcard", Access.DELETE, ObjectKind.TABLE))
                .target(TargetSpec.of("createTable", "tableName", Access.CREATE))
                .target(TargetSpec.of("createView", "viewName", Access.CREATE, ObjectKind.VIEW))
                .target(TargetSpec.of("createIndex", "indexName", Access.CREATE, ObjectKind.INDEX))
                .target(TargetSpec.of("createIndexTarget", "tableRef", Access.ALTER))
                .target(TargetSpec.of("alterTable", "tableRef", Access.ALTER))
                .target(TargetSpec.of("dropTable", "tableRefList", Access.DROP))
                .target(TargetSpec.all("viewRefList", "viewRef", Access.DROP, ObjectKind.VIEW))
                .target(TargetSpec.of("dropProcedure", "procedureRef", Access.DROP, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("dropFunction", "functionRef", Access.DROP, ObjectKind.FUNCTION))
                .target(TargetSpec.of("truncateTableStatement", "tableRef", Access.TRUNCATE))
                .target(TargetSpec.of("createProcedure", "procedureName", Access.CREATE, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("createFunction", "functionName", Access.CREATE, ObjectKind.FUNCTION))
                .target(TargetSpec.of("createTrigger", "triggerName", Access.CREATE, ObjectKind.TRIGGER))
                .target(TargetSpec.of("callStatement", "procedureRef", Access.EXECUTE, ObjectKind.ROUTINE)));
    }

    MySqlIrRules(IrRules.Builder builder) {
        super(builder);
    }

    @Override
    public LineageRules lineage() {
        return MySqlLineageRules.INSTANCE;
    }

    @Override
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        if (kind != StatementKind.CREATE_OTHER) {
            return kind;
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c) {
                StatementKind k = CREATE_KINDS.get(ruleName(c));
                if (k == StatementKind.CREATE_TABLE && hasDescendant(c, "duplicateAsQe")) {
                    return StatementKind.CREATE_TABLE_AS;
                }
                return k == null ? StatementKind.CREATE_OTHER : k;
            }
        }
        return kind;
    }

    @Override
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        if ((ref.access() == Access.CREATE || ref.access() == Access.DROP) && ref.kind() == ObjectKind.TABLE
                && leadingTokensContain(statementNode, 3, "TEMPORARY")) {
            return ObjectKind.TEMP_TABLE;
        }
        return ref.kind();
    }
}
