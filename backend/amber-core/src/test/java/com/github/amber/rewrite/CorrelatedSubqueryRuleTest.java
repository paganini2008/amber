package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.rewrite.RewriteTestSupport.catalog;
import static com.github.amber.rewrite.RewriteTestSupport.none;
import static com.github.amber.rewrite.RewriteTestSupport.only;
import static com.github.amber.rewrite.RewriteTestSupport.suggest;
import static com.github.amber.rewrite.RewriteTestSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AMB-P003 quick fix: correlated aggregate subquery in the select list → LEFT JOIN of a grouped derived
 * table.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class CorrelatedSubqueryRuleTest {

    private static final String SUM = "select o.id, (select sum(l.amount) from order_lines l "
            + "where l.order_id = o.id and l.status = 'A') as total from orders o where o.region = 'EU';";
    private static final Catalog META = catalog(table(null, "orders", List.of("id"), "id number!", "region varchar2(5)"),
            table(null, "order_lines", List.of(), "order_id number", "amount number", "status varchar2(1)"));

    @Test
    void sumBecomesAJoinInEveryDialect() {
        for (Dialect d : List.of(Dialect.ORACLE, Dialect.SQLSERVER, Dialect.POSTGRESQL, Dialect.MYSQL, Dialect.MARIADB,
                Dialect.DB2, Dialect.H2, Dialect.SQLITE)) {
            Rewrite r = only(suggest(d, SUM), "AMB-P003");
            assertTrue(r.rewrittenStatement().startsWith("select o.id, sq1.agg_value as total from orders o left join "
                    + "(select l.order_id, sum(l.amount) as agg_value from order_lines l where l.status = 'A' group by "
                    + "l.order_id) sq1 on sq1.order_id = o.id where o.region = 'EU'"), () -> d + ": " + r);
            assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, r.safety(), "types unknown");
        }
    }

    @Test
    void withMatchingTypesItIsEquivalentAndCountUsesCoalesce() {
        Rewrite r = only(suggest(Dialect.ORACLE, SUM, META), "AMB-P003");
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, r.safety());
        String count = "SELECT o.id, (SELECT COUNT(*) FROM order_lines l WHERE o.id = l.order_id) AS n FROM orders o;";
        Rewrite c = only(suggest(Dialect.POSTGRESQL, count, META), "AMB-P003");
        assertTrue(c.rewrittenStatement().contains("COALESCE(sq1.agg_value, 0) AS n")
                || c.rewrittenStatement().contains("COALESCE(SQ1.AGG_VALUE, 0) AS n"), c::rewrittenStatement);
        assertTrue(c.rewrittenStatement().contains("LEFT JOIN (SELECT l.order_id, COUNT(*) AS"), c::rewrittenStatement);
        Catalog mismatch = catalog(table(null, "orders", List.of("id"), "id varchar2(10)!"),
                table(null, "order_lines", List.of(), "order_id number", "amount number"));
        none(suggest(Dialect.ORACLE, SUM, mismatch), "AMB-P003");
    }

    @Test
    void shapesThatAreNotProvablyEquivalentAreLeftAlone() {
        String[] cases = {
            // no alias for the item
            "select o.id, (select sum(l.amount) from order_lines l where l.order_id = o.id) from orders o;",
            // unqualified column inside the subquery could be an outer column
            "select o.id, (select sum(amount) from order_lines l where l.order_id = o.id) t from orders o;",
            // outer column inside the aggregate
            "select o.id, (select sum(l.amount * o.rate) from order_lines l where l.order_id = o.id) t from orders o;",
            // OR in the subquery
            "select o.id, (select sum(l.amount) from order_lines l where l.order_id = o.id or l.x = 1) t from orders o;",
            // not an aggregate: may return several rows
            "select o.id, (select l.amount from order_lines l where l.order_id = o.id) t from orders o;",
            // grouped subquery
            "select o.id, (select sum(l.amount) from order_lines l where l.order_id = o.id group by l.x) t from orders o;",
            // comma join in the outer query
            "select o.id, (select sum(l.amount) from order_lines l where l.order_id = o.id) t from orders o, regions r "
                    + "where r.id = o.region_id;",
            // outer DISTINCT / GROUP BY
            "select distinct o.id, (select sum(l.amount) from order_lines l where l.order_id = o.id) t from orders o;",
            // non-equality correlation
            "select o.id, (select sum(l.amount) from order_lines l where l.order_id > o.id) t from orders o;",
            // join inside the subquery
            "select o.id, (select sum(l.amount) from order_lines l join p on p.id = l.pid where l.order_id = o.id) t "
                    + "from orders o;",
            // aggregate in the outer select list
            "select count(*) c, (select sum(l.amount) from order_lines l where l.order_id = o.id) t from orders o;",
        };
        for (String sql : cases) {
            none(suggest(Dialect.POSTGRESQL, sql), "AMB-P003");
        }
        none(suggest(Dialect.ORACLE, "-- amber:ignore AMB-P003\n" + SUM), "AMB-P003");
    }
}
