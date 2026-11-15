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
 * AMB-S008 for the year of a column: {@code YEAR(d)}, {@code EXTRACT(YEAR FROM d)}, {@code date_part('year', d)}
 * and Oracle's {@code TO_CHAR(d, 'YYYY')} compared with a literal year become a range of whole years.
 *
 * @Author: Fred Feng
 * @Date: 15/11/2026
 * @Version 1.0.0
 */
class DateRangeYearTest {

    private static Catalog orders(String createdType) {
        return catalog(table(null, "orders", List.of("id"), "id int!", "created " + createdType, "code varchar(10)"));
    }

    private static Rewrite year(Dialect d, String sql, Catalog meta) {
        return only(suggest(d, sql, meta), "AMB-S008");
    }

    private static void ends(Rewrite r, String tail) {
        assertTrue(r.rewrittenStatement().replaceAll(";\\s*$", "").endsWith(tail), r::rewrittenStatement);
    }

    @Test
    void yearEqualsBecomesOneYearRange() {
        Rewrite r = year(Dialect.MYSQL, "select id from orders where year(created) = 2024;", orders("datetime"));
        assertEquals("select id from orders where created >= '2024-01-01' and created < '2025-01-01'",
                r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, r.safety());
        assertTrue(r.safetyNote().contains("2024"), r.safetyNote());
    }

    @Test
    void everyComparisonAndDialect() {
        ends(year(Dialect.SQLSERVER, "SELECT id FROM orders o WHERE YEAR(o.created) BETWEEN 2020 AND 2022;",
                orders("datetime2")), "WHERE o.created >= '20200101' AND o.created < '20230101'");
        ends(year(Dialect.ORACLE, "SELECT id FROM orders WHERE EXTRACT(YEAR FROM created) > 2023;",
                orders("date")), "WHERE created >= DATE '2024-01-01'");
        ends(year(Dialect.POSTGRESQL, "select id from orders where date_part('year', created) <= 2024;",
                orders("timestamp")), "where created < date '2025-01-01'");
        ends(year(Dialect.DB2, "SELECT id FROM orders WHERE YEAR(created) < 2024;",
                orders("timestamp")), "WHERE created < DATE('2024-01-01')");
        ends(year(Dialect.MARIADB, "select id from orders where extract(year from created) >= 2024;",
                orders("date")), "where created >= '2024-01-01'");
        for (Dialect d : List.of(Dialect.SQLSERVER, Dialect.ORACLE, Dialect.POSTGRESQL, Dialect.DB2, Dialect.MARIADB)) {
            assertEquals(Safety.EQUIVALENT_WITH_METADATA, year(d, d == Dialect.ORACLE || d == Dialect.POSTGRESQL
                    || d == Dialect.MARIADB ? "select id from orders where extract(year from created) = 2024;"
                    : "select id from orders where year(created) = 2024;", orders("date")).safety(), d::toString);
        }
    }

    @Test
    void notAndOrKeepTheReading() {
        Rewrite r = year(Dialect.MYSQL, "select id from orders where not year(created) = 2024 or code = 'x';",
                orders("date"));
        assertTrue(r.rewrittenStatement().contains("not (created >= '2024-01-01' and created < '2025-01-01') "
                + "or code = 'x'"), r::rewrittenStatement);
    }

    @Test
    void edgeCasesAreSpelledOut() {
        // no metadata: the column could be text
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES,
                only(suggest(Dialect.MYSQL, "select id from orders where year(created) = 2024;"), "AMB-S008").safety());
        // a time zone moves New Year's Eve
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, year(Dialect.POSTGRESQL,
                "select id from orders where extract(year from created) = 2024;", orders("timestamptz")).safety());
        // TO_CHAR follows the session calendar, even with metadata
        Rewrite toChar = year(Dialect.ORACLE, "SELECT id FROM orders WHERE TO_CHAR(created, 'YYYY') = '2025';",
                orders("date"));
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, toChar.safety());
        assertTrue(toChar.safetyNote().contains("NLS_CALENDAR"), toChar.safetyNote());
        assertTrue(toChar.rewrittenStatement().endsWith("WHERE created >= DATE '2025-01-01' AND created < DATE '2026-01-01'"),
                toChar::rewrittenStatement);
    }

    @Test
    void timestamptzIsZonedForDaysToo() {
        // PostgreSQL's JDBC driver reports "timestamptz": the day of such a value depends on the session time zone
        Rewrite day = year(Dialect.POSTGRESQL, "select id from orders where created::date = date '2024-01-15';",
                orders("timestamptz"));
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, day.safety());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, year(Dialect.POSTGRESQL,
                "select id from orders where created::date = date '2024-01-15';", orders("timestamp")).safety());
    }

    @Test
    void shapesThatAreNotAYearRangeAreLeftAlone() {
        String[] cases = {
            // the compared value is not a literal year
            "select id from orders where year(created) = year(curdate());",
            "select id from orders where year(created) = ?;",
            // part of a longer expression
            "select id from orders where year(created) = 2024 + 1;",
            // text compared with YEAR: converted by the server
            "select id from orders where year(created) = '2024';",
            // no next year to end the range
            "select id from orders where year(created) = 9999;",
            // not a four-digit year
            "select id from orders where year(created) = 24;",
            // the year on the value side
            "select id from orders where 2024 = year(created);",
            // a backwards BETWEEN
            "select id from orders where year(created) between 2024 and 2020;"
        };
        for (String sql : cases) {
            none(suggest(Dialect.MYSQL, sql, orders("date")), "AMB-S008");
        }
        // not a date column: YEAR means something else
        none(suggest(Dialect.MYSQL, "select id from orders where year(code) = 2024;", orders("date")), "AMB-S008");
        // TO_CHAR with another format
        none(suggest(Dialect.ORACLE, "SELECT id FROM orders WHERE TO_CHAR(created, 'YYYYMM') = '202501';",
                orders("date")), "AMB-S008");
        // YEAR() is not an Oracle function
        none(suggest(Dialect.ORACLE, "SELECT id FROM orders WHERE YEAR(created) = 2024;", orders("date")), "AMB-S008");
    }
}
