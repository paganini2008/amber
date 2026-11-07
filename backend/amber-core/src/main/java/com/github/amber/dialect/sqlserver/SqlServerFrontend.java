package com.github.amber.dialect.sqlserver;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.AntlrFrontend;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.sqlserver.grammar.TSqlLexer;
import com.github.amber.dialect.sqlserver.grammar.TSqlParser;
import com.github.amber.dialect.split.ScriptSplitter;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * SQL Server frontend. Grammar: grammars-v4 tsql (see UPSTREAM.md).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SqlServerFrontend extends AntlrFrontend<TSqlLexer, TSqlParser> {

    private static final ScriptSplitter SPLITTER = new SqlServerSplitter();

    @Override
    public Dialect dialect() {
        return Dialect.SQLSERVER;
    }

    @Override
    public ScriptSplitter splitter() {
        return SPLITTER;
    }

    @Override
    public IrRules irRules() {
        return SqlServerIrRules.INSTANCE;
    }

    @Override
    protected TSqlLexer createLexer(CharStream input) {
        return new TSqlLexer(input);
    }

    @Override
    protected TSqlParser createParser(CommonTokenStream tokens) {
        return new TSqlParser(tokens);
    }

    @Override
    protected ParserRuleContext entry(TSqlParser parser) {
        return parser.tsql_file();
    }
}
