package com.github.amber.dialect.sqlite;

import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.BlockDepth;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.SqlScanner;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.UnitCollector;
import com.github.amber.dialect.split.UnitKind;

import java.util.List;

/**
 * Splits SQLite scripts the way {@code sqlite3_complete()} and the {@code sqlite3} shell do:
 * <ul>
 *   <li>a statement ends at {@code ;};</li>
 *   <li>inside {@code CREATE [TEMP] TRIGGER … BEGIN … END} the inner {@code ;} do not end it
 *       ({@code BEGIN} alone, as in {@code BEGIN TRANSACTION}, is not tracked elsewhere);</li>
 *   <li>shell dot-commands ({@code .mode}, {@code .import}, …) at the start of a line are
 *       {@link UnitKind#CLIENT_COMMAND} units.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SqliteSplitter implements ScriptSplitter {

    @Override
    public List<StatementUnit> split(SourceText source) {
        SqlScanner sc = new SqlScanner(source.text(), ScannerOptions.SQLITE);
        UnitCollector units = new UnitCollector(source);

        ScanToken first;
        while ((first = sc.next()) != null) {
            if (first.isSymbol('.') && sc.startsLine(first)) {
                int end = sc.lineEnd(first.start());
                units.add(UnitKind.CLIENT_COMMAND, first.start(), end);
                sc.reset(end);
                continue;
            }
            BlockDepth body = isCreateTrigger(sc, first) ? new BlockDepth() : null;
            int end = first.end();
            ScanToken t = first;
            while (t != null && !(t.isSymbol(';') && (body == null || !body.inBlock()))) {
                if (body != null) {
                    body.accept(t, sc.peek());
                }
                t = sc.next();
                if (t != null) {
                    end = t.end();
                }
            }
            units.add(UnitKind.SQL, first.start(), end);
        }
        return units.units();
    }

    private static boolean isCreateTrigger(SqlScanner sc, ScanToken first) {
        if (!first.isWord("CREATE")) {
            return false;
        }
        int saved = sc.position();
        ScanToken a = sc.next();
        ScanToken b = a != null && (a.isWord("TEMP") || a.isWord("TEMPORARY")) ? sc.next() : a;
        sc.reset(saved);
        return b != null && b.isWord("TRIGGER");
    }
}
