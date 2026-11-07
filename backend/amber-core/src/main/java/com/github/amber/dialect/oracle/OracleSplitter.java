package com.github.amber.dialect.oracle;

import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.SqlScanner;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.UnitCollector;
import com.github.amber.dialect.split.UnitKind;

import java.util.List;
import java.util.Set;

/**
 * Splits Oracle scripts the way SQL*Plus and SQLcl do:
 * <ul>
 *   <li>a SQL statement ends at {@code ;} or at a {@code /} alone on its line;</li>
 *   <li>a PL/SQL unit ({@code DECLARE}, {@code BEGIN}, {@code CREATE PROCEDURE/FUNCTION/PACKAGE/TRIGGER/TYPE …})
 *       ends only at a {@code /} alone on its line, or at end of input;</li>
 *   <li>SQL*Plus commands ({@code SET}, {@code PROMPT}, {@code SPOOL}, {@code @file}, …) are one line,
 *       with {@code -} continuation, and become {@link UnitKind#CLIENT_COMMAND} units.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class OracleSplitter implements ScriptSplitter {

    private static final Set<String> SQLPLUS_COMMANDS = Set.of(
            "SET", "PROMPT", "PRO", "SPOOL", "SPO", "WHENEVER", "EXIT", "QUIT", "DEFINE", "DEF", "UNDEFINE",
            "COLUMN", "COL", "TTITLE", "BTITLE", "REPHEADER", "REPFOOTER", "BREAK", "COMPUTE", "SHOW", "SHO",
            "CONNECT", "CONN", "DISCONNECT", "EXEC", "EXECUTE", "REM", "REMARK", "CLEAR", "TIMING", "PAUSE",
            "ACCEPT", "ACC", "VARIABLE", "VAR", "PRINT", "HOST", "STORE", "START", "DESCRIBE", "DESC",
            "PASSWORD", "APPEND", "CHANGE", "INPUT", "LIST", "SAVE", "GET", "EDIT", "RUN");

    /** {@code SET} followed by one of these is SQL, not SQL*Plus. */
    private static final Set<String> SQL_SET_TARGETS = Set.of("TRANSACTION", "ROLE", "CONSTRAINT", "CONSTRAINTS");

    private static final Set<String> PLSQL_OBJECTS = Set.of("PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER", "TYPE",
            "LIBRARY", "JAVA");

    private static final Set<String> CREATE_MODIFIERS = Set.of("OR", "REPLACE", "EDITIONABLE", "NONEDITIONABLE",
            "EDITIONING", "NONEDITIONING", "AND", "COMPILE", "RESOLVE", "NOFORCE", "FORCE", "SHARING", "=",
            "METADATA", "DATA", "EXTENDED", "NONE");

    @Override
    public List<StatementUnit> split(SourceText source) {
        SqlScanner sc = new SqlScanner(source.text(), ScannerOptions.ORACLE);
        UnitCollector units = new UnitCollector(source);

        ScanToken first;
        while ((first = sc.next()) != null) {
            if (isSlashLine(sc, first)) {
                continue;                                     // stray '/' re-runs the buffer; nothing to parse
            }
            if (sc.startsLine(first) && isSqlPlusCommand(sc, first)) {
                int end = commandEnd(sc, first.start());
                units.add(UnitKind.CLIENT_COMMAND, first.start(), end);
                sc.reset(end);
                continue;
            }
            boolean plsql = isPlsqlUnitStart(sc, first);
            int end = first.end();
            ScanToken t;
            while ((t = sc.next()) != null) {
                if (isSlashLine(sc, t)) {
                    break;                                    // '/' terminates; not part of the unit
                }
                end = t.end();
                if (!plsql && t.isSymbol(';')) {
                    break;
                }
            }
            units.add(UnitKind.SQL, first.start(), end);
        }
        return units.units();
    }

    private static boolean isSlashLine(SqlScanner sc, ScanToken t) {
        return t.isSymbol('/') && sc.aloneOnLine(t);
    }

    private static boolean isSqlPlusCommand(SqlScanner sc, ScanToken first) {
        if (first.isSymbol('@') || first.isSymbol('!') || first.isSymbol('$')) {
            return true;                                      // @file, @@file, !host, $host (Windows)
        }
        if (!first.isWordIn(SQLPLUS_COMMANDS)) {
            return false;
        }
        if (first.isWord("SET")) {
            ScanToken next = sc.peek();
            return next == null || !next.isWordIn(SQL_SET_TARGETS);
        }
        return true;
    }

    /** End of a SQL*Plus command: end of line, extended by a trailing {@code -} continuation. */
    private static int commandEnd(SqlScanner sc, int start) {
        String text = sc.text();
        int end = sc.lineEnd(start);
        while (end < text.length() && text.substring(sc.lineStart(end), end).stripTrailing().endsWith(" -")) {
            end = sc.lineEnd(sc.nextLineStart(end));
        }
        return end;
    }

    private static boolean isPlsqlUnitStart(SqlScanner sc, ScanToken first) {
        if (first.isWord("DECLARE") || first.isWord("BEGIN")) {
            return true;
        }
        if (first.isWord("WITH")) {                           // WITH FUNCTION/PROCEDURE … SELECT ends at '/'
            ScanToken next = sc.peek();
            return next != null && (next.isWord("FUNCTION") || next.isWord("PROCEDURE"));
        }
        if (first.isSymbol('<')) {
            return true;                                      // <<label>> before an anonymous block
        }
        if (!first.isWord("CREATE")) {
            return false;
        }
        int saved = sc.position();
        try {
            ScanToken t;
            for (int i = 0; i < 12 && (t = sc.next()) != null; i++) {
                if (t.isWordIn(PLSQL_OBJECTS)) {
                    return true;
                }
                if (!(t.isWordIn(CREATE_MODIFIERS) || t.isSymbol('='))) {
                    return false;
                }
            }
            return false;
        } finally {
            sc.reset(saved);
        }
    }
}
