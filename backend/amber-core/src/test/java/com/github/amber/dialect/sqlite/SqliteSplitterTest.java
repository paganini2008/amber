package com.github.amber.dialect.sqlite;

import org.junit.jupiter.api.Test;

import static com.github.amber.dialect.SplitAssertions.assertSplit;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class SqliteSplitterTest {

    private final SqliteSplitter splitter = new SqliteSplitter();

    @Test
    void keepsTriggerBodiesWhole() {
        assertSplit(splitter, """
                .mode csv
                begin transaction;
                create temp trigger trg after insert on t
                begin
                  update t set b = case when new.a > 0 then 1 else 0 end;
                  insert into [log] values (new.a);
                end;
                commit;
                """,
                "CLIENT_COMMAND: .mode csv",
                "SQL: begin transaction;",
                """
                SQL: create temp trigger trg after insert on t
                begin
                  update t set b = case when new.a > 0 then 1 else 0 end;
                  insert into [log] values (new.a);
                end;""",
                "SQL: commit;");
    }
}
