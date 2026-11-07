package com.github.amber.dialect.structure;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.QueryBlock;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nested query structure (CTEs, set operations, derived tables, subqueries) is the same in every
 * dialect for the same query.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class QueryStructureTest {

    private static final String QUERY = """
            with paid as (select customer_id, amount from sales.orders where status = 'PAID'),
                 large_c as (select customer_id from paid group by customer_id having sum(amount) > 100)
            select c.id, c.label, (select sum(p.amount) from paid p where p.customer_id = c.id) total
              from crm.customer c
              join (select region_id, max(lvl) lvl from crm.region group by region_id) r on r.region_id = c.region_id
             where c.id in (select customer_id from large_c)
               and not exists (select 1 from crm.blocked b where b.customer_id = c.id)
            union all
            select 0, 'none', 0 from crm.customer
            """;

    private static List<QueryBlock> structure(Dialect dialect, String sql) {
        DialectFrontend f = DialectFrontends.get(dialect);
        ScriptParse parse = f.parse(SourceText.of(sql));
        assertEquals(ScriptStatus.SUCCESS, parse.status(), () -> parse.diagnostics().toString());
        Script script = ScriptIrBuilder.build(f, parse);
        Statement st = script.statements().get(0);
        return st.structure();
    }

    private static String shape(QueryBlock b) {
        StringBuilder sb = new StringBuilder(b.kind().name());
        if (b.name() != null) {
            sb.append(' ').append(b.name());
        }
        if (b.role() != null) {
            sb.append(" [").append(b.role()).append(']');
        }
        sb.append(' ').append(b.sources().stream().map(s -> s.name() + (s.alias() == null ? "" : "/" + s.alias())).toList());
        sb.append(' ').append(b.clauses());
        if (!b.children().isEmpty()) {
            sb.append(" {").append(String.join("; ", b.children().stream().map(QueryStructureTest::shape).toList())).append('}');
        }
        return sb.toString();
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void sameStructureInEveryDialect(Dialect dialect) {
        String sql = dialect == Dialect.SQLSERVER ? QUERY.replace("union all", "union all\n") : QUERY;
        List<QueryBlock> s = structure(dialect, sql + ";");
        List<String> shapes = s.stream().map(QueryStructureTest::shape).toList();
        assertEquals(3, s.size(), shapes::toString);
        assertEquals("CTE paid [sales.orders] [WHERE]", shapes.get(0));
        assertEquals("CTE large_c [paid] [GROUP BY, HAVING]", shapes.get(1));
        QueryBlock set = s.get(2);
        assertEquals(QueryBlock.Kind.SET, set.kind());
        assertEquals("UNION ALL", set.name());
        assertEquals(2, set.children().size());
        QueryBlock main = set.children().get(0);
        assertEquals(List.of("JOIN", "WHERE"), main.clauses());
        assertEquals(List.of("id", "label", "total"), main.outputs());
        List<String> kids = main.children().stream().map(QueryStructureTest::shape).toList();
        assertEquals(4, kids.size(), kids::toString);
        assertEquals("SUBQUERY [SELECT scalar] [paid/p] [WHERE]", kids.get(0));
        assertEquals("DERIVED r [FROM] [crm.region] [GROUP BY]", kids.get(1));
        assertEquals("SUBQUERY [WHERE IN] [large_c] []", kids.get(2));
        assertEquals("SUBQUERY [WHERE NOT EXISTS] [crm.blocked/b] [WHERE]", kids.get(3));
    }

    @Test
    void dmlAndPlainQueries() {
        List<QueryBlock> upd = structure(Dialect.POSTGRESQL,
                "update t set a = (select max(b) from s where s.id = t.id) where t.k in (select k from u);");
        assertEquals(List.of("SUBQUERY [SET scalar] [s] [WHERE]", "SUBQUERY [WHERE IN] [u] []"),
                upd.stream().map(QueryStructureTest::shape).toList());

        List<QueryBlock> q = structure(Dialect.ORACLE,
                "select distinct a from t where x = 1 connect by prior id = pid start with pid is null order by a;");
        assertEquals(1, q.size());
        assertEquals(List.of("DISTINCT", "WHERE", "START WITH", "CONNECT BY", "ORDER BY"), q.get(0).clauses());

        assertTrue(structure(Dialect.MYSQL, "create table t (a int);").isEmpty());

        List<QueryBlock> rootClauses = structure(Dialect.POSTGRESQL, "select a from t where a > 0 order by a limit 5;");
        assertEquals(List.of("WHERE", "ORDER BY", "LIMIT"), rootClauses.get(0).clauses(), "clauses on the query root count");

        List<QueryBlock> joins = structure(Dialect.SQLSERVER,
                "select top 5 a.x from a join b on a.id = b.id left join c on c.id = b.id;");
        assertEquals(List.of("TOP", "JOIN ×2"), joins.get(0).clauses());
    }
}
