package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
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
 * S008 and S004 on MySQL, MariaDB, Db2, H2 and SQLite, plus a few other rules on those dialects.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class RewriteOtherDialectsTest {

    private static final String DDL = "create table visits (id int not null primary key, seen datetime not null, "
            + "page varchar(100));\n";
    private static final String DDL_TS = DDL.replace("datetime", "timestamp");
    private static final String NOT_IN = "select id from visits where page not in (select page from banned);";

    @Test
    void mysqlAndMariaDbDateFunction() {
        for (Dialect d : List.of(Dialect.MYSQL, Dialect.MARIADB)) {
            Rewrite lit = only(suggest(d, DDL + "select id from visits where date(seen) = '2024-05-01';"), "AMB-S008");
            assertEquals("select id from visits where seen >= '2024-05-01' and seen < '2024-05-02'", lit.rewrittenStatement());
            assertEquals(Safety.EQUIVALENT_WITH_METADATA, lit.safety());
            Rewrite today = only(suggest(d, DDL + "select id from visits where DATE(seen) = CURDATE();"), "AMB-S008");
            assertEquals("select id from visits where seen >= CURDATE() AND seen < CURDATE() + INTERVAL 1 DAY",
                    today.rewrittenStatement());
            assertEquals(Safety.EQUIVALENT_WITH_METADATA, today.safety());
            Rewrite cast = only(suggest(d, DDL + "select id from visits where cast(seen as date) <= @d;"), "AMB-S008");
            assertEquals("select id from visits where seen < @d + interval 1 day", cast.rewrittenStatement());
            none(suggest(d, DDL + "select id from visits where date_format(seen, '%Y') = '2024';"), "AMB-S008");
        }
    }

    @Test
    void db2DateFunction() {
        Dialect d = Dialect.DB2;
        Rewrite lit = only(suggest(d, DDL_TS + "select id from visits where date(seen) = '2024-05-01';"), "AMB-S008");
        assertEquals("select id from visits where seen >= date('2024-05-01') and seen < date('2024-05-02')",
                lit.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, lit.safety());
        Rewrite cur = only(suggest(d, DDL_TS + "select id from visits where date(seen) = current date;"), "AMB-S008");
        assertEquals("select id from visits where seen >= current date and seen < current date + 1 day",
                cur.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, cur.safety());
    }

    @Test
    void h2CastAndTrunc() {
        Dialect d = Dialect.H2;
        Rewrite cast = only(suggest(d, DDL_TS + "select id from visits where cast(seen as date) = current_date;"),
                "AMB-S008");
        assertEquals("select id from visits where seen >= current_date and seen < dateadd(day, 1, current_date)",
                cast.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, cast.safety());
        Rewrite trunc = only(suggest(d, DDL_TS + "select id from visits where trunc(seen) = date '2024-05-01';"),
                "AMB-S008");
        assertEquals("select id from visits where seen >= date '2024-05-01' and seen < date '2024-05-02'",
                trunc.rewrittenStatement());
    }

    @Test
    void sqliteDateIsAlwaysAnEdgeCase() {
        Dialect d = Dialect.SQLITE;
        Rewrite lit = only(suggest(d, DDL + "select id from visits where date(seen) = '2024-05-01';"), "AMB-S008");
        assertEquals("select id from visits where seen >= '2024-05-01' and seen < '2024-05-02'", lit.rewrittenStatement());
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, lit.safety());
        assertTrue(lit.safetyNote().contains("ISO-8601"));
        Rewrite now = only(suggest(d, DDL + "select id from visits where date(seen) = date('now');"), "AMB-S008");
        assertEquals("select id from visits where seen >= date('now') and seen < date(date('now'), '+1 day')",
                now.rewrittenStatement());
        none(suggest(d, DDL + "select id from visits where date(seen, 'localtime') = '2024-05-01';"), "AMB-S008");
    }

    @Test
    void notInOnEveryDialect() {
        for (Dialect d : List.of(Dialect.MYSQL, Dialect.MARIADB, Dialect.DB2, Dialect.H2, Dialect.SQLITE)) {
            String ddl = d == Dialect.DB2 || d == Dialect.H2 ? DDL_TS : DDL;
            Rewrite r = only(suggest(d, ddl + NOT_IN), "AMB-S004");
            assertEquals("select id from visits where not exists (select 1 from banned where page = visits.page)",
                    r.rewrittenStatement(), d.name());
            assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, r.safety());
            var meta = catalog(table(null, "visits", List.of("id"), "id int!", "page varchar(100)!"),
                    table(null, "banned", List.of(), "page varchar(100)!"));
            assertEquals(Safety.EQUIVALENT_WITH_METADATA, only(suggest(d, NOT_IN, meta), "AMB-S004").safety(), d.name());
        }
    }

    @Test
    void otherRulesOnMySql() {
        Dialect d = Dialect.MYSQL;
        only(suggest(d, "select v.id from visits v left join pages p on p.url = v.page where p.kind = 'blog';"),
                "AMB-S002");
        Rewrite star = only(suggest(d, DDL + "insert into visits_copy select * from visits;"), "AMB-S010");
        assertEquals("insert into visits_copy select id, seen, page from visits", star.rewrittenStatement());
        none(suggest(d, "select id from visits union distinct select id from visits;"), "AMB-S009");
        only(suggest(d, "select id from visits union select id from visits;"), "AMB-S009");
        Rewrite quoted = only(suggest(d, "create table `odd` (`id` int, `order` int, Mixed int);\n"
                + "insert into t select * from `odd`;"), "AMB-S010");
        assertEquals("insert into t select `id`, `order`, Mixed from `odd`", quoted.rewrittenStatement());
        // a quoted name with a space is read back whole from the DDL and stays quoted
        Rewrite spaced = only(suggest(d, "create table odd2 (`my col` int);\ninsert into t select * from odd2;"), "AMB-S010");
        assertEquals("insert into t select `my col` from odd2", spaced.rewrittenStatement());
    }
}
