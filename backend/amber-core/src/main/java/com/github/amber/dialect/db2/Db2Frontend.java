package com.github.amber.dialect.db2;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.AntlrFrontend;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.db2.grammar.Db2Lexer;
import com.github.amber.dialect.db2.grammar.Db2Parser;
import com.github.amber.dialect.split.ScriptSplitter;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Db2 frontend. Grammar: written for Amber from the Db2 12.1 SQL Reference (see UPSTREAM.md).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Db2Frontend extends AntlrFrontend<Db2Lexer, Db2Parser> {

    private static final ScriptSplitter SPLITTER = new Db2Splitter();

    @Override
    public Dialect dialect() {
        return Dialect.DB2;
    }

    @Override
    public IrRules irRules() {
        return Db2IrRules.INSTANCE;
    }

    @Override
    public ScriptSplitter splitter() {
        return SPLITTER;
    }

    @Override
    protected Db2Lexer createLexer(CharStream input) {
        return new Db2Lexer(input);
    }

    @Override
    protected Db2Parser createParser(CommonTokenStream tokens) {
        return new Db2Parser(tokens);
    }

    @Override
    protected ParserRuleContext entry(Db2Parser parser) {
        return parser.script();
    }
}
