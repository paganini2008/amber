package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.amber.rewrite.RewriteTestSupport.of;
import static com.github.amber.rewrite.RewriteTestSupport.only;
import static com.github.amber.rewrite.RewriteTestSupport.suggest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The suggester's contract: statement selection, ids, edit application, conflicts, formatting and
 * comment preservation, and scripts that do not parse.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class RewriteSuggesterTest {

    private static final String SCRIPT = """
            create table orders (id number not null primary key, created date not null, status varchar2(10));
            select id from orders union select id from orders;
            select distinct status, count(*) from orders group by status;
            """;

    @Test
    void statementSelectionAndIds() {
        List<Rewrite> all = RewriteSuggester.suggest(Dialect.ORACLE, SCRIPT, new Catalog(), List.of());
        assertEquals(2, all.size());
        assertEquals(List.of(1, 2), all.stream().map(Rewrite::statement).toList());
        assertEquals(List.of("R1", "R2"), all.stream().map(Rewrite::id).toList());
        List<Rewrite> one = RewriteSuggester.suggest(Dialect.ORACLE, SCRIPT, new Catalog(), List.of(2));
        assertEquals(List.of("AMB-S011"), one.stream().map(Rewrite::rule).toList());
        assertEquals(2, RewriteSuggester.suggest(Dialect.ORACLE, SCRIPT, null, null).size());
        assertTrue(RewriteSuggester.suggest(Dialect.ORACLE, SCRIPT, new Catalog(), List.of(0)).isEmpty());
    }

    @Test
    void scriptsThatDoNotParseGetNoSuggestions() {
        assertTrue(RewriteSuggester.suggest(Dialect.ORACLE, "select id from orders union select from where;",
                new Catalog(), List.of()).isEmpty());
        assertTrue(RewriteSuggester.suggest(Dialect.ORACLE, "", new Catalog(), List.of()).isEmpty());
    }

    @Test
    void fallbackModeStillOffersRewritesForTheStatementsThatParse() {
        String sql = """
                select id from orders union select id from blocked;
                this is not sql at all;
                select distinct status, count(*) from orders group by status;
                """;
        assertTrue(RewriteSuggester.suggest(Dialect.ORACLE, sql, new Catalog(), List.of()).isEmpty());
        List<Rewrite> list = RewriteSuggester.suggest(Dialect.ORACLE, sql, new Catalog(), List.of(), true);
        assertEquals(List.of("AMB-S009", "AMB-S011"), list.stream().map(Rewrite::rule).toList());
        list.forEach(r -> RewriteTestSupport.sane(sql, r));
    }

    @Test
    void theImportedCatalogIsNotChanged() {
        Catalog c = new Catalog();
        RewriteSuggester.suggest(Dialect.ORACLE, SCRIPT, c, List.of());
        assertEquals(0, c.size());
    }

    @Test
    void applyAndEdits() {
        SourceText s = SourceText.of("abcdef");
        Edit a = new Edit(s.range(1, 2), "X");
        Edit b = new Edit(s.range(4, 4), "Y");
        assertEquals("aXcdYef", RewriteSuggester.apply("abcdef", List.of(b, a)));
        assertEquals(1, a.start());
        assertEquals(2, a.end());
        assertFalse(a.overlaps(b));
        assertTrue(a.overlaps(new Edit(s.range(1, 1), "")));         // same start
        assertTrue(a.overlaps(new Edit(s.range(0, 3), "")));
        assertFalse(a.overlaps(new Edit(s.range(2, 2), "")));        // insertion right after
        assertThrows(IllegalArgumentException.class,
                () -> RewriteSuggester.apply("abcdef", List.of(a, new Edit(s.range(0, 3), ""))));
        assertThrows(IllegalArgumentException.class,
                () -> RewriteSuggester.apply("abc", List.of(new Edit(s.range(2, 6), ""))));
        assertThrows(IllegalArgumentException.class, () -> new Edit(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Edit(s.range(0, 1), null));
    }

    @Test
    void severalRewritesInOneStatementDoNotOverlap() {
        String sql = """
                create table orders (id number not null primary key, cust_id number not null, created date not null);
                create table blocked (cust_id number not null, d date not null);
                select o.id from orders o
                 where o.cust_id not in (select b.cust_id from blocked b where trunc(b.d) = date '2024-01-01')
                   and trunc(o.created) = date '2024-01-02';
                """;
        List<Rewrite> list = suggest(Dialect.ORACLE, sql);
        assertEquals(3, list.size());
        list.forEach(r -> assertNull(r.conflictsWith()));
        List<Edit> all = list.stream().flatMap(r -> r.edits().stream()).toList();
        String applied = RewriteSuggester.apply(sql, all);
        assertTrue(applied.contains("""
                 where not exists (select 1 from blocked b where b.d >= date '2024-01-01' and b.d < date '2024-01-02' \
                and b.cust_id = o.cust_id)
                   and o.created >= date '2024-01-02' and o.created < date '2024-01-03';"""), applied);
    }

    @Test
    void overlappingRewritesAreMarkedAsConflicting() {
        String sql = """
                create table orders (id number not null primary key, status varchar2(10));
                select x.id
                  from (select id from orders where status = 'A' order by id) x
                  join (select id from orders where status = 'A' order by id) y on y.id = x.id;
                """;
        List<Rewrite> list = suggest(Dialect.ORACLE, sql);
        Rewrite cte = only(list, "AMB-D002");
        assertNull(cte.conflictsWith());
        List<Rewrite> orders = of(list, "AMB-S012");
        assertEquals(2, orders.size());
        orders.forEach(r -> assertEquals(cte.id(), r.conflictsWith()));
        assertEquals("STRUCTURE_CHANGED", cte.check());
    }

    @Test
    void formattingCommentsAndKeywordCaseArePreserved() {
        String sql = """
                SELECT O.ID
                  FROM ORDERS O
                  LEFT /* keep */ JOIN CUSTOMERS C -- the customer
                    ON C.ID = O.CUST_ID
                 WHERE C.REGION = 'N';
                """;
        Rewrite r = only(suggest(Dialect.ORACLE, sql), "AMB-S002");
        assertEquals("""
                SELECT O.ID
                  FROM ORDERS O
                  /* keep */ JOIN CUSTOMERS C -- the customer
                    ON C.ID = O.CUST_ID
                 WHERE C.REGION = 'N'""", r.rewrittenStatement());

        String order = """
                select x.id from (
                    select id
                      from orders -- newest first
                     order by created desc
                ) x;
                """;
        Rewrite o = only(suggest(Dialect.ORACLE, order), "AMB-S012");
        assertEquals("""
                select x.id from (
                    select id
                      from orders -- newest first
                ) x""".replace("first\n) x", "first\n     \n) x"), o.rewrittenStatement());

        Rewrite upper = only(suggest(Dialect.ORACLE, "SELECT ID FROM ORDERS WHERE ID NOT IN (SELECT ID FROM BLOCKED);"),
                "AMB-S004");
        assertEquals("SELECT ID FROM ORDERS WHERE NOT EXISTS (SELECT 1 FROM BLOCKED WHERE ID = ORDERS.ID)",
                upper.rewrittenStatement());
    }

    @Test
    void rewritesCarryExplanations() {
        Rewrite r = only(suggest(Dialect.ORACLE, SCRIPT), "AMB-S009");
        assertNotNull(r.title());
        assertTrue(r.explanation().contains("UNION"));
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, r.safety());   // keyed, but both branches read the same rows
    }
}
