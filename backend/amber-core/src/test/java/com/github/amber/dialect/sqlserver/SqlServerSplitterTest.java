package com.github.amber.dialect.sqlserver;

import org.junit.jupiter.api.Test;

import static com.github.amber.dialect.SplitAssertions.assertSplit;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class SqlServerSplitterTest {

    private final SqlServerSplitter splitter = new SqlServerSplitter();

    @Test
    void splitsBatchesAtGo() {
        assertSplit(splitter, """
                use sales
                go
                create procedure dbo.p as
                begin
                  select 'go' as [go]; -- go
                  /* go */
                end
                GO 2
                select @@rowcount
                """,
                "SQL: use sales",
                """
                SQL: create procedure dbo.p as
                begin
                  select 'go' as [go]; -- go
                  /* go */
                end""",
                "SQL: select @@rowcount");
    }

    @Test
    void goMustBeAloneOnItsLine() {
        assertSplit(splitter, """
                select go from t go_on
                go
                """,
                "SQL: select go from t go_on");
    }

    @Test
    void recognizesSqlCmdCommands() {
        assertSplit(splitter, """
                :setvar db sales
                :on error exit
                select 1
                """,
                "CLIENT_COMMAND: :setvar db sales",
                "CLIENT_COMMAND: :on error exit",
                "SQL: select 1");
    }

    @Test
    void shellAndExitCommands() {
        assertSplit(splitter, """
                !! dir
                select 1
                go
                exit
                """,
                "CLIENT_COMMAND: !! dir",
                "SQL: select 1",
                "CLIENT_COMMAND: exit");
    }
}
