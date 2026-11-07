package com.github.amber.dialect.oracle;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.AntlrFrontend;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.oracle.grammar.PlSqlLexer;
import com.github.amber.dialect.oracle.grammar.PlSqlParser;
import com.github.amber.dialect.split.ScriptSplitter;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Oracle frontend. Grammar: grammars-v4 plsql (see UPSTREAM.md).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class OracleFrontend extends AntlrFrontend<PlSqlLexer, PlSqlParser> {

    private static final ScriptSplitter SPLITTER = new OracleSplitter();

    @Override
    public Dialect dialect() {
        return Dialect.ORACLE;
    }

    @Override
    public ScriptSplitter splitter() {
        return SPLITTER;
    }

    @Override
    public IrRules irRules() {
        return OracleIrRules.INSTANCE;
    }

    @Override
    protected String prepare(StatementUnit unit, String unitText) {
        return SubstitutionVariables.mask(unitText);
    }

    @Override
    protected PlSqlLexer createLexer(CharStream input) {
        return new PlSqlLexer(input);
    }

    @Override
    protected PlSqlParser createParser(CommonTokenStream tokens) {
        return new PlSqlParser(tokens);
    }

    @Override
    protected ParserRuleContext entry(PlSqlParser parser) {
        return parser.sql_script();
    }
}
