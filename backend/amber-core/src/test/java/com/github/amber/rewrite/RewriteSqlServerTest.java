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
 * Rewrite rules on SQL Server scripts, including T-SQL procedure bodies and GO batches.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class RewriteSqlServerTest {

    private static final Dialect D = Dialect.SQLSERVER;
    private static final String DDL = "create table dbo.sales (id int not null primary key, sold_at datetime2 not null, "
            + "region varchar(10), amount decimal(10,2));\n";

    @Test
    void leftJoinWithTableHints() {
        Rewrite r = only(suggest(D, "select s.id from dbo.sales s with (nolock) left outer join dbo.regions r with (nolock) "
                + "on r.code = s.region where r.zone = 'EU';"), "AMB-S002");
        assertEquals("select s.id from dbo.sales s with (nolock) join dbo.regions r with (nolock) on r.code = s.region "
                + "where r.zone = 'EU';", r.rewrittenStatement());
        none(suggest(D, "select s.id from dbo.sales s left join dbo.regions r on r.code = s.region where r.code is null;"),
                "AMB-S002");
    }

    @Test
    void notInWithBracketIdentifiers() {
        Rewrite r = only(suggest(D, DDL + "select [id] from [dbo].[sales] where [region] not in "
                + "(select [code] from [dbo].[regions]);"), "AMB-S004");
        assertEquals("select [id] from [dbo].[sales] where not exists (select 1 from [dbo].[regions] where [code] = "
                + "[sales].[region]);", r.rewrittenStatement());
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, r.safety());
        none(suggest(D, "select id from dbo.sales where @region not in (select code from dbo.regions);"), "AMB-S004");
    }

    @Test
    void notInOnUpdateTarget() {
        var meta = catalog(table("dbo", "sales", List.of("id"), "id int!", "region varchar(10)!"),
                table("dbo", "regions", List.of("code"), "code varchar(10)!"));
        Rewrite r = only(suggest(D, "update dbo.sales set amount = 0 where region not in (select code from dbo.regions);",
                meta), "AMB-S004");
        assertEquals("update dbo.sales set amount = 0 where not exists (select 1 from dbo.regions where code = "
                + "sales.region);", r.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, r.safety());
        // UPDATE … FROM has several tables: left alone
        none(suggest(D, "update s set amount = 0 from dbo.sales s join dbo.x x on x.id = s.id "
                + "where region not in (select code from dbo.regions);"), "AMB-S004");
    }

    @Test
    void castAndConvertToDateBecomeRanges() {
        Rewrite cast = only(suggest(D, DDL + "select id from dbo.sales where cast(sold_at as date) = '2024-02-01';"),
                "AMB-S008");
        assertEquals("select id from dbo.sales where sold_at >= '2024-02-01' and sold_at < '2024-02-02';",
                cast.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, cast.safety());
        Rewrite conv = only(suggest(D, DDL + "select id from dbo.sales s where CONVERT(DATE, s.sold_at) = @day;"), "AMB-S008");
        assertEquals("select id from dbo.sales s where s.sold_at >= @day AND s.sold_at < DATEADD(DAY, 1, @day);",
                conv.rewrittenStatement());
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, conv.safety());
        Rewrite today = only(suggest(D, DDL + "select id from dbo.sales where cast(sold_at as date) = cast(getdate() as date);"),
                "AMB-S008");
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, today.safety());
        Rewrite ge = only(suggest(D, DDL + "select id from dbo.sales where cast(sold_at as date) >= @day;"), "AMB-S008");
        assertTrue(ge.safetyNote().contains("boundary day"));
        var offset = catalog(table("dbo", "sales", List.of(), "sold_at datetimeoffset"));
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(D,
                "select id from dbo.sales where cast(sold_at as date) = '2024-02-01';", offset), "AMB-S008").safety());
        none(suggest(D, "select id from dbo.sales where convert(varchar(10), sold_at, 120) = '2024-02-01';"), "AMB-S008");
    }

    @Test
    void selectIntoTempTableListsColumns() {
        Rewrite r = only(suggest(D, DDL + "select * into #t from dbo.sales;"), "AMB-S010");
        assertEquals("select id, sold_at, region, amount into #t from dbo.sales;", r.rewrittenStatement());
    }

    @Test
    void orderByRules() {
        Rewrite cte = only(suggest(D, "with t as (select id from dbo.sales order by id) select id from t;"), "AMB-S012");
        assertEquals("with t as (select id from dbo.sales) select id from t;", cte.rewrittenStatement());
        none(suggest(D, "select x.id from (select top 100 percent id from dbo.sales order by id) x;"), "AMB-S012");
        none(suggest(D, "select stuff((select ',' + region from (select region from dbo.sales order by region) r "
                + "for xml path('')), 1, 1, '') as regions;"), "AMB-S012");
    }

    @Test
    void repeatedSubqueryGoesBeforeInsert() {
        String sql = """
                insert into dbo.archive (id, region)
                select s.id, s.region from dbo.sales s
                 where s.region in (select r.code from dbo.regions r where r.active = 1 and r.zone = 'EU')
                    or s.id in (select r.code from dbo.regions r where r.active = 1 and r.zone = 'EU');
                """;
        Rewrite r = only(suggest(D, sql), "AMB-D002");
        assertTrue(r.rewrittenStatement().startsWith("with shared_q1 as (select r.code from dbo.regions r where "
                + "r.active = 1 and r.zone = 'EU')\ninsert into dbo.archive"), r.rewrittenStatement());
        assertEquals("STRUCTURE_PRESERVED", r.check());
    }

    @Test
    void unionAndDistinct() {
        Rewrite u = only(suggest(D, "select region from dbo.sales union select code from dbo.regions;"), "AMB-S009");
        assertEquals("select region from dbo.sales union all select code from dbo.regions;", u.rewrittenStatement());
        Rewrite d = only(suggest(D, "select distinct top 10 region, count(*) as n from dbo.sales group by region;"),
                "AMB-S011");
        assertEquals("select top 10 region, count(*) as n from dbo.sales group by region;", d.rewrittenStatement());
    }

    @Test
    void rewritesInsideAProcedure() {
        String sql = RewriteTestSupport.resource("tsql_procedure.sql");
        List<Rewrite> list = suggest(D, sql);
        Rewrite s010 = only(list, "AMB-S010");
        assertTrue(s010.rewrittenStatement().startsWith("insert into dbo.sales_hist select id, sold_at, region, amount "
                + "from dbo.sales"), s010.rewrittenStatement());
        Rewrite s008 = only(list, "AMB-S008");
        assertTrue(s008.rewrittenStatement().contains("sold_at >= cast(getdate() as date) and sold_at < dateadd(day, 1, "
                + "cast(getdate() as date))"), s008.rewrittenStatement());
        // both touch the same statement but not the same text
        assertEquals(null, s008.conflictsWith());
        assertEquals(s010.statement(), s008.statement());
        only(list, "AMB-S011");
    }
}
