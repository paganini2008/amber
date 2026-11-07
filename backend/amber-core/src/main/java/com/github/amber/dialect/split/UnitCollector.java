package com.github.amber.dialect.split;

import com.github.amber.core.source.SourceText;

import java.util.ArrayList;
import java.util.List;

/** Small helper for splitters: numbers units and drops empty ones. */
/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class UnitCollector {

    private final SourceText source;
    private final List<StatementUnit> units = new ArrayList<>();

    public UnitCollector(SourceText source) {
        this.source = source;
    }

    public void add(UnitKind kind, int start, int end) {
        add(kind, start, end, null);
    }

    /** Adds a unit whose range ends with a client delimiter (see {@link StatementUnit#terminator()}). */
    public void add(UnitKind kind, int start, int end, String terminator) {
        String text = source.text();
        if (terminator == null) {
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
        }
        if (end > start) {
            units.add(new StatementUnit(units.size(), kind, source.range(start, end), terminator));
        }
    }

    public List<StatementUnit> units() {
        return List.copyOf(units);
    }
}
