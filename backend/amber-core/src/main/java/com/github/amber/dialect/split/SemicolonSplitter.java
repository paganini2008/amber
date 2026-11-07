package com.github.amber.dialect.split;

import com.github.amber.core.source.SourceText;

import java.util.List;

/**
 * Splits at {@code ;} outside strings, quoted identifiers and comments, as H2's {@code RUNSCRIPT}
 * does. Dollar-quoted bodies ({@code $$ … $$}) of aliases and triggers stay whole.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SemicolonSplitter implements ScriptSplitter {

    private final ScannerOptions options;

    public SemicolonSplitter(ScannerOptions options) {
        this.options = options;
    }

    @Override
    public List<StatementUnit> split(SourceText source) {
        SqlScanner sc = new SqlScanner(source.text(), options);
        UnitCollector units = new UnitCollector(source);
        ScanToken first;
        while ((first = sc.next()) != null) {
            int end = first.end();
            ScanToken t = first;
            while (!t.isSymbol(';') && (t = sc.next()) != null) {
                end = t.end();
            }
            units.add(UnitKind.SQL, first.start(), end);
        }
        return units.units();
    }
}
