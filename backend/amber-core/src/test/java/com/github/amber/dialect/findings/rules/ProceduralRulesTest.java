package com.github.amber.dialect.findings.rules;

import com.github.amber.core.Dialect;
import com.github.amber.dialect.findings.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.dialect.findings.rules.RulesTestSupport.line;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.none;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.one;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AMB-P004 statements that can be combined, AMB-P005 cursor loops that can be one statement, AMB-P008
 * COUNT used to test existence.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class ProceduralRulesTest {

    @Test
    void consecutiveUpdatesOfTheSameRows() {
        String sql = """
                update stock set qty = 0 where wh = 'A';
                update stock set flag = 'Z' where wh = 'A';
                """;
        for (Dialect d : List.of(Dialect.ORACLE, Dialect.SQLSERVER, Dialect.POSTGRESQL, Dialect.MYSQL)) {
            Finding f = one(d, sql, "AMB-P004");
            assertEquals(2, line(f));
            assertNotNull(f.suggested(), d::toString);
            assertTrue(f.suggested().sql().startsWith("update stock set qty = 0, flag = 'Z' where wh = 'A'"),
                    () -> f.suggested().sql());
        }
        none(Dialect.ORACLE, "update stock set qty = 0 where wh = 'A';\nupdate stock set flag = 'Z' where wh = 'B';",
                "AMB-P004");
        none(Dialect.ORACLE, "update stock set qty = 0 where wh = 'A';\nupdate stock set flag = qty where wh = 'A';",
                "AMB-P004");
        none(Dialect.ORACLE, "update stock set wh = 'B' where wh = 'A';\nupdate stock set flag = 1 where wh = 'A';",
                "AMB-P004");
        none(Dialect.ORACLE, "update stock set qty = 0 where wh = 'A';\ncommit;\nupdate stock set flag = 1 where wh = 'A';",
                "AMB-P004");
        none(Dialect.POSTGRESQL, "update stock set qty = 0 where wh = 'A' returning id;\n"
                + "update stock set flag = 1 where wh = 'A';", "AMB-P004");
    }

    @Test
    void updateAndInsertWhereNotExistsIsAnUpsert() {
        String sql = """
                update dim_c d set name = (select s.name from stg_c s where s.id = d.id)
                 where exists (select 1 from stg_c s where s.id = d.id);
                insert into dim_c (id, name)
                select s.id, s.name from stg_c s where not exists (select 1 from dim_c d where d.id = s.id);
                """;
        Finding f = one(Dialect.ORACLE, sql, "AMB-P004");
        assertTrue(f.suggestion().contains("MERGE"), f::suggestion);
        assertNull(f.suggested());
        assertTrue(one(Dialect.MYSQL, sql, "AMB-P004").suggestion().contains("ON DUPLICATE KEY"));
        assertTrue(one(Dialect.POSTGRESQL, sql, "AMB-P004").suggestion().contains("ON CONFLICT"));
        none(Dialect.ORACLE, sql.replace("from dim_c d where d.id = s.id", "from other_t d where d.id = s.id"),
                "AMB-P004");
        none(Dialect.DB2, """
                update dim_c set is_current = 0 where id in (select id from stg_c);
                insert into dim_c (id, name, is_current)
                select s.id, s.name, 1 from stg_c s
                 where not exists (select 1 from dim_c d where d.id = s.id and d.is_current = 1);
                """, "AMB-P004");
    }

    @Test
    void oracleCursorForLoops() {
        String sql = """
                declare
                  cursor c1 is select id, amt from src where flag = 'Y';
                begin
                  for r in (select id, amt from src) loop
                    update tgt set amt = r.amt where id = r.id;
                  end loop;
                  for r in c1 loop
                    insert into tgt (id, amt) values (r.id, r.amt);
                  end loop;
                  for r in (select id from src) loop
                    update tgt set amt = 0 where id = r.id;
                    commit;
                  end loop;
                end;
                /
                """;
        List<Finding> p005 = rule(Dialect.ORACLE, sql, "AMB-P005");
        assertEquals(2, p005.size(), p005::toString);
        Finding merge = p005.get(0);
        assertTrue(merge.suggestion().contains("MERGE INTO tgt USING (select id, amt from src) r ON (id = r.id) WHEN "
                + "MATCHED THEN UPDATE SET amt = r.amt"), merge::suggestion);
        assertNotNull(merge.suggested());
        assertEquals("REWRITE_OF_STATEMENT", merge.suggested().kind().name());
        assertTrue(p005.get(1).suggestion().contains("insert into tgt (id, amt) SELECT r.id, r.amt FROM (select id, "
                + "amt from src where flag = 'Y') r"), () -> p005.get(1).suggestion());
    }

    @Test
    void postgresAndDb2ForLoops() {
        String pg = """
                do $$
                declare r record;
                begin
                  for r in select id from src loop
                    delete from tgt where tgt.id = r.id;
                  end loop;
                end $$;
                """;
        Finding f = one(Dialect.POSTGRESQL, pg, "AMB-P005");
        assertTrue(f.suggestion().contains("delete from tgt WHERE EXISTS (SELECT 1 FROM (select id from src) r WHERE "
                + "tgt.id = r.id)"), f::suggestion);
        String db2 = """
                create procedure p()
                begin
                  for r as select id, amt from src do
                    update tgt set amt = r.amt where tgt.id = r.id;
                  end for;
                end
                """;
        Finding d = one(Dialect.DB2, db2, "AMB-P005");
        assertTrue(d.suggestion().contains("MERGE INTO tgt USING"), d::suggestion);
    }

    @Test
    void fetchLoops() {
        String tsql = """
                declare @id int, @amt int;
                declare c cursor for select id, amount as amt from src;
                open c;
                fetch next from c into @id, @amt;
                while @@fetch_status = 0
                begin
                  update tgt set amt = @amt where id = @id;
                  fetch next from c into @id, @amt;
                end;
                close c;
                deallocate c;
                """;
        Finding f = one(Dialect.SQLSERVER, tsql, "AMB-P005");
        assertTrue(f.suggestion().contains("UPDATE tgt SET amt = src.amt FROM tgt JOIN (select id, amount as amt from "
                + "src) src ON id = src.id") || f.suggestion().contains("UPDATE tgt SET amt = cur.amt"), f::suggestion);
        String mysql = """
                create procedure p()
                begin
                  declare done int default 0;
                  declare v_id int;
                  declare c cursor for select id from src;
                  declare continue handler for not found set done = 1;
                  open c;
                  read_loop: loop
                    fetch c into v_id;
                    if done then leave read_loop; end if;
                    update tgt set flag = 1 where tgt.id = v_id;
                  end loop;
                  close c;
                end;
                """;
        Finding m = one(Dialect.MYSQL, mysql, "AMB-P005");
        assertTrue(m.suggestion().contains("JOIN (select id from src)"), m::suggestion);
    }

    @Test
    void loopsThatAreNotCursorLoopsAreQuiet() {
        none(Dialect.ORACLE, """
                begin
                  for i in 1..10 loop
                    insert into t (n) values (i);
                  end loop;
                end;
                /
                """, "AMB-P005");
        none(Dialect.ORACLE, """
                begin
                  for r in (select id from src) loop
                    insert into t (n) values (1);
                  end loop;
                end;
                /
                """, "AMB-P005");
        none(Dialect.ORACLE, """
                -- amber:ignore AMB-P005
                begin
                  for r in (select id from src) loop
                    update t set n = 1 where id = r.id returning n into x;
                  end loop;
                end;
                /
                """, "AMB-P005");
    }

    @Test
    void countToTestExistence() {
        Finding f = one(Dialect.POSTGRESQL, "select * from a where (select count(*) from b where b.k = a.k) > 0;",
                "AMB-P008");
        assertEquals("select * from a where EXISTS (SELECT 1 from b where b.k = a.k)", f.suggested().sql());
        Finding n = one(Dialect.ORACLE, "select * from a where (select count(1) from b where b.k = a.k) = 0;",
                "AMB-P008");
        assertTrue(n.suggestion().startsWith("Use NOT EXISTS"), n::suggestion);
        Finding t = one(Dialect.SQLSERVER, "if (select count(*) from tgt where id = 1) > 0 print 'x';", "AMB-P008");
        assertNull(t.suggested());
        none(Dialect.POSTGRESQL, "select * from a where (select count(*) from b where b.k = a.k) > 2;", "AMB-P008");
        none(Dialect.POSTGRESQL, "select * from a where (select count(*) from b group by b.x) > 0;", "AMB-P008");
    }

    @Test
    void countIntoAVariableThenIf() {
        String plsql = """
                declare
                  n number;
                begin
                  select count(*) into n from tgt where id = 1;
                  if n > 0 then
                    update tgt set flag = 1 where id = 1;
                  end if;
                end;
                /
                """;
        Finding f = one(Dialect.ORACLE, plsql, "AMB-P008");
        assertEquals(4, line(f));
        none(Dialect.ORACLE, plsql.replace("set flag = 1", "set flag = n"), "AMB-P008");
        String tsql = """
                create procedure p @id int as
                begin
                  declare @n int;
                  select @n = count(*) from tgt where id = @id;
                  if @n > 0 update tgt set a = 1 where id = @id;
                end
                """;
        one(Dialect.SQLSERVER, tsql, "AMB-P008");
        String pg = """
                do $$
                declare n int;
                begin
                  select count(*) into n from tgt where id = 1;
                  if n = 0 then
                    raise notice 'none';
                  end if;
                end $$;
                """;
        assertTrue(one(Dialect.POSTGRESQL, pg, "AMB-P008").suggestion().startsWith("Use NOT EXISTS"));
    }
}
