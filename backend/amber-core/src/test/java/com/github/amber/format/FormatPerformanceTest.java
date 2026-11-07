package com.github.amber.format;

import com.github.amber.core.Dialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 5,000-line script must format well under two seconds.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class FormatPerformanceTest {

    private static String script(int lines) {
        String[] block = {
                "-- step %d",
                "select a.id, a.name, b.total, case when b.total > 100 then 'big' else 'small' end size_class",
                "from accounts a join (select account_id, sum(amount) total from ledger group by account_id) b",
                "on b.account_id = a.id where a.status = 'OPEN' and a.created > date '2020-01-01' order by 1;",
                "update accounts set status = 'CLOSED', closed_at = current_timestamp where id in (1, 2, 3);",
                "insert into audit (id, msg) values (%d, 'step; -- %d');",
                "",
        };
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (n < lines) {
            for (String line : block) {
                sb.append(String.format(line, n, n, n)).append('\n');
                n++;
            }
        }
        return sb.toString();
    }

    @Test
    void fiveThousandLinesFormatQuickly() {
        String text = script(5000);
        Formatter.format(Dialect.ORACLE, script(200));          // warm up class loading and the JIT a little
        long started = System.nanoTime();
        FormatResult result = Formatter.format(Dialect.ORACLE, text);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(0, result.warnings().size());
        assertTrue(millis < 2000, "took " + millis + " ms");

        String tsql = text.replace(";", "").replace("-- step", "GO\n-- step");
        started = System.nanoTime();
        result = Formatter.format(Dialect.SQLSERVER, tsql);
        millis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(0, result.warnings().size());
        assertTrue(millis < 2000, "took " + millis + " ms");
    }
}
