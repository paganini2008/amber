package com.github.amber.dialect.db2;

import org.junit.jupiter.api.Test;

import static com.github.amber.dialect.SplitAssertions.assertSplit;

/**
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class Db2SplitterTest {

    private final Db2Splitter splitter = new Db2Splitter();

    @Test
    void detectsAtTerminator() {
        assertSplit(splitter, """
                create procedure p()
                begin
                  update t set a = 1;
                end@
                call p()@
                """,
                """
                SQL: create procedure p()
                begin
                  update t set a = 1;
                end""",
                "SQL: call p()");
    }

    @Test
    void honoursTerminatorDirective() {
        assertSplit(splitter, """
                select 1 from sysibm.sysdummy1;
                --#SET TERMINATOR !
                create trigger trg after insert on t for each row
                begin atomic
                  insert into log values (1);
                end!
                """,
                "SQL: select 1 from sysibm.sysdummy1;",
                """
                SQL: create trigger trg after insert on t for each row
                begin atomic
                  insert into log values (1);
                end""");
    }

    @Test
    void semicolonTerminatorRespectsCompoundStatements() {
        assertSplit(splitter, """
                create procedure p()
                begin
                  if 1 = 1 then
                    update t set a = case when b > 0 then 1 else 0 end;
                  end if;
                end;
                select 1 from sysibm.sysdummy1;
                """,
                """
                SQL: create procedure p()
                begin
                  if 1 = 1 then
                    update t set a = case when b > 0 then 1 else 0 end;
                  end if;
                end;""",
                "SQL: select 1 from sysibm.sysdummy1;");
    }
}
