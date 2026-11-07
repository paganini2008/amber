package com.github.amber.dialect.postgresql;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.AntlrFrontend;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.postgresql.grammar.PostgreSQLLexer;
import com.github.amber.dialect.postgresql.grammar.PostgreSQLParser;
import com.github.amber.dialect.split.ScriptSplitter;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * PostgreSQL frontend. Grammar: grammars-v4 postgresql (see UPSTREAM.md).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class PostgreSqlFrontend extends AntlrFrontend<PostgreSQLLexer, PostgreSQLParser> {

    private static final ScriptSplitter SPLITTER = new PostgreSqlSplitter();

    @Override
    public Dialect dialect() {
        return Dialect.POSTGRESQL;
    }

    @Override
    public ScriptSplitter splitter() {
        return SPLITTER;
    }

    @Override
    public IrRules irRules() {
        return PostgreSqlIrRules.INSTANCE;
    }

    @Override
    protected PostgreSQLLexer createLexer(CharStream input) {
        return new PostgreSQLLexer(input);
    }

    @Override
    protected PostgreSQLParser createParser(CommonTokenStream tokens) {
        return new PostgreSQLParser(tokens);
    }

    @Override
    protected ParserRuleContext entry(PostgreSQLParser parser) {
        return parser.root();
    }
}
