package com.github.amber.dialect.split;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontends;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting must stay linear on very long lines (generated INSERT … VALUES dumps, minified scripts):
 * line-start checks walk back over blanks only, and the MySQL delimiter search stays inside the token.
 * Before the fix a 1 MB single-line statement took about 6 s to split in MySQL.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class LongLineSplitTest {

    /** Generous bound per dialect and input; linear splitting takes well under 100 ms here. */
    private static final long BOUND_MS = 1_500;

    @Test
    void oneMegabyteLinesSplitInLinearTime() {
        StringBuilder values = new StringBuilder("INSERT INTO log_t (id, msg, ratio) VALUES ");
        for (int i = 0; values.length() < 1_000_000; i++) {
            values.append(i == 0 ? "" : ", ").append('(').append(i).append(", 'm ").append(i).append("', ").append(i)
                    .append(" / 2)");
        }
        String oneStatement = values.append(";\n").toString();
        String manyStatements = "SELECT a / 2 FROM t WHERE b = 'x'; ".repeat(30_000) + "\n";
        for (Dialect d : Dialect.values()) {
            ScriptSplitter splitter = DialectFrontends.get(d).splitter();
            splitter.split(SourceText.of("select 1;"));
            for (String text : List.of(oneStatement, manyStatements)) {
                long t0 = System.nanoTime();
                List<StatementUnit> units = splitter.split(SourceText.of(text));
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(ms < BOUND_MS, d + ": " + ms + " ms for a " + text.length() + "-char line");
                assertTrue(!units.isEmpty(), d::toString);
            }
        }
    }

    @Test
    void lineStartAndRestOfLineKeepTheirMeaning() {
        SqlScanner sc = new SqlScanner("select 1\n  GO -- done\nx GO\r\n\tGO 5 # c", ScannerOptions.MYSQL);
        ScanToken t;
        int starts = 0;
        int empty = 0;
        while ((t = sc.next()) != null) {
            if (t.isWord("GO") && sc.startsLine(t)) {
                starts++;
                if (sc.restOfLineEmpty(t.end())) {
                    empty++;
                }
            }
        }
        assertEquals(2, starts);
        assertEquals(1, empty);
        SqlScanner first = new SqlScanner("GO", ScannerOptions.SQLSERVER);
        ScanToken go = first.next();
        assertTrue(first.startsLine(go) && first.restOfLineEmpty(go.end()));
    }
}
