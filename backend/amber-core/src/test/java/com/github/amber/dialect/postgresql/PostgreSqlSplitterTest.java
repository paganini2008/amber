package com.github.amber.dialect.postgresql;

import org.junit.jupiter.api.Test;

import static com.github.amber.dialect.SplitAssertions.assertSplit;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class PostgreSqlSplitterTest {

    private final PostgreSqlSplitter splitter = new PostgreSqlSplitter();

    @Test
    void keepsDollarQuotedBodiesWhole() {
        assertSplit(splitter, """
                create function f() returns int language plpgsql as $body$
                begin
                  insert into t values (1);
                  return $$;$$::text::int;
                end;
                $body$;
                select f();
                """,
                """
                SQL: create function f() returns int language plpgsql as $body$
                begin
                  insert into t values (1);
                  return $$;$$::text::int;
                end;
                $body$;""",
                "SQL: select f();");
    }

    @Test
    void keepsBeginAtomicBodiesWhole() {
        assertSplit(splitter, """
                create function g(a int) returns int language sql
                begin atomic
                  select case when a > 0 then 1 else 0 end;
                end;
                select 2;
                """,
                """
                SQL: create function g(a int) returns int language sql
                begin atomic
                  select case when a > 0 then 1 else 0 end;
                end;""",
                "SQL: select 2;");
    }

    @Test
    void metaCommandAfterQueryEndsIt() {
        assertSplit(splitter, """
                select current_database() as db \\gset
                select 1;
                """,
                "SQL: select current_database() as db",
                "CLIENT_COMMAND: \\gset",
                "SQL: select 1;");
    }

    @Test
    void handlesPsqlCommandsAndCopyData() {
        assertSplit(splitter, """
                \\set ON_ERROR_STOP on
                copy t (a, b) from stdin;
                1\t'x;y'
                2\tz
                \\.
                select E'it\\'s;', $1 from t;
                """,
                "CLIENT_COMMAND: \\set ON_ERROR_STOP on",
                "SQL: copy t (a, b) from stdin;",
                "DATA: 1\t'x;y'\n2\tz\n\\.",
                "SQL: select E'it\\'s;', $1 from t;");
    }
}
