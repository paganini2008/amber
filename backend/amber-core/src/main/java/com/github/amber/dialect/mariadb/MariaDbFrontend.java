package com.github.amber.dialect.mariadb;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.AntlrFrontend;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.mariadb.grammar.MariaDBLexer;
import com.github.amber.dialect.mariadb.grammar.MariaDBParser;
import com.github.amber.dialect.mysql.MySqlSplitter;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.StatementUnit;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * MariaDB frontend. Grammar: grammars-v4 mariadb (see UPSTREAM.md).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class MariaDbFrontend extends AntlrFrontend<MariaDBLexer, MariaDBParser> {

    private static final ScriptSplitter SPLITTER = new MySqlSplitter();

    @Override
    public Dialect dialect() {
        return Dialect.MARIADB;
    }

    @Override
    public ScriptSplitter splitter() {
        return SPLITTER;
    }

    @Override
    public IrRules irRules() {
        return MariaDbIrRules.INSTANCE;
    }

    @Override
    protected String prepare(StatementUnit unit, String unitText) {
        return unwrapVersionedComments(terminatorToSemicolon(unit, unitText));
    }

    @Override
    protected MariaDBLexer createLexer(CharStream input) {
        return new MariaDBLexer(input);
    }

    @Override
    protected MariaDBParser createParser(CommonTokenStream tokens) {
        return new MariaDBParser(tokens);
    }

    @Override
    protected ParserRuleContext entry(MariaDBParser parser) {
        return parser.root();
    }
}
