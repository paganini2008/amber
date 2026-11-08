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
import org.antlr.v4.runtime.Token;

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

    /** Opening parentheses in a row before a query that the grammar still predicts quickly (about 0.3 s). */
    static final int MAX_QUERY_PARENS = 12;

    /**
     * The grammar's {@code selectStatementWithInto} and {@code queryExpressionParens} both recurse on
     * {@code (}, so each parenthesis that opens a query at statement level (at the start, or after
     * UNION / EXCEPT / INTERSECT) about doubles prediction work: 16 of them filled a 2 GB heap. Parentheses
     * inside a query (subqueries, IN lists, expressions) are not affected.
     */
    @Override
    protected String tooComplex(CommonTokenStream tokens) {
        int run = 0;
        boolean queryPosition = true;
        for (Token t : tokens.getTokens()) {
            if (t.getChannel() != Token.DEFAULT_CHANNEL || t.getType() == Token.EOF) {
                continue;
            }
            String text = t.getText();
            if (queryPosition && text.equals("(")) {
                if (++run > MAX_QUERY_PARENS) {
                    return "More than " + MAX_QUERY_PARENS + " parentheses in a row before a query; Amber cannot parse "
                            + "this statement (the MySQL grammar needs exponential time for it)";
                }
                continue;
            }
            run = 0;
            boolean setOperator = text.equalsIgnoreCase("UNION") || text.equalsIgnoreCase("EXCEPT")
                    || text.equalsIgnoreCase("INTERSECT");
            boolean quantifier = text.equalsIgnoreCase("ALL") || text.equalsIgnoreCase("DISTINCT");
            queryPosition = setOperator || quantifier && queryPosition || text.equals(";");
        }
        return null;
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
