package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.rewrite.RewriteTestSupport.catalog;
import static com.github.amber.rewrite.RewriteTestSupport.none;
import static com.github.amber.rewrite.RewriteTestSupport.of;
import static com.github.amber.rewrite.RewriteTestSupport.only;
import static com.github.amber.rewrite.RewriteTestSupport.suggest;
import static com.github.amber.rewrite.RewriteTestSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rewrite rules on Oracle scripts: positive and negative cases per rule, PL/SQL bodies, metadata.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class RewriteOracleTest {

    private static final Dialect D = Dialect.ORACLE;
    private static final String DDL = """
            create table orders (id number not null, cust_id number not null, created date, status varchar2(10),
                                 primary key (id));
            create table blocked (cust_id number not null, reason varchar2(20));
            """;

    // ----- S002 --------------------------------------------------------------------------------------

    @Test
    void leftJoinFilteredInWhereBecomesJoin() {
        String sql = """
                select o.id, c.name
                  from orders o
                  LEFT OUTER JOIN customers c on c.id = o.cust_id
                 where c.region = 'NORTH' and o.status = 'A';
                """;
        Rewrite r = only(suggest(D, sql), "AMB-S002");
        assertEquals("""
                select o.id, c.name
                  from orders o
                  JOIN customers c on c.id = o.cust_id
                 where c.region = 'NORTH' and o.status = 'A'""", r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT, r.safety());
        assertEquals("STRUCTURE_PRESERVED", r.check());
        assertEquals(2, r.edits().size());
    }

    @Test
    void leftJoinNullRejectingShapes() {
        for (String cond : List.of("c.region in ('A', 'B')", "c.region like 'N%'", "c.score between 1 and 5",
                "c.region is not null", "5 < c.score", "c.score <= 3", "c.region <> 'X'", "c.region != 'X'",
                "c.region not in ('A')")) {
            String sql = "select o.id from orders o left join customers c on c.id = o.cust_id where o.id > 0 and " + cond
                    + ";";
            Rewrite r = only(suggest(D, sql), "AMB-S002");
            assertTrue(r.rewrittenStatement().contains(" join customers c"), cond);
        }
    }

    @Test
    void leftJoinKeptWhenWhereDoesNotRejectNull() {
        for (String cond : List.of("c.id is null", "c.region = 'A' or o.status = 'B'", "nvl(c.region, 'X') = 'X'",
                "o.status = 'A'", "c.region || 'x' = 'Ax'", "(c.region = 'A' or c.region is null)")) {
            String sql = "select o.id from orders o left join customers c on c.id = o.cust_id where " + cond + ";";
            none(suggest(D, sql), "AMB-S002");
        }
        none(suggest(D, "select o.id from orders o left join customers c on c.id = o.cust_id;"), "AMB-S002");
        // the same alias exposed twice: not ours to judge
        none(suggest(D, "select 1 from orders c left join customers c on c.id = 1 where c.x = 1;"), "AMB-S002");
    }

    // ----- S011 --------------------------------------------------------------------------------------

    @Test
    void distinctWithGroupByDropped() {
        Rewrite r = only(suggest(D, "SELECT DISTINCT o.status, count(*) cnt FROM orders o GROUP BY status;"), "AMB-S011");
        assertEquals("SELECT o.status, count(*) cnt FROM orders o GROUP BY status", r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT, r.safety());
        assertEquals("STRUCTURE_CHANGED", r.check());
        assertTrue(r.checkDifferences().get(0).startsWith("Ordering and row limits"));
        // position references
        only(suggest(D, "select distinct status, created, max(id) from orders group by 1, 2;"), "AMB-S011");
    }

    @Test
    void distinctKeptWhenAGroupingColumnIsNotSelected() {
        none(suggest(D, "select distinct status, count(*) from orders group by status, created;"), "AMB-S011");
        none(suggest(D, "select distinct status from orders group by rollup(status);"), "AMB-S011");
        none(suggest(D, "select distinct * from orders group by id;"), "AMB-S011");
        none(suggest(D, "select status, count(*) from orders group by status;"), "AMB-S011");
        none(suggest(D, "select distinct status, count(*) from orders group by 9;"), "AMB-S011");
    }

    // ----- S012 --------------------------------------------------------------------------------------

    @Test
    void orderByInDerivedTableDropped() {
        String sql = """
                select x.id
                  from (select id
                          from orders
                         order by created desc, id) x;
                """;
        Rewrite r = only(suggest(D, sql), "AMB-S012");
        assertEquals("""
                select x.id
                  from (select id
                          from orders) x""", r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT, r.safety());
        assertEquals("STRUCTURE_CHANGED", r.check());
    }

    @Test
    void orderByInCteAndInSubqueryDropped() {
        Rewrite cte = only(suggest(D, "with t as (select id from orders order by id) select id from t;"), "AMB-S012");
        assertEquals("with t as (select id from orders) select id from t", cte.rewrittenStatement());
        Rewrite in = only(suggest(D, "select 1 from dual where 1 in (select id from orders order by id);"), "AMB-S012");
        assertTrue(in.rewrittenStatement().endsWith("(select id from orders)"));
    }

    @Test
    void orderByKeptWhenARowLimitDependsOnIt() {
        none(suggest(D, "select a from (select id a from orders order by id) where rownum <= 5;"), "AMB-S012");
        none(suggest(D, "select * from (select id from orders order by id fetch first 3 rows only) x;"), "AMB-S012");
        none(suggest(D, "select listagg(id, ',') within group (order by id) from (select id from orders order by id);"),
                "AMB-S012");
        none(suggest(D, "select xmlagg(xmlelement(e, id)) from (select id from orders order by id);"), "AMB-S012");
        none(suggest(D, "select id from orders order by id;"), "AMB-S012");
        none(suggest(D, "select row_number() over (order by 1) rn from (select id from orders order by id);"), "AMB-S012");
        none(suggest(D, "insert into t select seq.nextval, id from (select id from orders order by id);"), "AMB-S012");
    }

    // ----- S004 --------------------------------------------------------------------------------------

    @Test
    void notInBecomesNotExistsAndIsEquivalentWithNotNullMetadata() {
        String sql = DDL + """
                select o.id from orders o
                 where o.cust_id not in (select b.cust_id from blocked b where b.reason = 'FRAUD');
                """;
        Rewrite r = only(suggest(D, sql), "AMB-S004");
        assertEquals("""
                select o.id from orders o
                 where not exists (select 1 from blocked b where b.reason = 'FRAUD' and b.cust_id = o.cust_id)""",
                r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, r.safety());
        assertEquals("STRUCTURE_PRESERVED", r.check());
    }

    @Test
    void notInSafetyDependsOnNullability() {
        String sql = "select o.id from orders o where o.cust_id not in (select b.cust_id from blocked b);";
        Rewrite unknown = only(suggest(D, sql), "AMB-S004");
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, unknown.safety());
        assertTrue(unknown.safetyNote().contains("NULL") && unknown.safetyNote().contains("not known"));

        var nullable = catalog(table(null, "orders", List.of("id"), "id number!", "cust_id number"),
                table(null, "blocked", List.of(), "cust_id number!"));
        Rewrite n = only(suggest(D, sql, nullable), "AMB-S004");
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, n.safety());
        assertTrue(!n.safetyNote().contains("not known"));

        var notNull = catalog(table(null, "orders", List.of("id"), "id number!", "cust_id number!"),
                table(null, "blocked", List.of(), "cust_id number!"));
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, only(suggest(D, sql, notNull), "AMB-S004").safety());

        // a nullable subquery column filtered with IS NOT NULL counts as NOT NULL
        var nullableInner = catalog(table(null, "orders", List.of("id"), "id number!", "cust_id number!"),
                table(null, "blocked", List.of(), "cust_id number"));
        String filtered = "select o.id from orders o where o.cust_id not in "
                + "(select b.cust_id from blocked b where b.cust_id is not null);";
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, only(suggest(D, filtered, nullableInner), "AMB-S004").safety());
    }

    @Test
    void notInQualifiesTheOuterColumnAndAliasesASelfReference() {
        Rewrite r = only(suggest(D, DDL + "select * from orders where id not in (select id from orders where status = 'X');"),
                "AMB-S004");
        assertEquals("select * from orders where not exists (select 1 from orders sq1 where status = 'X' and sq1.id = "
                + "orders.id)", r.rewrittenStatement());
        Rewrite unq = only(suggest(D, "select o.id from orders o where cust_id not in (select cust_id from blocked);"),
                "AMB-S004");
        assertEquals("select o.id from orders o where not exists (select 1 from blocked where cust_id = o.cust_id)",
                unq.rewrittenStatement());
    }

    @Test
    void notInWithOrInSubqueryWhereIsParenthesized() {
        Rewrite r = only(suggest(D, "select o.id from orders o where o.cust_id not in "
                + "(select distinct b.cust_id from blocked b where b.reason = 'A' or b.reason = 'B');"), "AMB-S004");
        assertTrue(r.rewrittenStatement().contains("(select 1 from blocked b where (b.reason = 'A' or b.reason = 'B') "
                + "and b.cust_id = o.cust_id)"), r.rewrittenStatement());
    }

    @Test
    void notInSkippedWhenItCannotBeDoneCorrectly() {
        // several columns, aggregates, set operators, limits, expressions on the left
        none(suggest(D, "select 1 from orders o where (o.id, o.cust_id) not in (select a, b from t);"), "AMB-S004");
        none(suggest(D, "select 1 from orders o where o.id not in (select max(b.cust_id) from blocked b);"), "AMB-S004");
        none(suggest(D, "select 1 from orders o where o.id not in (select a from t union select b from u);"), "AMB-S004");
        none(suggest(D, "select 1 from orders o where o.id not in (select a from t where rownum < 3);"), "AMB-S004");
        none(suggest(D, "select 1 from orders o where o.id not in (select a from t group by a);"), "AMB-S004");
        none(suggest(D, "select 1 from orders o where o.id + 1 not in (select a from t);"), "AMB-S004");
        none(suggest(D, "select 1 from orders o where :x not in (select a from t);"), "AMB-S004");
        // unqualified outer column with two candidate tables and no metadata
        none(suggest(D, "select 1 from orders o, blocked b where id not in (select a from t);"), "AMB-S004");
        // the outer alias is reused inside and the inner table already has an alias
        none(suggest(D, "select 1 from orders o where o.id not in (select o.id from blocked o);"), "AMB-S004");
        // ignored by the author
        none(suggest(D, "-- amber:ignore AMB-S004\nselect 1 from orders o where o.id not in (select a from t);"),
                "AMB-S004");
    }

    // ----- S008 --------------------------------------------------------------------------------------

    @Test
    void truncOnADateColumnBecomesARange() {
        Rewrite r = only(suggest(D, DDL + "select id from orders where TRUNC(created) = DATE '2024-02-29';"), "AMB-S008");
        assertEquals("select id from orders where created >= DATE '2024-02-29' AND created < DATE '2024-03-01'",
                r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, r.safety());
        assertEquals("STRUCTURE_PRESERVED", r.check());
    }

    @Test
    void truncOperatorsAndValues() {
        String base = DDL + "select id from orders o where ";
        assertEquals("o.created >= trunc(sysdate) and o.created < trunc(sysdate) + 1",
                where(only(suggest(D, base + "trunc(o.created) = trunc(sysdate);"), "AMB-S008")));
        assertEquals("o.created >= date '2024-01-01' and o.created < date '2024-02-01'",
                where(only(suggest(D, base + "trunc(o.created) between date '2024-01-01' and date '2024-01-31';"),
                        "AMB-S008")));
        assertEquals("o.created >= to_date('2024-01-01', 'YYYY-MM-DD') + 1",
                where(only(suggest(D, base + "trunc(o.created) > to_date('2024-01-01', 'YYYY-MM-DD');"), "AMB-S008")));
        assertEquals("o.created < date '2024-01-02'",
                where(only(suggest(D, base + "trunc(o.created, 'DD') <= date '2024-01-01';"), "AMB-S008")));
        assertEquals("o.created >= date '2024-01-01'",
                where(only(suggest(D, base + "trunc(o.created) >= date '2024-01-01';"), "AMB-S008")));
        assertEquals("o.created < date '2024-01-01'",
                where(only(suggest(D, base + "trunc(o.created) < date '2024-01-01';"), "AMB-S008")));
        Rewrite bind = only(suggest(D, base + "trunc(o.created) = :day;"), "AMB-S008");
        assertEquals("o.created >= :day and o.created < :day + 1", where(bind));
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, bind.safety());
        assertTrue(bind.safetyNote().contains("no time of day"));
        Rewrite expr = only(suggest(D, base + "trunc(o.created) = sysdate - 1;"), "AMB-S008");
        assertEquals("o.created >= sysdate - 1 and o.created < (sysdate - 1) + 1", where(expr));
        Rewrite negated = only(suggest(D, base + "not trunc(o.created) = date '2024-01-01';"), "AMB-S008");
        assertEquals("not (o.created >= date '2024-01-01' and o.created < date '2024-01-02')", where(negated));
        Rewrite or = only(suggest(D, base + "o.status = 'A' or trunc(o.created) = date '2024-01-01';"), "AMB-S008");
        assertTrue(where(or).endsWith("or (o.created >= date '2024-01-01' and o.created < date '2024-01-02')"));
    }

    @Test
    void truncSafetyFollowsTheColumnType() {
        String sql = "select id from orders where trunc(created) = date '2024-01-01';";
        Rewrite unknown = only(suggest(D, sql), "AMB-S008");
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, unknown.safety());
        assertTrue(unknown.safetyNote().contains("unknown"));
        var zoned = catalog(table(null, "orders", List.of(), "created timestamp with time zone"));
        assertTrue(only(suggest(D, sql, zoned), "AMB-S008").safetyNote().contains("time zone"));
        var number = catalog(table(null, "orders", List.of(), "created number"));
        none(suggest(D, sql, number), "AMB-S008");
        var ts = catalog(table(null, "orders", List.of(), "created timestamp(6)"));
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, only(suggest(D, sql, ts), "AMB-S008").safety());
    }

    @Test
    void functionsWithoutARangeFormAreNotRewritten() {
        none(suggest(D, "select id from orders where upper(status) = 'A';"), "AMB-S008");
        none(suggest(D, "select id from orders where trunc(created) = '01-JAN-2024';"), "AMB-S008");
        none(suggest(D, "select id from orders where trunc(created, 'MM') = date '2024-01-01';"), "AMB-S008");
        none(suggest(D, "select id from orders where trunc(created) <> date '2024-01-01';"), "AMB-S008");
        none(suggest(D, "select id from orders where trunc(created) = (select max(d) from t);"), "AMB-S008");
        none(suggest(D, "select id from orders where date '2024-01-01' = trunc(created);"), "AMB-S008");
        none(suggest(D, "select trunc(created) from orders;"), "AMB-S008");
    }

    private static String where(Rewrite r) {
        String s = r.rewrittenStatement();
        return s.substring(s.lastIndexOf("where ") + 6);
    }

    // ----- S009 --------------------------------------------------------------------------------------

    @Test
    void unionAllSafetyLevels() {
        Rewrite plain = only(suggest(D, "select id from orders union select cust_id from blocked;"), "AMB-S009");
        assertEquals("select id from orders union all select cust_id from blocked", plain.rewrittenStatement());
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, plain.safety());

        Rewrite member = only(suggest(D, "select 1 from dual where 5 in (select id from orders union select 6 from dual);"),
                "AMB-S009");
        assertEquals(Safety.EQUIVALENT, member.safety());
        Rewrite any = only(suggest(D, "select 1 from dual where 5 = any (select id from orders union select 6 from dual);"),
                "AMB-S009");
        assertEquals(Safety.EQUIVALENT, any.safety());

        String disjoint = DDL + "SELECT 'NEW' kind, id FROM orders WHERE status = 'N' UNION SELECT 'OLD', id FROM orders;";
        Rewrite d = only(suggest(D, disjoint), "AMB-S009");
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, d.safety());
        assertTrue(d.rewrittenStatement().contains("UNION ALL SELECT"));

        // filtered column selected in both branches
        String filtered = DDL + "select status, id from orders where status = 'A' union select status, id from orders "
                + "where 'B' = status;";
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, only(suggest(D, filtered), "AMB-S009").safety());
        // filtered column not selected: rows from different filters can still be equal
        String hidden = DDL + "select id from orders where status = 'A' union select id from orders where status = 'B';";
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(D, hidden), "AMB-S009").safety());
        // discriminator equal ignoring case and trailing blanks: not provably disjoint
        String sameish = DDL + "select 'a' k, id from orders union select 'A  ' k, id from orders;";
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(D, sameish), "AMB-S009").safety());
        // a branch without a key can return duplicates
        String dup = DDL + "select 'A' k, status from orders union select 'B' k, status from orders;";
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(D, dup), "AMB-S009").safety());
        // ... unless it is DISTINCT or grouped by what it selects
        String grouped = DDL + "select distinct 'A' k, status from orders union select 'B' k, status from orders "
                + "group by status;";
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, only(suggest(D, grouped), "AMB-S009").safety());
        String partly = DDL + "select distinct 'A' k, status from orders union select 'B' k, status from orders "
                + "group by status, created;";
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(D, partly), "AMB-S009").safety());
        none(suggest(D, "select id from orders union all select id from orders;"), "AMB-S009");
        // MINUS in the chain: no proof attempted
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(D, DDL
                + "select 1 k, id from orders union select 2, id from orders minus select 3, id from orders;"),
                "AMB-S009").safety());
    }

    @Test
    void unionAllOverThreeDisjointKeyedBranches() {
        String sql = DDL + "select 1 k, id from orders union select 2, id from orders union all select 3, id from orders;";
        Rewrite r = only(suggest(D, sql), "AMB-S009");
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, r.safety());
        assertTrue(r.safetyNote().contains("branch 3"));
    }

    // ----- S010 --------------------------------------------------------------------------------------

    @Test
    void selectStarListsTheColumns() {
        Rewrite r = only(suggest(D, DDL + "insert into order_copy select * from orders;"), "AMB-S010");
        assertEquals("insert into order_copy select id, cust_id, created, status from orders", r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, r.safety());
        assertEquals("STRUCTURE_PRESERVED", r.check());
        Rewrite join = only(suggest(D, DDL + "create table t2 as select * from orders o join blocked b on b.cust_id = o.cust_id;"),
                "AMB-S010");
        assertEquals("create table t2 as select o.id, o.cust_id, o.created, o.status, b.cust_id, b.reason from orders o "
                + "join blocked b on b.cust_id = o.cust_id", join.rewrittenStatement());
    }

    @Test
    void selectStarFromImportedMetadataQuotesWhereNeeded() {
        var meta = catalog(table("app", "audit", List.of(), "ID number!", "Mixed number", "COMMENT varchar2(5)"));
        Rewrite r = only(suggest(D, "create view v_audit as select * from app.audit;", meta), "AMB-S010");
        assertEquals("create view v_audit as select ID, \"Mixed\", \"COMMENT\" from app.audit", r.rewrittenStatement());
    }

    @Test
    void selectStarSkippedWhenColumnsAreUnknown() {
        none(suggest(D, "insert into t select * from unknown_table;"), "AMB-S010");
        none(suggest(D, DDL + "select * from orders;"), "AMB-S010");
        none(suggest(D, DDL + "insert into t select * from orders join blocked using (cust_id);"), "AMB-S010");
        // created twice in the script: which definition applies is not clear
        none(suggest(D, DDL + "drop table orders; create table orders (x number);\ninsert into t select * from orders;"),
                "AMB-S010");
        // created only after the statement
        none(suggest(D, "insert into t select * from later;\ncreate table later (a number);"), "AMB-S010");
    }

    // ----- D002 --------------------------------------------------------------------------------------

    @Test
    void repeatedDerivedTableBecomesACte() {
        String sql = DDL + """
                select x.id
                  from (select id, status from orders where status = 'A' and created > date '2020-01-01') x
                  join (select id, status from orders where status = 'A' and created > date '2020-01-01') y on y.id = x.id;
                """;
        Rewrite r = only(suggest(D, sql), "AMB-D002");
        assertEquals("""
                with shared_q1 as (select id, status from orders where status = 'A' and created > date '2020-01-01')
                select x.id
                  from shared_q1 x
                  join shared_q1 y on y.id = x.id""", r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT, r.safety());
        assertEquals("STRUCTURE_PRESERVED", r.check());
    }

    @Test
    void repeatedSubqueryJoinsAnExistingWithAndInsert() {
        String sql = """
                insert into totals
                with base as (select id from orders)
                select b.id from base b
                 where b.id in (select k.id from kinds k where k.active = 1 and k.region = 'N')
                    or b.id + 1 in (select k.id from kinds k where k.active = 1 and k.region = 'N');
                """;
        Rewrite r = only(suggest(D, sql), "AMB-D002");
        assertTrue(r.rewrittenStatement().contains("""
                with base as (select id from orders),
                shared_q1 as (select k.id from kinds k where k.active = 1 and k.region = 'N')"""), r.rewrittenStatement());
        assertTrue(r.rewrittenStatement().contains("b.id in (select * from shared_q1)"));
    }

    @Test
    void repeatedSubqueryNotFactoredWhenItDependsOnTheRow() {
        // correlated: refers to the outer alias
        none(suggest(D, "select (select max(k.id) from kinds k where k.owner = o.id) a, "
                + "(select max(k.id) from kinds k where k.owner = o.id) b from orders o;"), "AMB-D002");
        // volatile
        none(suggest(D, "select (select max(k.id) from kinds k where k.at < sysdate) a, "
                + "(select max(k.id) from kinds k where k.at < sysdate) b from dual;"), "AMB-D002");
        // unknown function (could be non-deterministic)
        none(suggest(D, "select (select max(k.id) from kinds k where k.v = my_pkg.f(1)) a, "
                + "(select max(k.id) from kinds k where k.v = my_pkg.f(1)) b from dual;"), "AMB-D002");
        // unqualified column not provably of the subquery's table
        none(suggest(D, "select (select max(k.id) from kinds k where owner = 1) a, "
                + "(select max(k.id) from kinds k where owner = 1) b from orders;"), "AMB-D002");
        // literal differs: not the same subquery
        none(suggest(D, "select (select max(k.id) from kinds k where k.v = 1) a, "
                + "(select max(k.id) from kinds k where k.v = 2) b from dual;"), "AMB-D002");
        // a DELETE: no WITH there
        none(suggest(D, "delete from orders where id in (select k.id from kinds k where k.v = 1 and k.w = 2) "
                + "or cust_id in (select k.id from kinds k where k.v = 1 and k.w = 2);"), "AMB-D002");
    }

    @Test
    void repeatedSubqueryWithKnownColumnsAndTypedWords() {
        String sql = DDL + "select (select max(created) from orders where status = 'P' and created > date '2020-01-01') a, "
                + "(select max(created) from orders where status = 'P' and created > date '2020-01-01') b from dual;";
        Rewrite r = only(suggest(D, sql), "AMB-D002");
        assertTrue(r.rewrittenStatement().startsWith("with shared_q1 as (select max(created) from orders"));
        // a column named like a unit is a column unless used as one
        none(suggest(D, "select (select max(k.id) from kinds k where k.v = year) a, "
                + "(select max(k.id) from kinds k where k.v = year) b from dual;"), "AMB-D002");
    }

    // ----- PL/SQL -------------------------------------------------------------------------------------

    @Test
    void rewritesInsideAPackageBody() {
        String sql = RewriteTestSupport.resource("oracle_package.sql");
        List<Rewrite> list = suggest(D, sql);
        Rewrite s008 = only(list, "AMB-S008");
        assertEquals("select count(*) into v_cnt from acct a where a.opened >= trunc(p_day) and a.opened < trunc(p_day) + 1",
                s008.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, s008.safety());
        Rewrite s004 = only(list, "AMB-S004");
        assertTrue(s004.rewrittenStatement().startsWith("delete from acct where not exists (select 1 from owners o "
                + "where o.id = acct.owner_id)"), s004.rewrittenStatement());
        // the unqualified variable on the left of NOT IN inside a routine is not qualified blindly
        assertEquals(1, of(list, "AMB-S004").size());
        // the cursor loop query keeps its ORDER BY
        none(list, "AMB-S012");
    }
}
