package com.github.amber.dialect.postgresql;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.diagnostic.Severity;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.ir.TargetSpec;
import com.github.amber.dialect.lineage.LineageRules;
import com.github.amber.dialect.postgresql.grammar.PostgreSQLParser;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Meaning of the grammars-v4 PostgreSQL rules for Amber's IR. PL/pgSQL bodies inside {@code $$}
 * are strings in this grammar; their statements are analyzed separately.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class PostgreSqlIrRules extends IrRules {

    static final PostgreSqlIrRules INSTANCE = new PostgreSqlIrRules();

    private PostgreSqlIrRules() {
        super(IrRules.builder(Dialect.POSTGRESQL, PostgreSQLParser.ruleNames)
                .statementParents("stmtmulti")
                .wrappers("stmt")
                // queries and DML
                .kind(StatementKind.SELECT, "selectstmt")
                .kind(StatementKind.INSERT, "insertstmt")
                .kind(StatementKind.UPDATE, "updatestmt")
                .kind(StatementKind.DELETE, "deletestmt")
                .kind(StatementKind.MERGE, "mergestmt")
                .kind(StatementKind.OTHER, "copystmt")
                // DDL
                .kind(StatementKind.CREATE_TABLE, "createstmt")
                .kind(StatementKind.CREATE_TABLE_AS, "createasstmt")
                .kind(StatementKind.CREATE_VIEW, "viewstmt")
                .kind(StatementKind.CREATE_MATERIALIZED_VIEW, "creatematviewstmt")
                .kind(StatementKind.CREATE_INDEX, "indexstmt")
                .kind(StatementKind.CREATE_SEQUENCE, "createseqstmt")
                .kind(StatementKind.CREATE_SCHEMA, "createschemastmt")
                .kind(StatementKind.CREATE_FUNCTION, "createfunctionstmt")
                .kind(StatementKind.CREATE_TRIGGER, "createtrigstmt")
                .kind(StatementKind.ALTER, "altertablestmt")
                .kind(StatementKind.DROP, "dropstmt")
                .kind(StatementKind.TRUNCATE, "truncatestmt")
                // other
                .kind(StatementKind.BLOCK, "dostmt")
                .kind(StatementKind.CALL, "callstmt")
                .kind(StatementKind.TRANSACTION, "transactionstmt")
                .kind(StatementKind.SESSION, "variablesetstmt")
                // names
                .nameRules("qualified_name")
                .subqueryRules("select_with_parens")
                .cte("common_table_expr", "name")
                .aliasRules("alias_clause", "colid")
                .aliasDepth(0)
                // what statements act on
                .target(TargetSpec.of("insert_target", "qualified_name", Access.INSERT))
                .target(TargetSpec.of("updatestmt", "relation_expr_opt_alias", Access.UPDATE))
                .target(TargetSpec.of("deletestmt", "relation_expr_opt_alias", Access.DELETE))
                .target(TargetSpec.of("mergestmt", "qualified_name", Access.MERGE))
                .target(TargetSpec.of("opttempTableName", "qualified_name", Access.CREATE))
                .target(TargetSpec.of("createstmt", "qualified_name", Access.CREATE))
                .target(TargetSpec.of("create_as_target", "qualified_name", Access.CREATE))
                .target(TargetSpec.of("viewstmt", "qualified_name", Access.CREATE, ObjectKind.VIEW))
                .target(TargetSpec.of("create_mv_target", "qualified_name", Access.CREATE, ObjectKind.MATERIALIZED_VIEW))
                .target(TargetSpec.of("indexstmt", "relation_expr", Access.ALTER))
                .target(TargetSpec.of("createseqstmt", "qualified_name", Access.CREATE, ObjectKind.SEQUENCE))
                .target(TargetSpec.of("altertablestmt", "relation_expr", Access.ALTER))
                .target(TargetSpec.all("any_name_list_", "any_name", Access.DROP, ObjectKind.TABLE))
                .target(TargetSpec.of("truncatestmt", "relation_expr_list", Access.TRUNCATE))
                .target(TargetSpec.of("createfunctionstmt", "func_name", Access.CREATE, ObjectKind.FUNCTION))
                .target(TargetSpec.of("createtrigstmt", "name", Access.CREATE, ObjectKind.TRIGGER))
                .target(TargetSpec.of("callstmt", "func_application", Access.EXECUTE, ObjectKind.ROUTINE)));
    }

    private static final Set<StatementKind> BODY_KINDS = Set.of(StatementKind.CREATE_FUNCTION,
            StatementKind.CREATE_PROCEDURE, StatementKind.BLOCK);

    /** Parses PL/pgSQL and SQL function bodies and DO blocks, which the grammar keeps as strings. */
    @Override
    public List<Diagnostic> enrich(Statement st,
                                                                             ParserRuleContext node,
                                                                             SourceText source,
                                                                             Catalog catalog) {
        if (!BODY_KINDS.contains(st.kind())) {
            return List.of();
        }
        String rule = ruleName(node);
        boolean doBlock = rule.equals("dostmt");
        if (!doBlock && !rule.equals("createfunctionstmt")) {
            return List.of();
        }
        SqlScanner sc = new SqlScanner(source.text(),
                ScannerOptions.POSTGRESQL);
        sc.reset(st.range().start().offset());
        int stop = st.range().end().offset();
        String language = doBlock ? "plpgsql" : null;
        ScanToken body = null;
        ScanToken previous = null;
        ScanToken t;
        while ((t = sc.next()) != null && t.start() < stop) {
            if (previous != null && previous.isWord("LANGUAGE")) {
                language = t.text().replace("'", "").toLowerCase(Locale.ROOT);
            }
            if (t.type() == ScanToken.Type.STRING
                    && (doBlock || (previous != null && (previous.isWord("AS") || previous.isSymbol(','))))
                    && body == null) {
                body = t;
            }
            previous = t;
        }
        if (body == null || language == null || !(language.equals("plpgsql") || language.equals("sql"))) {
            return List.of();
        }
        String text = body.text();
        if (!text.startsWith("$")) {
            return List.of(new Diagnostic(
                    Severity.INFO, "plpgsql",
                    "Function body in a quoted string is not analyzed; dollar-quoted bodies ($$ … $$) are",
                    source.range(body.start(), body.end()), null, List.of()));
        }
        int tag = text.indexOf('$', 1) + 1;
        PlPgSqlParser parser = new PlPgSqlParser(frontend(), source, body.start() + tag, body.end() - tag, catalog);
        parser.routine(st, node);                     // parameters and name of the variables' scope
        if (language.equals("plpgsql")) {
            parser.parsePlpgsql(st);
        } else {
            parser.parseSqlBody(st);
        }
        return parser.diagnostics();
    }

    private static PostgreSqlFrontend frontend() {
        return (PostgreSqlFrontend) DialectFrontends.get(Dialect.POSTGRESQL);
    }

    @Override
    public LineageRules lineage() {
        return PostgreSqlLineageRules.INSTANCE;
    }

    @Override
    protected StatementKind refineKind(StatementKind kind, ParserRuleContext ctx) {
        if (kind == StatementKind.SELECT && hasDescendant(ctx, "into_clause")) {
            return StatementKind.SELECT_INTO;
        }
        if (kind == StatementKind.CREATE_FUNCTION && leadingTokensContain(ctx, 4, "PROCEDURE")) {
            return StatementKind.CREATE_PROCEDURE;
        }
        return kind;
    }

    @Override
    public ObjectKind refineObjectKind(ObjectRef ref, ParserRuleContext statementNode) {
        if (ref.access() == Access.CREATE && ref.kind() == ObjectKind.TABLE
                && (temporary(statementNode, "opttemp") || temporary(statementNode, "opttempTableName"))) {
            return ObjectKind.TEMP_TABLE;
        }
        if (ref.access() == Access.CREATE && ref.kind() == ObjectKind.FUNCTION
                && leadingTokensContain(statementNode, 4, "PROCEDURE")) {
            return ObjectKind.PROCEDURE;
        }
        if (ref.access() == Access.DROP && ruleName(statementNode).equals("dropstmt")) {
            String type = firstChildText(statementNode, "object_type_any_name");
            if (type != null) {
                if (type.startsWith("MATERIALIZED")) {
                    return ObjectKind.MATERIALIZED_VIEW;
                }
                if (type.startsWith("VIEW")) {
                    return ObjectKind.VIEW;
                }
                if (type.startsWith("INDEX")) {
                    return ObjectKind.INDEX;
                }
                if (type.startsWith("SEQUENCE")) {
                    return ObjectKind.SEQUENCE;
                }
                if (!type.startsWith("TABLE") && !type.startsWith("FOREIGNTABLE")) {
                    return ObjectKind.OTHER;
                }
            }
        }
        return ref.kind();
    }

    /** True when a descendant with rule {@code rule} contains a TEMP or TEMPORARY keyword. */
    private boolean temporary(ParserRuleContext ctx, String rule) {
        String text = firstDescendantText(ctx, rule);
        if (text == null) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.startsWith("TEMP") || upper.startsWith("LOCALTEMP") || upper.startsWith("GLOBALTEMP");
    }

    private String firstChildText(ParserRuleContext ctx, String rule) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c && ruleName(c).equals(rule)) {
                return c.getText().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String firstDescendantText(ParserRuleContext ctx, String rule) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child instanceof ParserRuleContext c) {
                if (ruleName(c).equals(rule)) {
                    return c.getText();
                }
                if (!ruleName(c).equals("select_with_parens")) {
                    String found = firstDescendantText(c, rule);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }
}
