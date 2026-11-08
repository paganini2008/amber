package com.github.amber.dialect.findings.rules;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.findings.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.dialect.findings.rules.RulesTestSupport.none;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.one;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.rule;
import static com.github.amber.dialect.findings.rules.RulesTestSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AMB-P003 correlated subquery in the select list, AMB-P007 calculation on a compared column, AMB-P009
 * DISTINCT hiding a duplicating join.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class QueryShapeRulesTest {

    private static final String CORRELATED = "select o.id, (select sum(l.amount) from order_lines l "
            + "where l.order_id = o.id) as total from orders o";

    @Test
    void correlatedScalarSubqueries() {
        for (Dialect d : List.of(Dialect.ORACLE, Dialect.SQLSERVER, Dialect.POSTGRESQL, Dialect.MYSQL, Dialect.DB2,
                Dialect.H2, Dialect.SQLITE, Dialect.MARIADB)) {
            Finding f = one(d, CORRELATED + ";", "AMB-P003");
            assertTrue(f.message().contains("o.id"), f::message);
            assertEquals(Confidence.INFERRED, f.confidence());
        }
        none(Dialect.ORACLE, "select o.id, (select max(x.amount) from order_lines x) as m from orders o;", "AMB-P003");
        none(Dialect.SQLSERVER, "select top 10 o.id, (select sum(l.amount) from order_lines l where l.order_id = o.id) t "
                + "from orders o;", "AMB-P003");
        none(Dialect.POSTGRESQL, CORRELATED + " limit 5;", "AMB-P003");
        none(Dialect.ORACLE, "select o.id from orders o where exists (select 1 from order_lines l where l.order_id = o.id);",
                "AMB-P003");
        none(Dialect.ORACLE, "-- amber:ignore AMB-P003\n" + CORRELATED + ";", "AMB-P003");
    }

    @Test
    void arithmeticAndConcatenationOnColumns() {
        Finding f = one(Dialect.ORACLE, "select * from orders o where o.qty + 1 = 5;", "AMB-P007");
        assertEquals(Confidence.INFERRED, f.confidence());
        assertTrue(f.suggestion().contains("o.qty = 5 - 1"), f::suggestion);
        assertEquals("select * from orders o where o.qty = 5 - 1", f.suggested().sql());
        TableMeta orders = table(null, "orders", List.of("id"), List.of("qty"), "id int!", "qty int", "a varchar(5)",
                "b varchar(5)");
        Finding certain = one(Dialect.SQLSERVER, "select * from orders where qty - 2 > @n;", "AMB-P007", orders);
        assertEquals(Confidence.CERTAIN, certain.confidence());
        assertTrue(certain.message().contains("the index on"), certain::message);
        assertEquals("select * from orders where qty > @n + 2;", certain.suggested().sql());
        Finding concat = one(Dialect.POSTGRESQL, "select * from orders o where o.a || o.b = 'xy';", "AMB-P007");
        assertTrue(concat.message().startsWith("Concatenation"), concat::message);
        assertNull(concat.suggested());
        Finding mul = one(Dialect.DB2, "select * from orders o join items i on o.qty * 2 = i.qty;", "AMB-P007");
        assertNull(mul.suggested());
        none(Dialect.MYSQL, "select * from orders o where o.a || o.b = 'xy';", "AMB-P007");
        none(Dialect.POSTGRESQL, "select * from t where p ->> 'b' = 'x' and p -> 'a' = '1';", "AMB-P007");
        none(Dialect.ORACLE, "select * from orders o where o.qty = 5 + 1;", "AMB-P007");
        none(Dialect.ORACLE, "select o.qty + 1 from orders o where o.id = 3;", "AMB-P007");
    }

    @Test
    void nullFunctionsInJoinConditions() {
        Finding f = one(Dialect.ORACLE, "select * from a join b on nvl(a.k, 0) = b.k;", "AMB-P007");
        assertTrue(f.message().contains("NVL(a.k"), f::message);
        one(Dialect.SQLSERVER, "select * from a join b on isnull(a.k, 0) = b.k;", "AMB-P007");
        none(Dialect.ORACLE, "select * from a join b on a.k = b.k where nvl(a.x, 0) = 1;", "AMB-P007");
    }

    @Test
    void unqualifiedNamesInRoutinesNeedMetadata() {
        String plsql = """
                create or replace procedure p(v_limit number) is
                  n number;
                begin
                  select count(*) into n from orders where qty + 1 > v_limit;
                end;
                /
                """;
        none(Dialect.ORACLE, plsql, "AMB-P007");
        one(Dialect.ORACLE, plsql, "AMB-P007", table(null, "orders", List.of("id"), List.of(), "id number", "qty number"));
    }

    @Test
    void distinctHidingAJoin() {
        TableMeta c = table(null, "customers", List.of("id"), List.of(), "id int!", "name varchar(50)");
        TableMeta o = table(null, "orders", List.of("id"), List.of(), "id int!", "customer_id int");
        String sql = "select distinct c.id, c.name from customers c join orders o on o.customer_id = c.id;";
        Finding f = one(Dialect.POSTGRESQL, sql, "AMB-P009", c, o);
        assertEquals(Confidence.CERTAIN, f.confidence());
        one(Dialect.ORACLE, "select distinct c.* from customers c, orders o where o.customer_id = c.id;", "AMB-P009", c, o);
        none(Dialect.POSTGRESQL, sql, "AMB-P009");
        none(Dialect.POSTGRESQL, "select distinct c.name from customers c join orders o on o.customer_id = c.id;",
                "AMB-P009", c, o);
        none(Dialect.POSTGRESQL, "select distinct c.id, o.id from customers c join orders o on o.customer_id = c.id;",
                "AMB-P009", c, o);
        none(Dialect.POSTGRESQL, "select distinct c.id from customers c left join orders o on o.customer_id = c.id;",
                "AMB-P009", c, o);
        assertEquals(List.of(), rule(Dialect.POSTGRESQL, "select distinct c.id, count(*) from customers c "
                + "join orders o on o.customer_id = c.id group by c.id;", "AMB-P009", c, o));
    }
}
