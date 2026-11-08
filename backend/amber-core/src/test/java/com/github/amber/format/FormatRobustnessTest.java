package com.github.amber.format;

import com.github.amber.core.Dialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions found by the mutation fuzzer ({@code server.MutationFuzzTest}).
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class FormatRobustnessTest {

    /**
     * A statement that follows another on the same line is moved to a new line, so it must be laid out
     * for column 0; it used to be laid out for its old column, and formatting the result again changed it.
     */
    @Test
    void statementAfterAnotherOnTheSameLineIsIdempotent() {
        String items = "first_rather_long_column_name, second_rather_long_column_name, third_long_name";
        String text = "UPDATE t SET a = 1 END LOOP; SELECT " + items + " FROM some_table WHERE x = 1;\n";
        for (Dialect d : new Dialect[]{Dialect.H2, Dialect.ORACLE, Dialect.POSTGRESQL}) {
            assertNull(FormatContract.check(d, text, FormatOptions.DEFAULTS), d::toString);
        }
        String out = Formatter.format(Dialect.H2, text).text();
        assertTrue(out.contains("\nSELECT " + items + "\n"), out);
    }
}
