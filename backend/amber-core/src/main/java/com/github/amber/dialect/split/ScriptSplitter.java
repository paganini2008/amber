package com.github.amber.dialect.split;

import com.github.amber.core.source.SourceText;

import java.util.List;

/**
 * Cuts a script into {@link StatementUnit}s so each can be parsed, reported and cached on its own.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public interface ScriptSplitter {

    List<StatementUnit> split(SourceText source);
}
