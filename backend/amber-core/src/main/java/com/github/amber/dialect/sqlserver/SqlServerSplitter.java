package com.github.amber.dialect.sqlserver;

import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.SqlScanner;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.UnitCollector;
import com.github.amber.dialect.split.UnitKind;

import java.util.List;

/**
 * Splits T-SQL scripts into batches, the way sqlcmd and SSMS do:
 * <ul>
 *   <li>{@code GO} (optionally {@code GO n}) alone on its line ends a batch;</li>
 *   <li>sqlcmd commands ({@code :setvar}, {@code :r}, {@code :on error}, …) at the start of a line are
 *       {@link UnitKind#CLIENT_COMMAND} units.</li>
 * </ul>
 * Statements inside a batch need no terminator in T-SQL, so the batch is the unit; statements are
 * separated later from the parse tree.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class SqlServerSplitter implements ScriptSplitter {

    @Override
    public List<StatementUnit> split(SourceText source) {
        SqlScanner sc = new SqlScanner(source.text(), ScannerOptions.SQLSERVER);
        UnitCollector units = new UnitCollector(source);

        int batchStart = -1;
        int batchEnd = -1;
        ScanToken t;
        while ((t = sc.next()) != null) {
            if (isGo(sc, t)) {
                if (batchStart >= 0) {
                    units.add(UnitKind.SQL, batchStart, batchEnd);
                    batchStart = -1;
                }
                sc.reset(sc.lineEnd(t.start()));
                continue;
            }
            if (sc.startsLine(t) && (t.isSymbol('!') && sc.peek() != null && sc.peek().isSymbol('!')
                    || (t.isWordIn(java.util.Set.of("EXIT", "QUIT")) && sc.restOfLineEmpty(t.end()) && batchStart < 0))) {
                if (batchStart >= 0) {
                    units.add(UnitKind.SQL, batchStart, batchEnd);   // units stay in order and never overlap
                    batchStart = -1;
                }
                int end = sc.lineEnd(t.start());
                units.add(UnitKind.CLIENT_COMMAND, t.start(), end);
                sc.reset(end);
                continue;
            }
            if (t.isSymbol(':') && sc.startsLine(t) && isSqlCmdCommand(sc)) {
                if (batchStart >= 0) {
                    units.add(UnitKind.SQL, batchStart, batchEnd);
                    batchStart = -1;
                }
                int end = sc.lineEnd(t.start());
                units.add(UnitKind.CLIENT_COMMAND, t.start(), end);
                sc.reset(end);
                continue;
            }
            if (batchStart < 0) {
                batchStart = t.start();
            }
            batchEnd = t.end();
        }
        if (batchStart >= 0) {
            units.add(UnitKind.SQL, batchStart, batchEnd);
        }
        return units.units();
    }

    /** {@code GO} or {@code GO 5}, alone on the line apart from comments. */
    private static boolean isGo(SqlScanner sc, ScanToken t) {
        if (!t.isWord("GO") || !sc.startsLine(t)) {
            return false;
        }
        if (sc.restOfLineEmpty(t.end())) {
            return true;
        }
        int saved = sc.position();
        ScanToken count = sc.next();
        boolean ok = count != null && count.type() == ScanToken.Type.NUMBER && sc.restOfLineEmpty(count.end())
                && sc.lineStart(count.start()) == sc.lineStart(t.start());
        sc.reset(saved);
        return ok;
    }

    private static boolean isSqlCmdCommand(SqlScanner sc) {
        ScanToken word = sc.peek();
        return word != null && word.type() == ScanToken.Type.WORD;
    }
}
