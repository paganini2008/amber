package com.github.amber.dialect.postgresql;

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
 * Splits PostgreSQL scripts the way psql does:
 * <ul>
 *   <li>a statement ends at {@code ;} outside parentheses, strings and {@code $$} bodies;</li>
 *   <li>SQL-standard function bodies ({@code BEGIN ATOMIC … END}) keep their inner {@code ;};</li>
 *   <li>psql meta-commands ({@code \set}, {@code \i}, {@code \connect}, …) are {@link UnitKind#CLIENT_COMMAND} units;
 *       a meta-command after a query ({@code SELECT … \gset}) also ends that query;</li>
 *   <li>rows after {@code COPY … FROM STDIN;} up to {@code \.} are a {@link UnitKind#DATA} unit.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class PostgreSqlSplitter implements ScriptSplitter {

    @Override
    public List<StatementUnit> split(SourceText source) {
        SqlScanner sc = new SqlScanner(source.text(), ScannerOptions.POSTGRESQL);
        UnitCollector units = new UnitCollector(source);

        ScanToken first;
        while ((first = sc.next()) != null) {
            if (first.isSymbol('\\') && sc.startsLine(first)) {
                int end = sc.lineEnd(first.start());
                units.add(UnitKind.CLIENT_COMMAND, first.start(), end);
                sc.reset(end);
                continue;
            }
            boolean copy = first.isWord("COPY");
            boolean fromStdin = false;
            ScanToken previous = first;
            int parens = first.isSymbol('(') ? 1 : 0;
            int atomic = 0;
            int end = first.end();
            int metaCommand = -1;
            ScanToken t = first;
            while (!(t.isSymbol(';') && parens <= 0 && atomic <= 0) && (t = sc.next()) != null) {
                if (t.isSymbol('\\')) {
                    metaCommand = t.start();          // \g, \gset, \gx … end the query buffer
                    break;
                }
                end = t.end();
                if (t.isSymbol('(')) {
                    parens++;
                } else if (t.isSymbol(')')) {
                    parens--;
                } else if (t.isWord("ATOMIC") && previous.isWord("BEGIN")) {
                    atomic++;
                } else if (atomic > 0 && t.isWord("CASE")) {
                    atomic++;
                } else if (atomic > 0 && t.isWord("END")) {
                    atomic--;
                } else if (copy && t.isWord("STDIN") && previous.isWord("FROM")) {
                    fromStdin = true;
                }
                previous = t;
            }
            units.add(UnitKind.SQL, first.start(), end);
            if (metaCommand >= 0) {
                int commandEnd = sc.lineEnd(metaCommand);
                units.add(UnitKind.CLIENT_COMMAND, metaCommand, commandEnd);
                sc.reset(commandEnd);
                continue;
            }
            if (copy && fromStdin) {
                int dataStart = sc.nextLineStart(end);
                int dataEnd = copyDataEnd(sc, dataStart);
                units.add(UnitKind.DATA, dataStart, dataEnd);
                sc.reset(sc.nextLineStart(dataEnd));
            }
        }
        return units.units();
    }

    /** Offset of the {@code \.} line that ends COPY data, or end of input. */
    private static int copyDataEnd(SqlScanner sc, int from) {
        String text = sc.text();
        int line = from;
        while (line < text.length()) {
            int lineEnd = sc.lineEnd(line);
            if (text.substring(line, lineEnd).strip().equals("\\.")) {
                return lineEnd;
            }
            line = sc.nextLineStart(line);
        }
        return text.length();
    }
}
