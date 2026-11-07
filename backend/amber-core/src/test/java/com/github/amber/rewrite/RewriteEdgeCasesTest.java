package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.amber.rewrite.RewriteTestSupport.catalog;
import static com.github.amber.rewrite.RewriteTestSupport.none;
import static com.github.amber.rewrite.RewriteTestSupport.only;
import static com.github.amber.rewrite.RewriteTestSupport.suggest;
import static com.github.amber.rewrite.RewriteTestSupport.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Less common shapes: verification failures, unusual FROM items, quoting, set-operation chains,
 * CTE placement and the places where a rule must step back.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class RewriteEdgeCasesTest {

    // ----- verification -------------------------------------------------------------------------------

    private record Fixture(Verifier verifier, String text, List<Statement> steps, Catalog catalog, Dialect dialect) {

        RewriteContext ctx(int id) {
            Statement st = steps.get(id);
            Sql sql = new Sql(dialect, text, st.range().start().offset(), st.range().end().offset());
            return new RewriteContext(dialect, SourceText.of(text), st, id, sql, catalog, null, Map.of(), Map.of(), steps);
        }

        Edit replace(String what, String with) {
            int at = text.indexOf(what);
            return new Edit(SourceText.of(text).range(at, at + what.length()), with);
        }
    }

    private static Fixture fixture(Dialect d, String text) {
        DialectFrontend f = DialectFrontends.get(d);
        ScriptParse parse = f.parse(SourceText.of(text), ParseOptions.STRICT);
        Catalog base = new Catalog();
        Catalog sc = base.copy();
        Script s = ScriptIrBuilder.build(f, parse, sc);
        return new Fixture(new Verifier(d, f, text, sc, base, parse.diagnostics().size() + s.diagnostics().size()), text,
                s.flatten(), sc, d);
    }

    private static Candidate candidate(Safety safety, Edit... edits) {
        return new Candidate("AMB-TEST", "t", "e", List.of(edits), safety, "n");
    }

    @Test
    void verifierDropsBrokenOrUnexpectedRewrites() {
        Fixture fx = fixture(Dialect.ORACLE, "select id from orders where status = 'A';");
        RewriteContext ctx = fx.ctx(0);
        // does not parse any more
        assertNull(fx.verifier().verify(ctx, candidate(Safety.EQUIVALENT, fx.replace("from", "frm"))));
        // overlapping edits cannot be applied
        assertNull(fx.verifier().verify(ctx, candidate(Safety.EQUIVALENT, fx.replace("from", "x"),
                fx.replace("from orders", "y"))));
        // declared equivalent, but the output column changed
        assertNull(fx.verifier().verify(ctx, candidate(Safety.EQUIVALENT, fx.replace("id", "status"))));
        // the same change offered as a behavior change is kept, labeled
        Verifier.Outcome kept = fx.verifier().verify(ctx, candidate(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES,
                fx.replace("id", "status")));
        assertNotNull(kept);
        assertEquals("STRUCTURE_CHANGED", kept.label());
        assertEquals("select status from orders where status = 'A'", kept.rewrittenStatement());
    }

    @Test
    void verifierChecksBodyStatementsInsideTheirScript() {
        String text = """
                create table events (id bigint primary key, kind text);
                create or replace function f() returns bigint as $$
                declare
                  v bigint;
                begin
                  select count(*) into strict v from events where kind = 'a';
                  return v;
                end;
                $$ language plpgsql;
                """;
        Fixture fx = fixture(Dialect.POSTGRESQL, text);
        int id = 0;
        while (!text.substring(fx.steps().get(id).range().start().offset()).startsWith("select count")) {
            id++;
        }
        RewriteContext ctx = fx.ctx(id);
        Verifier.Outcome ok = fx.verifier().verify(ctx, candidate(Safety.EQUIVALENT, fx.replace("kind = 'a'", "'a' = kind")));
        assertNotNull(ok);
        assertEquals("STRUCTURE_PRESERVED", ok.label());
        assertNull(fx.verifier().verify(ctx, candidate(Safety.EQUIVALENT, fx.replace("from events where", "frm events wher"))));
    }

    // ----- unusual FROM items ----------------------------------------------------------------------

    @Test
    void fromItemsThatAreNotPlainTables() {
        Dialect d = Dialect.ORACLE;
        // table function, database link, derived table: the LEFT JOIN rule only needs the alias
        only(suggest(d, "select a.id from table(f_rows()) a left join t@remote r on r.id = a.id where r.v = 1;"),
                "AMB-S002");
        only(suggest(d, "select a.id from (select id from t) a left join u r on r.id = a.id where r.v = 1;"), "AMB-S002");
        // NOT IN needs a known outer item to qualify an unqualified column
        none(suggest(d, "select 1 from table(f_rows()) where id not in (select x from u);"), "AMB-S004");
        // PostgreSQL column alias list: the item renames columns, so * is not expanded
        none(suggest(Dialect.POSTGRESQL, "create table t (a int, b int);\n"
                + "insert into u select * from t x(c, d);"), "AMB-S010");
        // MySQL index hint after the table
        only(suggest(Dialect.MYSQL, "select v.id from visits v use index (ix_v) left join pages p on p.id = v.pid "
                + "where p.kind = 'blog';"), "AMB-S002");
        // RIGHT JOIN is not a LEFT JOIN
        none(suggest(d, "select a.id from a right join b on b.id = a.id where a.v = 1;"), "AMB-S002");
    }

    // ----- SELECT * quoting and layout ---------------------------------------------------------------

    @Test
    void longColumnListsWrapUnderTheFirstColumn() {
        StringBuilder ddl = new StringBuilder("create table wide (");
        for (int i = 1; i <= 16; i++) {
            ddl.append(i > 1 ? ", " : "").append("column_number_").append(i).append(" int");
        }
        ddl.append(");\n");
        Rewrite r = only(suggest(Dialect.POSTGRESQL, ddl + "insert into wide_copy\n    select * from wide;"), "AMB-S010");
        String[] lines = r.rewrittenStatement().split("\n");
        assertTrue(lines.length >= 4, r.rewrittenStatement());
        assertTrue(lines[1].startsWith("    select column_number_1, column_number_2,"));
        assertTrue(lines[2].startsWith("           column_number_"), lines[2]);
        for (String line : lines) {
            assertTrue(line.length() <= 100, line);
        }
    }

    @Test
    void namesAreQuotedPerDialect() {
        Rewrite pg = only(suggest(Dialect.POSTGRESQL, "create view v as select * from app.t;",
                catalog(table("app", "t", List.of(), "id int", "CamelCase int", "order int"))), "AMB-S010");
        assertEquals("create view v as select id, \"CamelCase\", \"order\" from app.t", pg.rewrittenStatement());
        TableMeta spaced = new TableMeta(null, "dbo", "t", "TABLE", List.of(new TableMeta.ColumnMeta("id", "int", false),
                new TableMeta.ColumnMeta("my col", "int", true)), List.of(), List.of(), List.of(), -1, "pack");
        Rewrite ss = only(suggest(Dialect.SQLSERVER, "create view v as select * from dbo.t;", Catalog.of(List.of(spaced))),
                "AMB-S010");
        assertEquals("create view v as select id, [my col] from dbo.t;", ss.rewrittenStatement());
        Rewrite script = only(suggest(Dialect.POSTGRESQL, "create table t (id int, comment text);\n"
                + "create view v as select * from t;"), "AMB-S010");
        assertEquals("create view v as select id, \"comment\" from t", script.rewrittenStatement());
        Rewrite upper = only(suggest(Dialect.DB2, "create table t (id int, comment varchar(9));\n"
                + "create view v as select * from t;"), "AMB-S010");
        assertEquals("create view v as select id, \"COMMENT\" from t", upper.rewrittenStatement());
        Rewrite mixed = only(suggest(Dialect.MYSQL, "create view v as select * from t;",
                catalog(table(null, "t", List.of(), "Id int", "order int"))), "AMB-S010");
        assertEquals("create view v as select Id, `order` from t", mixed.rewrittenStatement());
    }

    // ----- UNION chains -------------------------------------------------------------------------------

    @Test
    void unionProofsWithKeysAndShapes() {
        Dialect d = Dialect.ORACLE;
        TableMeta uk = new TableMeta(null, null, "codes", "TABLE", List.of(new TableMeta.ColumnMeta("code", "varchar2(5)",
                false), new TableMeta.ColumnMeta("alt", "varchar2(5)", true)), List.of(), List.of(List.of("code"),
                List.of("alt")), List.of(), -1, "pack");
        Catalog c = Catalog.of(List.of(uk));
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, only(suggest(d,
                "select 1 k, code from codes union select 2.0 k, code from codes;", c), "AMB-S009").safety());
        // unique but nullable key: several NULL rows are possible
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "select 1 k, alt from codes union select 2 k, alt from codes;", c), "AMB-S009").safety());
        // same value written differently is not a difference
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "select 1 k, code from codes union select 1.0 k, code from codes;", c), "AMB-S009").safety());
        // different widths, parenthesized branches, a branch without metadata, joins
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "(select 1 k, code from codes) union (select 2 k, code from codes);", c), "AMB-S009").safety());
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "select 1 k, code from codes union select 2 k, id from other;", c), "AMB-S009").safety());
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "select 1 k, a.code from codes a join codes b on b.code = a.code union select 2 k, code from codes;", c),
                "AMB-S009").safety());
        // a later INTERSECT stops the proof
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "select 1 k, code from codes union select 2 k, code from codes intersect select 2 k, code from codes;", c),
                "AMB-S009").safety());
        // membership with ORDER BY in the set is not plain membership
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "select 1 from dual where 'x' in (select code from codes union select alt from codes order by 1);", c),
                "AMB-S009").safety());
    }

    // ----- repeated subqueries ----------------------------------------------------------------------

    @Test
    void cteGoesAfterTheViewHeaderAndIntoRecursiveWiths() {
        String view = "create view v as select (select max(k.id) from kinds k where k.v = 1 and k.w = 2) a, "
                + "(select max(k.id) from kinds k where k.v = 1 and k.w = 2) b from dual;";
        Rewrite r = only(suggest(Dialect.ORACLE, view), "AMB-D002");
        assertTrue(r.rewrittenStatement().startsWith("create view v as with shared_q1 as (select max(k.id) from kinds k "
                + "where k.v = 1 and k.w = 2)\nselect"), r.rewrittenStatement());

        String rec = """
                with recursive tree (id, parent) as (select n.id, n.parent from nodes n where n.parent is null)
                select t.id from tree t
                 where t.id in (select k.id from kinds k where k.v = :x and k.w = 2 order by k.id nulls first)
                   and t.parent in (select k.id from kinds k where k.v = :x and k.w = 2 order by k.id nulls first);
                """;
        Rewrite rr = only(suggest(Dialect.POSTGRESQL, rec), "AMB-D002");
        assertTrue(rr.rewrittenStatement().contains("n.parent is null),\nshared_q1 as (select k.id"), rr.rewrittenStatement());
    }

    @Test
    void cteNotAddedWhereOrderWouldBreak() {
        // the repeated subquery sits inside an existing CTE body
        none(suggest(Dialect.POSTGRESQL, """
                with a as (select x.id from t x where x.id in (select k.id from kinds k where k.v = 1 and k.w = 2)
                                                  or x.id in (select k.id from kinds k where k.v = 1 and k.w = 2))
                select id from a;
                """), "AMB-D002");
        // MATERIALIZED CTE header is understood; a CTE header that is not is left alone
        only(suggest(Dialect.POSTGRESQL, """
                with a as materialized (select 1 as id)
                select id from a where id in (select k.id from kinds k where k.v = 1 and k.w = 2)
                    or id in (select k.id from kinds k where k.v = 1 and k.w = 2);
                """), "AMB-D002");
        // aliases inside the subquery, a qualifier from outside, and a window function
        only(suggest(Dialect.ORACLE, "select (select max(k.id) as m from kinds k where k.v = 1 and k.w = 2) a, "
                + "(select max(k.id) as m from kinds k where k.v = 1 and k.w = 2) b from dual;"), "AMB-D002");
        none(suggest(Dialect.ORACLE, "select (select max(k.id) over () from kinds k where k.v = 1 and k.w = 2) a, "
                + "(select max(k.id) over () from kinds k where k.v = 1 and k.w = 2) b from dual;"), "AMB-D002");
        none(suggest(Dialect.ORACLE, "select (select max(k.id) from kinds k where k.v = 1 and k.w = x.y) a, "
                + "(select max(k.id) from kinds k where k.v = 1 and k.w = x.y) b from dual x;"), "AMB-D002");
        // nested copies: only the outer group is factored
        String nested = "select (select max(k.id) from kinds k where k.v in (select j.v from js j where j.a = 1 and j.b = 2)) a, "
                + "(select max(k.id) from kinds k where k.v in (select j.v from js j where j.a = 1 and j.b = 2)) b from dual;";
        Rewrite outer = only(suggest(Dialect.ORACLE, nested), "AMB-D002");
        assertTrue(outer.rewrittenStatement().contains("(select * from shared_q1) a"), outer.rewrittenStatement());
    }

    // ----- ORDER BY contexts --------------------------------------------------------------------------

    @Test
    void orderByContexts() {
        Dialect d = Dialect.ORACLE;
        only(suggest(d, "select id from t where v = (select max(x) from u order by 1);"), "AMB-S012");
        only(suggest(d, "select id from t where v > all (select x from u order by 1);"), "AMB-S012");
        only(suggest(d, "select a.id from t a, (select x from u order by x) b where b.x = a.id;"), "AMB-S012");
        only(suggest(d, "with c (x) as (select x from u order by x) select x from c;"), "AMB-S012");
        only(suggest(d, "select (select max(x) from u order by 1) + 1 as m from dual;"), "AMB-S012");
        // unaliased, the output column is named after its text, which the edit would change
        none(suggest(d, "select (select max(x) from u order by 1) + 1 from dual;"), "AMB-S012");
        // a parenthesized branch of a set operation, or a whole parenthesized statement
        none(suggest(d, "(select x from u order by x) union all (select y from v);"), "AMB-S012");
        none(suggest(Dialect.POSTGRESQL, "with c as materialized (select x from u order by x) select x from c;"),
                "AMB-S012");
        none(suggest(d, "select id from t start with id = 1 connect by prior id = parent and id in "
                + "(select x from u order by x);"), "AMB-S012");
    }

    // ----- dates -------------------------------------------------------------------------------------

    @Test
    void dateRangeOwnersAndOddValues() {
        Dialect d = Dialect.ORACLE;
        Catalog c = catalog(table(null, "a", List.of(), "id number!", "created date!"),
                table(null, "b", List.of(), "id number!"));
        Rewrite owned = only(suggest(d, "select a.id from a join b on b.id = a.id where trunc(created) = date '2024-01-01';",
                c), "AMB-S008");
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, owned.safety());
        Catalog both = catalog(table(null, "a", List.of(), "created date!"), table(null, "b", List.of(), "created date!"));
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "select 1 from a join b on b.id = a.id where trunc(created) = date '2024-01-01';", both), "AMB-S008").safety());
        Rewrite day = only(suggest(d, "delete from a where trunc(created) = trunc(sysdate - 1, 'DD');", c), "AMB-S008");
        assertEquals("delete from a where created >= trunc(sysdate - 1, 'DD') and created < trunc(sysdate - 1, 'DD') + 1",
                day.rewrittenStatement());
        assertEquals(Safety.EQUIVALENT_WITH_METADATA, day.safety());
        none(suggest(d, "select 1 from a where trunc(created) = date '2024-02-30';"), "AMB-S008");
        none(suggest(d, "select 1 from a where trunc(created) between date '2024-01-01' or 1 = 1;"), "AMB-S008");
        none(suggest(d, "select 1 from a where trunc(created) = 5;"), "AMB-S008");
        Rewrite mm = only(suggest(d, "select 1 from a where trunc(created) = trunc(sysdate, 'MM');", c), "AMB-S008");
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, mm.safety());
    }

    // ----- other rule edges ---------------------------------------------------------------------------

    @Test
    void notInAndDistinctEdges() {
        Dialect d = Dialect.ORACLE;
        Catalog c = catalog(table(null, "a", List.of(), "id number!", "ref number!"),
                table(null, "b", List.of(), "id number!", "other number!"), table(null, "z", List.of(), "ref number!"));
        Rewrite r = only(suggest(d, "select 1 from a, b where ref not in (select z.ref from z);", c), "AMB-S004");
        assertTrue(r.rewrittenStatement().contains("z.ref = a.ref"), r.rewrittenStatement());
        none(suggest(d, "select 1 from a, b where id not in (select z.ref from z);", c), "AMB-S004");
        // IS NOT NULL inside an OR does not make the column NOT NULL
        Catalog nz = catalog(table(null, "a", List.of(), "ref number!"), table(null, "z", List.of(), "ref number"));
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, only(suggest(d,
                "select 1 from a where ref not in (select z.ref from z where z.ref is not null or z.ref > 0);", nz),
                "AMB-S004").safety());
        // Oracle's UNIQUE is a synonym the rule does not touch; HAVING and CONNECT BY are understood
        none(suggest(d, "select unique status, count(*) from t group by status;"), "AMB-S011");
        only(suggest(d, "select distinct status, count(*) from t group by status having count(*) > 1;"), "AMB-S011");
        none(suggest(d, "select distinct status from t group by status, x.y;"), "AMB-S011");
        only(suggest(Dialect.SQLSERVER, "select distinct top (5) with ties t.status from t group by t.status;"),
                "AMB-S011");
        none(suggest(Dialect.POSTGRESQL, "select distinct status from t group by status window w as (order by status);"),
                "AMB-S011");
    }

    @Test
    void clientCommandsAndIgnoredRulesAreSkipped() {
        String sql = """
                set serveroutput on
                -- amber:ignore AMB-S009, AMB-S011
                select distinct status, count(*) from t group by status union select 'x', 1 from dual;
                select id from t union select id from u;
                """;
        List<Rewrite> list = suggest(Dialect.ORACLE, sql);
        assertEquals(List.of("AMB-S009"), list.stream().map(Rewrite::rule).toList());
        assertTrue(list.getFirst().rewrittenStatement().startsWith("select id from t union all"));
    }
}
