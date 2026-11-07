package com.github.amber.dialect.h2;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SemicolonSplitter;
import org.junit.jupiter.api.Test;

import static com.github.amber.dialect.SplitAssertions.assertSplit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class H2SplitterTest {

    @Test
    void keepsDollarBodiesAndIgnoresSlashComments() {
        assertSplit(new SemicolonSplitter(ScannerOptions.H2), """
                create alias next_id as $$ long nextId(long x) { return x + 1; } $$;
                // a comment; with a semicolon
                /* nested /* comment; */ still comment; */
                select next_id(1);
                """,
                "SQL: create alias next_id as $$ long nextId(long x) { return x + 1; } $$;",
                "SQL: select next_id(1);");
    }

    @Test
    void h2ScriptsParse() {
        ScriptParse parse = DialectFrontends.get(Dialect.H2).parse(SourceText.of("""
                create table inventory (sku varchar(20) primary key, qty int default 0);
                merge into inventory key (sku) values ('A-1', 5);
                select sku, qty from inventory where qty > 0 order by sku fetch first 10 rows only;
                """));
        assertEquals(ScriptStatus.SUCCESS, parse.status(), () -> parse.diagnostics().toString());
        assertEquals(3, parse.units().size());
    }
}
