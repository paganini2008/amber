package com.github.amber.rewrite;

import com.github.amber.core.Dialect;
import com.github.amber.core.lineage.Catalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rewrite.optimize}: every safe rewrite of a statement applied together, to a fixpoint, verified.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class OptimizerTest {

    private static Optimizer.Result run(Dialect d, String sql, boolean edge, Predicate<String> on) {
        return Optimizer.optimize(d, sql, new Catalog(), List.of(), edge, on, false, Map.of());
    }

    @Test
    void equivalentRewritesAreAppliedTogetherAndUnsafeOnesOnlyOnRequest() {
        String sql = """
                select distinct status, count(*) from orders group by status;
                select a from t union select a from u;
                select 1 from dual;
                """;
        Optimizer.Result r = run(Dialect.ORACLE, sql, false, x -> true);
        assertEquals(1, r.statements().size(), r::toString);
        Optimizer.StatementResult s = r.statements().get(0);
        assertEquals(0, s.statement());
        assertEquals("select status, count(*) from orders group by status", s.optimized());
        assertEquals(Safety.EQUIVALENT, s.safety());
        assertEquals("STRUCTURE_CHANGED", s.check().label(), s.check()::toString);
        assertEquals(List.of("Ordering and row limits: [DISTINCT] → []"), s.check().differences());
        Optimizer.Result skippedUnion = run(Dialect.ORACLE, "select a from t union select a from u;", false, x -> true);
        assertTrue(skippedUnion.statements().isEmpty(), "UNION → UNION ALL changes results in edge cases");
        Optimizer.Result edge = run(Dialect.ORACLE, sql, true, x -> true);
        assertEquals(2, edge.statements().size());
        assertEquals(Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, edge.statements().get(1).safety());
        assertTrue(edge.optimizedText().contains("union all"), edge::optimizedText);
        assertTrue(edge.optimizedText().contains("select status, count(*) from orders group by status;"));
        assertTrue(edge.optimizedText().endsWith("select 1 from dual;\n"));
    }

    @Test
    void severalRewritesOfOneStatementAndAFixpoint() {
        // S011 (DISTINCT) and S012 (ORDER BY in the derived table) apply together in pass 1
        String sql = "select distinct x.status, count(*) from (select status from orders order by status) x "
                + "group by x.status;";
        Optimizer.Result r = run(Dialect.POSTGRESQL, sql, false, x -> true);
        Optimizer.StatementResult s = r.statements().get(0);
        assertEquals(List.of("AMB-S011", "AMB-S012"), s.applied().stream().map(Optimizer.Applied::rule).sorted().toList(),
                s::toString);
        assertEquals("select x.status, count(*) from (select status from orders) x group by x.status", s.optimized());
        assertTrue(s.skipped().isEmpty(), s::toString);
    }

    @Test
    void disabledRulesAndStatementSelection() {
        String sql = "select distinct status, count(*) from orders group by status;\n"
                + "select distinct region, count(*) from orders group by region;\n";
        Optimizer.Result off = run(Dialect.ORACLE, sql, false, rule -> !rule.equals("AMB-S011"));
        assertTrue(off.statements().isEmpty());
        assertEquals(sql, off.optimizedText());
        Optimizer.Result one = Optimizer.optimize(Dialect.ORACLE, sql, new Catalog(), List.of(1), false, null, false,
                null);
        assertEquals(List.of(1), one.statements().stream().map(Optimizer.StatementResult::statement).toList());
        assertTrue(one.optimizedText().startsWith("select distinct status"));
    }

    @Test
    void skippedRewritesSayWhy() {
        String sql = "select distinct status, count(*) from orders group by status union select 'x', 1 from dual;";
        Optimizer.Result r = Optimizer.optimize(Dialect.ORACLE, sql, new Catalog(), null, false,
                rule -> !rule.equals("AMB-S011"), false, Map.of(0, List.of("CREATE INDEX ix ON orders (status)")));
        Optimizer.StatementResult s = r.statements().get(0);
        assertTrue(s.applied().isEmpty());
        assertEquals(sql.substring(0, sql.length() - 1), s.optimized());
        assertNull(s.safety());
        assertNull(s.check());
        assertEquals(List.of("CREATE INDEX ix ON orders (status)"), s.additionalDdl());
        List<String> reasons = s.skipped().stream().map(x -> x.rule() + ":" + x.reason()).sorted().toList();
        assertEquals(List.of("AMB-S009:EDGE_CASE", "AMB-S011:DISABLED"), reasons);
    }

    @Test
    void conflictingRewritesAreListedAndScriptsThatDoNotParseAreLeftAlone() {
        // S002 (WHERE on the outer table) and S004 (NOT IN) touch different parts; two NOT IN in one
        // predicate give rewrites whose edits do not overlap, so both apply
        String sql = "select v.id from v left join p on p.id = v.pid where p.kind = 'x' "
                + "and v.a not in (select b.a from b) and v.c not in (select c.c from c);";
        Optimizer.Result r = run(Dialect.POSTGRESQL, sql, true, x -> true);
        Optimizer.StatementResult s = r.statements().get(0);
        assertTrue(s.applied().size() >= 2, s::toString);
        assertNotNull(s.check());
        Optimizer.Result broken = run(Dialect.POSTGRESQL, "select from where;", true, x -> true);
        assertTrue(broken.statements().isEmpty());
        assertEquals("select from where;", broken.optimizedText());
        Optimizer.Result fallback = Optimizer.optimize(Dialect.ORACLE, "this is not sql;\nselect distinct a, count(*) "
                + "from t group by a;\n", new Catalog(), null, false, null, true, null);
        assertEquals(1, fallback.statements().size());
    }

    @Test
    void conflictsBetweenOverlappingRewrites() {
        // D002 (repeated subquery → CTE) and S004 (NOT IN → NOT EXISTS) both edit the same subqueries
        String sql = "select a from t where a not in (select x from s where s.k = 1) "
                + "and b not in (select x from s where s.k = 1);";
        Optimizer.Result r = run(Dialect.POSTGRESQL, sql, true, x -> true);
        Optimizer.StatementResult s = r.statements().get(0);
        assertTrue(!s.applied().isEmpty(), s::toString);
        assertTrue(s.optimized().length() > 0);
        assertTrue(s.applied().stream().allMatch(a -> a.id().startsWith("R") || a.id().contains(":R")), s::toString);
    }

    @Test
    void combinationsThatDoNotVerifyAreDropped() {
        String sql = "select distinct status, count(*) from orders group by status;\n"
                + "select distinct region, count(*) from orders group by region;\n";
        // every edited text is broken: nothing is applied, the rewrites are listed as UNVERIFIED
        Optimizer.Result none = Optimizer.optimize(Dialect.ORACLE, sql, new Catalog(), null, false, null, false, null,
                t -> t.replace("group by", "group grp"));
        assertTrue(none.statements().isEmpty(), none::toString);
        assertEquals(sql, none.optimizedText());
        // only the second statement's edit breaks: the first is still applied statement by statement
        Optimizer.Result some = Optimizer.optimize(Dialect.ORACLE, sql, new Catalog(), null, false, null, false,
                Map.of(1, List.of("CREATE INDEX ix_orders_region ON orders (region)")),
                t -> t.contains("select region") ? t.replace("group by region", "group region") : t);
        assertEquals(2, some.statements().size(), some::toString);
        assertEquals("select status, count(*) from orders group by status", some.statements().get(0).optimized());
        Optimizer.StatementResult second = some.statements().get(1);
        assertTrue(second.applied().isEmpty());
        assertEquals("UNVERIFIED", second.skipped().get(0).reason(), second::toString);
    }
}
