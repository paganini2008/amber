package com.github.amber.dialect.split;

import com.github.amber.core.source.SourceText;

import java.util.List;

/**
 * Treats the whole script as one unit. Used by dialects that do not have a splitter yet.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class WholeScriptSplitter implements ScriptSplitter {

    public static final WholeScriptSplitter INSTANCE = new WholeScriptSplitter();

    private WholeScriptSplitter() {
    }

    @Override
    public List<StatementUnit> split(SourceText source) {
        String text = source.text();
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start == end) {
            return List.of();
        }
        return List.of(new StatementUnit(0, UnitKind.SQL, source.range(start, end)));
    }
}
