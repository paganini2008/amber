package com.github.amber.dialect.h2;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.AntlrFrontend;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.h2.grammar.H2Lexer;
import com.github.amber.dialect.h2.grammar.H2Parser;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.SemicolonSplitter;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * H2 frontend. Grammar: written for Amber from the H2 2.x documentation (see UPSTREAM.md).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class H2Frontend extends AntlrFrontend<H2Lexer, H2Parser> {

    private static final ScriptSplitter SPLITTER = new SemicolonSplitter(ScannerOptions.H2);

    @Override
    public Dialect dialect() {
        return Dialect.H2;
    }

    @Override
    public IrRules irRules() {
        return H2IrRules.INSTANCE;
    }

    @Override
    public ScriptSplitter splitter() {
        return SPLITTER;
    }

    @Override
    protected H2Lexer createLexer(CharStream input) {
        return new H2Lexer(input);
    }

    @Override
    protected H2Parser createParser(CommonTokenStream tokens) {
        return new H2Parser(tokens);
    }

    @Override
    protected ParserRuleContext entry(H2Parser parser) {
        return parser.script();
    }
}
