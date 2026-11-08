package com.github.amber.dialect.mysql;

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
import java.util.Set;

/**
 * Splits MySQL and MariaDB scripts the way the {@code mysql} / {@code mariadb} clients do:
 * <ul>
 *   <li>a statement ends at the current delimiter ({@code ;} by default) or at {@code \g} / {@code \G};</li>
 *   <li>{@code DELIMITER x} lines change the delimiter (e.g. {@code $$} or {@code //} around stored
 *       programs) and are {@link UnitKind#CLIENT_COMMAND} units, as are {@code SOURCE}, {@code \.} and
 *       other client commands at the start of a statement.</li>
 * </ul>
 * The delimiter is ignored inside strings, quoted identifiers and comments; the delimiter itself is
 * not part of the unit unless it is {@code ;}.
 *
 * <p>Amber is more forgiving than the client in one case: stored programs pasted without
 * {@code DELIMITER} (for example from {@code SHOW CREATE PROCEDURE}). With the {@code ;} delimiter,
 * a {@code ;} inside the {@code BEGIN … END} of a stored program does not end it.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class MySqlSplitter implements ScriptSplitter {

    /** Client commands; none of these words starts a SQL statement in MySQL or MariaDB. */
    private static final Set<String> CLIENT_COMMANDS = Set.of("DELIMITER", "SOURCE", "CONNECT", "TEE", "NOTEE", "USE",
            "PROMPT", "WARNINGS", "NOWARNING", "REHASH", "CHARSET", "EGO", "PAGER", "NOPAGER", "PRINT", "STATUS",
            "SYSTEM", "EXIT", "QUIT");

    @Override
    public List<StatementUnit> split(SourceText source) {
        SqlScanner sc = new SqlScanner(source.text(), ScannerOptions.MYSQL);
        UnitCollector units = new UnitCollector(source);
        String text = source.text();
        String delimiter = ";";

        ScanToken first;
        while ((first = sc.next()) != null) {
            if (sc.startsLine(first) && first.isWordIn(CLIENT_COMMANDS)) {
                int end = sc.lineEnd(first.start());
                if (first.isWord("DELIMITER")) {
                    String value = text.substring(first.end(), end).strip();
                    if (!value.isEmpty()) {
                        delimiter = value.split("\\s+")[0];
                    }
                }
                units.add(UnitKind.CLIENT_COMMAND, first.start(), end);
                sc.reset(end);
                continue;
            }
            if (first.isSymbol('\\') && isClientCommand(text, first.end())) {
                int end = sc.lineEnd(first.start());
                units.add(UnitKind.CLIENT_COMMAND, first.start(), end);
                sc.reset(end);
                continue;
            }
            int unitStart = sc.versionedOpenerBefore() >= 0 ? sc.versionedOpenerBefore() : first.start();
            int end = first.start();
            String terminator = null;
            BlockDepth body = delimiter.equals(";") && isCompound(sc, first) ? new BlockDepth() : null;
            ScanToken t = first;
            while (t != null) {
                int at = delimiterIn(text, t, delimiter);
                if (at >= 0 && body != null && body.inBlock()) {
                    at = -1;
                }
                if (body != null) {
                    body.accept(t, sc.peek());
                }
                if (at >= 0) {
                    if (delimiter.equals(";")) {
                        end = at + 1;
                    } else {
                        end = at + delimiter.length();      // keep the delimiter; the frontend turns it into ';'
                        terminator = delimiter;
                    }
                    sc.reset(at + delimiter.length());
                    break;
                }
                if (t.isSymbol('\\') && isGo(text, t.end())) {
                    end = t.end() + 1;                      // \g or \G ends the statement
                    terminator = text.substring(t.start(), end);
                    sc.reset(end);
                    break;
                }
                if (t.isSymbol('\\') && t != first && isClientCommand(text, t.end())) {
                    sc.reset(t.start());                    // another client command (\. file, \c …) ends it too
                    break;
                }
                end = t.end();
                t = sc.next();
            }
            units.add(UnitKind.SQL, unitStart, end, terminator);
        }
        return units.units();
    }

    /** Letters of the mysql client's backslash commands; {@code \\N} (upper case) is the NULL literal instead. */
    private static final String COMMAND_CHARS = "?cdeghnPpqRr.s!TtuCWwl";

    private static boolean isClientCommand(String text, int afterBackslash) {
        return afterBackslash < text.length() && COMMAND_CHARS.indexOf(text.charAt(afterBackslash)) >= 0;
    }

    private static final Set<String> PROGRAMS = Set.of("PROCEDURE", "FUNCTION", "TRIGGER", "EVENT");

    /**
     * True for stored programs ({@code CREATE … PROCEDURE/FUNCTION/TRIGGER/EVENT}, {@code ALTER … EVENT})
     * and anonymous compound statements ({@code BEGIN [NOT ATOMIC] …}, but not {@code BEGIN;} or
     * {@code BEGIN WORK}, which start a transaction).
     */
    private static boolean isCompound(SqlScanner sc, ScanToken first) {
        int saved = sc.position();
        try {
            if (first.isWord("BEGIN")) {
                ScanToken next = sc.next();
                return next != null && !next.isSymbol(';') && !next.isWord("WORK");
            }
            if (!first.isWord("CREATE") && !first.isWord("ALTER")) {
                return false;
            }
            ScanToken t;
            for (int i = 0; i < 16 && (t = sc.next()) != null; i++) {
                if (t.isWordIn(PROGRAMS)) {
                    return true;
                }
                if (t.isSymbol(';')) {
                    return false;
                }
            }
            return false;
        } finally {
            sc.reset(saved);
        }
    }

    /**
     * Offset of the delimiter inside {@code t}, or -1. Like the mysql client, the delimiter is found
     * inside words too ({@code end$$}), but never inside strings or quoted identifiers.
     */
    private static int delimiterIn(String text, ScanToken t, String delimiter) {
        if (t.type() == ScanToken.Type.STRING || t.type() == ScanToken.Type.QUOTED_IDENTIFIER
                || t.type() == ScanToken.Type.UNTERMINATED) {
            return -1;
        }
        for (int at = t.start(); at < t.end(); at++) {         // only inside the token: indexOf would scan the rest
            if (text.startsWith(delimiter, at)) {              // of the text for every token of a long statement
                return at;
            }
        }
        return -1;
    }

    private static boolean isGo(String text, int offset) {
        return offset < text.length() && (text.charAt(offset) == 'g' || text.charAt(offset) == 'G')
                && (offset + 1 >= text.length() || !Character.isLetterOrDigit(text.charAt(offset + 1)));
    }
}
