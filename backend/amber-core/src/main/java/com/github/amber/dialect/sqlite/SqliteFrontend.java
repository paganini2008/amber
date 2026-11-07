package com.github.amber.dialect.sqlite;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.AntlrFrontend;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.sqlite.grammar.SQLiteLexer;
import com.github.amber.dialect.sqlite.grammar.SQLiteParser;
import com.github.amber.dialect.split.ScriptSplitter;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * SQLite frontend. Grammar: grammars-v4 sqlite (see UPSTREAM.md).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SqliteFrontend extends AntlrFrontend<SQLiteLexer, SQLiteParser> {

    private static final ScriptSplitter SPLITTER = new SqliteSplitter();

    @Override
    public Dialect dialect() {
        return Dialect.SQLITE;
    }

    @Override
    public IrRules irRules() {
        return SqliteIrRules.INSTANCE;
    }

    @Override
    public ScriptSplitter splitter() {
        return SPLITTER;
    }

    @Override
    protected SQLiteLexer createLexer(CharStream input) {
        return new SQLiteLexer(input);
    }

    @Override
    protected SQLiteParser createParser(CommonTokenStream tokens) {
        return new SQLiteParser(tokens);
    }

    @Override
    protected ParserRuleContext entry(SQLiteParser parser) {
        return parser.parse();
    }
}
