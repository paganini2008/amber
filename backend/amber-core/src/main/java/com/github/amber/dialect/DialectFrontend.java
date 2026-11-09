package com.github.amber.dialect;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.WholeScriptSplitter;

/**
 * Entry point of one dialect. Implementations are discovered with {@link java.util.ServiceLoader}
 * (see {@link DialectFrontends}).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public interface DialectFrontend {

    Dialect dialect();

    /** How scripts of this dialect are cut into statements, blocks or batches. */
    default ScriptSplitter splitter() {
        return WholeScriptSplitter.INSTANCE;
    }

    /**
     * How this dialect's parse trees map to Amber's IR, or {@code null} when the dialect has no
     * mapping yet (statements are then only scanned at token level).
     */
    default IrRules irRules() {
        return null;
    }

    /** Parses one SQL unit. Never throws for bad SQL; errors are reported in the result. */
    UnitParse parseUnit(SourceText source, StatementUnit unit);

    /** Splits and parses a whole script. */
    default ScriptParse parse(SourceText source, ParseOptions options) {
        return ScriptParser.parse(this, source, options);
    }

    default ScriptParse parse(SourceText source) {
        return parse(source, ParseOptions.STRICT);
    }
}
