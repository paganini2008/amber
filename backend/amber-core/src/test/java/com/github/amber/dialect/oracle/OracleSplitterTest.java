package com.github.amber.dialect.oracle;

import org.junit.jupiter.api.Test;

import static com.github.amber.dialect.SplitAssertions.assertSplit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class OracleSplitterTest {

    private final OracleSplitter splitter = new OracleSplitter();

    @Test
    void splitsSqlAtSemicolonAndSkipsSlash() {
        assertSplit(splitter, """
                drop table t;
                create table t as select 1 a from dual;
                select * from t
                /
                """,
                "SQL: drop table t;",
                "SQL: create table t as select 1 a from dual;",
                "SQL: select * from t");
    }

    @Test
    void keepsPlsqlBlocksWholeUntilSlash() {
        assertSplit(splitter, """
                create or replace procedure p is
                  v number := 10 / 2;
                begin
                  update t set a = 1;
                  commit;
                end;
                /
                begin p; end;
                /
                select 1 from dual;
                """,
                """
                SQL: create or replace procedure p is
                  v number := 10 / 2;
                begin
                  update t set a = 1;
                  commit;
                end;""",
                "SQL: begin p; end;",
                "SQL: select 1 from dual;");
    }

    @Test
    void recognizesSqlPlusCommands() {
        assertSplit(splitter, """
                set serveroutput on
                prompt Don't stop; really
                define batch_day=trunc(sysdate) - 1
                rem it's a remark; ok
                @@other_script.sql
                set transaction read only;
                exec p(1);
                """,
                "CLIENT_COMMAND: set serveroutput on",
                "CLIENT_COMMAND: prompt Don't stop; really",
                "CLIENT_COMMAND: define batch_day=trunc(sysdate) - 1",
                "CLIENT_COMMAND: rem it's a remark; ok",
                "CLIENT_COMMAND: @@other_script.sql",
                "SQL: set transaction read only;",
                "CLIENT_COMMAND: exec p(1);");
    }

    @Test
    void ignoresTerminatorsInStringsCommentsAndQQuotes() {
        assertSplit(splitter, """
                select 'a;b', q'[c;d]', "x;y" /* ; */ from dual -- ;
                where 1 = 1;
                """,
                """
                SQL: select 'a;b', q'[c;d]', "x;y" /* ; */ from dual -- ;
                where 1 = 1;""");
    }

    @Test
    void createTypeIsAPlsqlUnit() {
        assertSplit(splitter, """
                create or replace type t_obj as object (a number);
                /
                create or replace editionable type body t_obj as
                  member function f return number is begin return 1; end;
                end;
                /
                """,
                "SQL: create or replace type t_obj as object (a number);",
                """
                SQL: create or replace editionable type body t_obj as
                  member function f return number is begin return 1; end;
                end;""");
    }

    @Test
    void masksSubstitutionVariablesKeepingLength() {
        String sql = "select add_months(&start_day, 1), '&in_string', &&twice, tab_&sfx..col from dual where x = &1";
        String masked = SubstitutionVariables.mask(sql);
        assertEquals(sql.length(), masked.length());
        assertEquals("select add_months(Xstart_day, 1), '&in_string', XXtwice, tab_Xsfx .col from dual where x = X1", masked);
    }

    @Test
    void withFunctionEndsAtSlashAndHostShortcuts() {
        assertSplit(splitter, """
                with function twice(n number) return number is begin return n * 2; end;
                select twice(3) from dual
                /
                !ls -l
                select 1 from dual;
                """,
                """
                SQL: with function twice(n number) return number is begin return n * 2; end;
                select twice(3) from dual""",
                "CLIENT_COMMAND: !ls -l",
                "SQL: select 1 from dual;");
    }
}
