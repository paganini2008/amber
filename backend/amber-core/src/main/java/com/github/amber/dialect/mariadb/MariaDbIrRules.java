package com.github.amber.dialect.mariadb;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.ir.TargetSpec;
import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.mariadb.grammar.MariaDBParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.Map;

/**
 * Meaning of the grammars-v4 MariaDB rules for Amber's IR.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class MariaDbIrRules extends IrRules {

    static final MariaDbIrRules INSTANCE = new MariaDbIrRules();

    private static final Map<String, StatementKind> DML_KINDS = Map.of(
            "selectStatement", StatementKind.SELECT,
            "insertStatement", StatementKind.INSERT,
            "replaceStatement", StatementKind.INSERT,
            "updateStatement", StatementKind.UPDATE,
            "deleteStatement", StatementKind.DELETE);

    private MariaDbIrRules() {
        super(IrRules.builder(Dialect.MARIADB, MariaDBParser.ruleNames)
                .statementParents("sqlStatements")
                .wrappers("sqlStatement", "ddlStatement", "dmlStatement", "compoundStatement", "procedureSqlStatement",
                        "routineBody", "updateStatement", "deleteStatement", "emptyStatement_")
                .kind(StatementKind.SELECT, "selectStatement", "withStatement")
                .kind(StatementKind.INSERT, "insertStatement", "replaceStatement")
                .kind(StatementKind.UPDATE, "singleUpdateStatement", "multipleUpdateStatement")
                .kind(StatementKind.DELETE, "singleDeleteStatement", "multipleDeleteStatement")
                .kind(StatementKind.CREATE_TABLE, "createTable")
                .kind(StatementKind.CREATE_VIEW, "createView")
                .kind(StatementKind.CREATE_INDEX, "createIndex")
                .kind(StatementKind.CREATE_SEQUENCE, "createSequence")
                .kind(StatementKind.CREATE_PROCEDURE, "createProcedure")
                .kind(StatementKind.CREATE_FUNCTION, "createFunction")
                .kind(StatementKind.CREATE_TRIGGER, "createTrigger")
                .kind(StatementKind.ROUTINE, "createEvent")
                .kind(StatementKind.TRUNCATE, "truncateTable")
                .kind(StatementKind.ALTER, "alterTable")
                .kind(StatementKind.CALL, "callStatement")
                .kind(StatementKind.BLOCK, "blockStatement")
                .kind(StatementKind.IF, "ifStatement")
                .kind(StatementKind.CASE, "caseStatement")
                .kind(StatementKind.LOOP, "loopStatement", "whileStatement", "repeatStatement")
                .kind(StatementKind.CURSOR, "cursorStatement", "declareCursor")
                .kind(StatementKind.EXCEPTION_HANDLER, "declareHandler")
                .kind(StatementKind.TRANSACTION, "transactionStatement")
                .kind(StatementKind.SESSION, "setStatement", "useStatement")
                .kind(StatementKind.RETURN, "returnStatement")
                .nameRules("tableName")
                .subqueryRules("selectStatement")
                .cte("commonTableExpressions", "cteName")
                .aliasRules("uid")
                .aliasDepth(0)
                .target(TargetSpec.of("insertStatement", "tableName", Access.INSERT))
                .target(TargetSpec.of("replaceStatement", "tableName", Access.INSERT))
                .target(TargetSpec.of("singleUpdateStatement", "tableName", Access.UPDATE))
                .target(TargetSpec.firstName("multipleUpdateStatement", "tableSources", Access.UPDATE))
                .target(TargetSpec.of("singleDeleteStatement", "tableName", Access.DELETE))
                .target(TargetSpec.all("multipleDeleteStatement", "tableName", Access.DELETE, ObjectKind.TABLE))
                .target(TargetSpec.of("createTable", "tableName", Access.CREATE))
                .target(TargetSpec.of("createView", "fullId", Access.CREATE, ObjectKind.VIEW))
                .target(TargetSpec.of("createIndex", "uid", Access.CREATE, ObjectKind.INDEX))
                .target(TargetSpec.of("createIndex", "tableName", Access.ALTER))
                .target(TargetSpec.of("createSequence", "fullId", Access.CREATE, ObjectKind.SEQUENCE))
                .target(TargetSpec.of("alterTable", "tableName", Access.ALTER))
                .target(TargetSpec.of("dropTable", "tables", Access.DROP))
                .target(TargetSpec.all("dropView", "fullId", Access.DROP, ObjectKind.VIEW))
                .target(TargetSpec.of("dropProcedure", "fullId", Access.DROP, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("dropFunction", "fullId", Access.DROP, ObjectKind.FUNCTION))
                .target(TargetSpec.of("truncateTable", "tableName", Access.TRUNCATE))
                .target(TargetSpec.of("createProcedure", "fullId", Access.CREATE, ObjectKind.PROCEDURE))
                .target(TargetSpec.of("createFunction", "fullId", Access.CREATE, ObjectKind.FUNCTION))
                .target(TargetSpec.of("createTrigger", "fullId", Access.CREATE, ObjectKind.TRIGGER))
                .target(TargetSpec.of("createEvent", "fullId", Access.CREATE, ObjectKind.ROUTINE))
                .target(TargetSpec.of("callStatement", "fullId", Access.EXECUTE, ObjectKind.ROUTINE)));
    }

    @Override
    public LineageRules lineage() {
        return MariaDbLineageRules.INSTANCE;
    }

    @Override
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        String rule = ruleName(ctx);
        if (kind == StatementKind.CREATE_TABLE && hasDescendant(ctx, "selectStatement")) {
            return StatementKind.CREATE_TABLE_AS;
        }
        if (rule.equals("withStatement")) {
            for (int i = 0; i < ctx.getChildCount(); i++) {
                ParseTree child = ctx.getChild(i);
                if (child instanceof ParserRuleContext c && ruleName(c).equals("dmlStatement") && c.getChildCount() > 0
                        && c.getChild(0) instanceof ParserRuleContext inner) {
                    return DML_KINDS.getOrDefault(ruleName(inner), StatementKind.SELECT);
                }
            }
        }
        return kind;
    }

    @Override
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        if ((ref.access() == Access.CREATE || ref.access() == Access.DROP) && ref.kind() == ObjectKind.TABLE
                && leadingTokensContain(statementNode, 5, "TEMPORARY")) {
            return ObjectKind.TEMP_TABLE;
        }
        return ref.kind();
    }
}
