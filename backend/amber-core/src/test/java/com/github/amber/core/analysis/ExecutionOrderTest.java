package com.github.amber.core.analysis;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The logical processing order of statements: clause phases, nested queries before the phase that uses
 * them, set operations after their branches, the write last.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class ExecutionOrderTest {

    private static Statement statement(Dialect dialect, String sql) {
        DialectFrontend f = DialectFrontends.get(dialect);
        return ScriptIrBuilder.build(f, f.parse(SourceText.of(sql))).statements().getFirst();
    }

    private static List<String> steps(Statement st, String sql) {
        return st.executionOrder().stream().map(s -> "  ".repeat(s.depth()) + s.phase() + ": "
                + sql.substring(s.range().start().offset(), s.range().end().offset()).replaceAll("\\s+", " ")).toList();
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"ORACLE", "POSTGRESQL", "MYSQL", "MARIADB", "DB2", "H2", "SQLITE"})
    void clausesRunInLogicalOrder(Dialect dialect) {
        String sql = "select a, count(*) from t join u on u.id = t.id where t.x > 1 group by a having count(*) > 2 order by a;";
        List<String> phases = statement(dialect, sql).executionOrder().stream().map(ExecutionOrder.Step::phase).toList();
        assertEquals(List.of("FROM", "WHERE", "GROUP BY", "HAVING", "SELECT", "ORDER BY"), phases, dialect::toString);
    }

    @Test
    void nestedQueriesRunBeforeThePhaseThatUsesThem() {
        String sql = """
                insert into r (id, total)
                with paid as (select customer_id, amount from orders where status = 'PAID')
                select c.id, (select sum(p.amount) from paid p where p.customer_id = c.id)
                  from customer c join (select region_id from region) g on g.region_id = c.region_id
                 where c.id in (select customer_id from paid)
                union all
                select 0, 0 from dual;""";
        List<String> s = steps(statement(Dialect.ORACLE, sql), sql);
        assertEquals(List.of(
                "FROM: from orders",
                "WHERE: where status = 'PAID'",
                "SELECT: select customer_id, amount",
                "CTE: paid as (select customer_id, amount from orders where status = 'PAID')",
                "    FROM: from region",
                "    SELECT: select region_id",
                "  FROM: from customer c join (select region_id from region) g on g.region_id = c.region_id",
                "    FROM: from paid",
                "    SELECT: select customer_id",
                "  WHERE: where c.id in (select customer_id from paid)",
                "    FROM: from paid p",
                "    WHERE: where p.customer_id = c.id",
                "    SELECT: select sum(p.amount)",
                "  SELECT: select c.id, (select sum(p.amount) from paid p where p.customer_id = c.id)",
                "  FROM: from dual",
                "  SELECT: select 0, 0"), s.subList(0, 16), () -> String.join("\n", s));
        assertEquals("UNION ALL", statement(Dialect.ORACLE, sql).executionOrder().get(16).phase());
        assertTrue(s.getLast().startsWith("WRITE: insert into r"), s::toString);
        assertEquals("Insert into r", statement(Dialect.ORACLE, sql).executionOrder().getLast().detail());
    }

    @Test
    void rowLimitsComeLast() {
        String tsql = "select distinct top 5 a from t where b = 1 order by a;";
        assertEquals(List.of("FROM: from t", "WHERE: where b = 1", "SELECT: select distinct top 5 a", "DISTINCT: distinct",
                "ORDER BY: order by a", "TOP: top 5"), steps(statement(Dialect.SQLSERVER, tsql), tsql));
        String pg = "select a from t order by a limit 5 offset 10;";
        assertEquals(List.of("FROM", "SELECT", "ORDER BY", "OFFSET", "LIMIT"),
                statement(Dialect.POSTGRESQL, pg).executionOrder().stream().map(ExecutionOrder.Step::phase).toList());
        String win = "select a, rank() over (order by b) from t;";
        assertEquals(List.of("FROM", "SELECT"), statement(Dialect.H2, win).executionOrder().stream()
                .map(ExecutionOrder.Step::phase).toList(), "ORDER BY inside OVER (…) is not a clause of the query");
    }

    @Test
    void updateWithSubqueriesAndPlainStatements() {
        String sql = "update t set a = (select max(b) from s where s.id = t.id) where t.k in (select k from u);";
        List<String> phases = statement(Dialect.POSTGRESQL, sql).executionOrder().stream()
                .map(s -> s.depth() + s.phase()).toList();
        assertEquals(List.of("0FROM", "0SELECT", "0FROM", "0WHERE", "0SELECT", "0WRITE"), phases,
                "WHERE subqueries before SET subqueries, then the update");
        assertTrue(statement(Dialect.MYSQL, "create table t (a int);").executionOrder().isEmpty());
    }
}
