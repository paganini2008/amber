package com.github.amber.dialect.mysql;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.AntlrFrontend;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.mysql.grammar.MySQLLexer;
import com.github.amber.dialect.mysql.grammar.MySQLParser;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.StatementUnit;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * MySQL frontend. Grammar: grammars-v4 mysql/Oracle (see UPSTREAM.md).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class MySqlFrontend extends AntlrFrontend<MySQLLexer, MySQLParser> {

    private static final ScriptSplitter SPLITTER = new MySqlSplitter();

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public ScriptSplitter splitter() {
        return SPLITTER;
    }

    @Override
    public IrRules irRules() {
        return MySqlIrRules.INSTANCE;
    }

    @Override
    protected String prepare(StatementUnit unit, String unitText) {
        return unwrapVersionedComments(terminatorToSemicolon(unit, unitText));
    }

    @Override
    protected MySQLLexer createLexer(CharStream input) {
        return new MySQLLexer(input);
    }

    @Override
    protected MySQLParser createParser(CommonTokenStream tokens) {
        return new MySQLParser(tokens);
    }

    @Override
    protected ParserRuleContext entry(MySQLParser parser) {
        return parser.queries();
    }
}
