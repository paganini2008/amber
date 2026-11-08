package com.github.amber.dialect.findings;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ScriptParse;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each findings rule fires where expected and not where it should not.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class FindingsTest {

    private static List<Finding> findings;

    @BeforeAll
    static void build() throws IOException {
        DialectFrontend oracle = DialectFrontends.get(Dialect.ORACLE);
        String sql = new String(FindingsTest.class.getResourceAsStream("/sql/findings/risky_oracle.sql").readAllBytes(),
                StandardCharsets.UTF_8);
        ScriptParse parse = oracle.parse(SourceText.of(sql));
        assertEquals(ScriptStatus.SUCCESS, parse.status(), () -> parse.diagnostics().toString());
        Script script = ScriptIrBuilder.build(oracle, parse);
        findings = Findings.of(script);
    }

    /** rule id → lines where it fired. */
    private static Map<String, List<Integer>> byRule() {
        return findings.stream().collect(Collectors.groupingBy(Finding::rule,
                Collectors.mapping(f -> f.range().start().line(), Collectors.toList())));
    }

    @Test
    void everyRuleFiresOnItsLine() {
        Map<String, List<Integer>> r = byRule();
        assertEquals(List.of(2), r.get("AMB-S001"), r::toString);
        assertEquals(List.of(3), r.get("AMB-S002"), r::toString);
        assertEquals(List.of(5), r.get("AMB-S003"), r::toString);
        assertEquals(List.of(6), r.get("AMB-S004"), r::toString);
        assertEquals(List.of(7), r.get("AMB-S005"), r::toString);
        assertEquals(List.of(8), r.get("AMB-S008"), r::toString);
        assertEquals(List.of(9), r.get("AMB-S009"), "the second UNION is suppressed by amber:ignore");
        assertEquals(List.of(10, 13, 14), r.get("AMB-S010"), r::toString);
        assertEquals(List.of(11), r.get("AMB-S011"), r::toString);
        assertEquals(List.of(12), r.get("AMB-S012"), r::toString);
        assertEquals(List.of(14, 16), r.get("AMB-D001"), "line 16 repeats line 9; its ignore only covers AMB-S009");
        assertTrue(r.containsKey("AMB-D002"), r::toString);
        assertEquals(List.of(19), r.get("AMB-S006"), r::toString);
        assertEquals(List.of(20), r.get("AMB-S007"), r::toString);
        assertEquals(List.of(22), r.get("AMB-I001"), r::toString);
    }

    @Test
    void leftJoinWithIsNullIsNotReported() {
        assertTrue(byRule().get("AMB-S002").stream().noneMatch(line -> line == 4));
    }

    @Test
    void findingsCarryExplanations() {
        for (Finding f : findings) {
            assertTrue(!f.title().isBlank() && !f.message().isBlank() && !f.suggestion().isBlank(), f::toString);
        }
    }

    @Test
    void notInFollowsColumnNullability() {
        DialectFrontend pg = DialectFrontends.get(Dialect.POSTGRESQL);
        String sql = "select id from a where id not in (select b.ref from b);\n"
                + "select id from a where id not in (select c.ref from c);\n";
        ScriptParse parse = pg.parse(SourceText.of(sql));
        Script script = ScriptIrBuilder.build(pg, parse);
        var catalog = com.github.amber.core.lineage.Catalog.of(List.of(
                new com.github.amber.core.meta.TableMeta(null, null, "b", "TABLE",
                        List.of(new com.github.amber.core.meta.TableMeta.ColumnMeta("ref", "int", false)),
                        List.of(), List.of(), List.of(), 0, "t"),
                new com.github.amber.core.meta.TableMeta(null, null, "c", "TABLE",
                        List.of(new com.github.amber.core.meta.TableMeta.ColumnMeta("REF", "int", true)),
                        List.of(), List.of(), List.of(), 0, "t")));
        List<Finding> online = Findings.of(script, catalog);
        List<Finding> s004 = online.stream().filter(f -> f.rule().equals("AMB-S004")).toList();
        assertEquals(1, s004.size(), "NOT NULL column b.ref is safe");
        assertEquals(2, s004.get(0).range().start().line());
        assertEquals(com.github.amber.core.ir.Confidence.CERTAIN, s004.get(0).confidence());
        assertEquals(2, Findings.of(script).stream().filter(f -> f.rule().equals("AMB-S004")).count(), "offline: both");
    }

    @Test
    void orderByBeforeARowLimitIsTopNNotUseless() {
        DialectFrontend ora = DialectFrontends.get(Dialect.ORACLE);
        String sql = "select * from (select a from t order by a desc) where rownum <= 20;\n"
                + "select * from (select a from t order by a desc) x;\n";
        Script script = ScriptIrBuilder.build(ora, ora.parse(SourceText.of(sql)));
        List<Integer> s012 = Findings.of(script).stream().filter(f -> f.rule().equals("AMB-S012"))
                .map(f -> f.range().start().line()).toList();
        assertEquals(List.of(2), s012, "line 1 is the classic top-N idiom");
        DialectFrontend ms = DialectFrontends.get(Dialect.SQLSERVER);
        Script top = ScriptIrBuilder.build(ms, ms.parse(SourceText.of(
                "select top 5 * from (select top 100 percent a from t order by a) x;")));
        assertTrue(Findings.of(top).stream().noneMatch(f -> f.rule().equals("AMB-S012")));
    }
}
