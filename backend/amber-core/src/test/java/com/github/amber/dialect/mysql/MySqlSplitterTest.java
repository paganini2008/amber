package com.github.amber.dialect.mysql;

import org.junit.jupiter.api.Test;

import static com.github.amber.dialect.SplitAssertions.assertSplit;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class MySqlSplitterTest {

    private final MySqlSplitter splitter = new MySqlSplitter();

    @Test
    void followsDelimiterChanges() {
        assertSplit(splitter, """
                drop procedure if exists p;
                DELIMITER $$
                create procedure p()
                begin
                  insert into t values ('a;b');
                  select `x;y` from t; # comment ;
                end$$
                DELIMITER ;
                call p();
                """,
                "SQL: drop procedure if exists p;",
                "CLIENT_COMMAND: DELIMITER $$",
                """
                SQL: create procedure p()
                begin
                  insert into t values ('a;b');
                  select `x;y` from t; # comment ;
                end$$""",
                "CLIENT_COMMAND: DELIMITER ;",
                "SQL: call p();");
    }

    @Test
    void handlesBackslashEscapesAndGoTerminator() {
        assertSplit(splitter, """
                select 'it\\'s; fine' from dual\\G
                source other.sql
                select 2;
                """,
                "SQL: select 'it\\'s; fine' from dual\\G",
                "CLIENT_COMMAND: source other.sql",
                "SQL: select 2;");
    }

    @Test
    void storedProgramsWithoutDelimiterStayWhole() {
        assertSplit(splitter, """
                create definer = current_user procedure p(a int)
                begin
                  if a > 0 then
                    update t set b = 1;
                  end if;
                end;
                begin;
                select 5--1;
                """,
                """
                SQL: create definer = current_user procedure p(a int)
                begin
                  if a > 0 then
                    update t set b = 1;
                  end if;
                end;""",
                "SQL: begin;",
                "SQL: select 5--1;");
    }

    @Test
    void versionedCommentsAndClientCommandsInMysqldumpStyle() {
        assertSplit(splitter, """
                /*!40101 SET NAMES utf8mb4 */;
                use shop
                select 1 \\. other.sql
                select 2;
                """,
                "SQL: /*!40101 SET NAMES utf8mb4 */;",
                "CLIENT_COMMAND: use shop",
                "SQL: select 1",
                "CLIENT_COMMAND: \\. other.sql",
                "SQL: select 2;");
        var parse = com.github.amber.dialect.DialectFrontends.get(com.github.amber.core.Dialect.MYSQL)
                .parse(com.github.amber.core.source.SourceText.of("/*!40101 SET NAMES utf8mb4 */;\n/*!40014 SET UNIQUE_CHECKS=0 */;\n"));
        org.junit.jupiter.api.Assertions.assertTrue(parse.success(), parse.diagnostics()::toString);
    }
}
