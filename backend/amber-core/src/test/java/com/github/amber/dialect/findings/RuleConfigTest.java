package com.github.amber.dialect.findings;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Script;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontend;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.findings.Finding.Severity;
import com.github.amber.dialect.ir.ScriptIrBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule settings (disabled rules, severity overrides) and the rule catalogue.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class RuleConfigTest {

    private static Script script(String sql) {
        DialectFrontend f = DialectFrontends.get(Dialect.ORACLE);
        return ScriptIrBuilder.build(f, f.parse(SourceText.of(sql)));
    }

    @Test
    void disabledRulesAndSeverityOverrides() {
        Script s = script("select a from t union select a from u;\nselect * from t where t.n + 1 = 2;");
        List<Finding> all = Findings.of(s, new Catalog());
        assertTrue(all.stream().anyMatch(f -> f.rule().equals("AMB-S009")));
        assertTrue(all.stream().anyMatch(f -> f.rule().equals("AMB-P007")));
        RuleConfig c = RuleConfig.of(List.of("amb-s009", " ", ""), Map.of("AMB-P007", "high", "AMB-X999", "LOW"));
        List<Finding> configured = Findings.of(s, new Catalog(), c);
        assertTrue(configured.stream().noneMatch(f -> f.rule().equals("AMB-S009")));
        Finding p007 = configured.stream().filter(f -> f.rule().equals("AMB-P007")).findFirst().orElseThrow();
        assertEquals(Severity.HIGH, p007.severity());
        assertFalse(c.enabled("AMB-S009"));
        assertTrue(c.enabled("AMB-S001"));
        assertTrue(c.enabled(null));
        assertSame(all, RuleConfig.DEFAULT.apply(all));
        assertTrue(RuleConfig.of(null, null).isDefault());
        assertEquals(all.size(), Findings.of(s, new Catalog(), null).size());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RuleConfig.of(List.of(), Map.of("AMB-S001", "CRITICAL")));
        assertTrue(e.getMessage().contains("CRITICAL"));
    }

    @Test
    void catalogueListsEveryRuleTheEngineReports() throws IOException {
        Set<String> ids = new HashSet<>();
        for (RuleCatalog.RuleInfo r : RuleCatalog.all()) {
            assertTrue(ids.add(r.id()), "duplicate " + r.id());
            assertFalse(r.title().isBlank() || r.description().isBlank());
            assertEquals(r.id(), RuleCatalog.get(r.id().toLowerCase()).orElseThrow().id());
        }
        assertEquals(27, ids.size());
        assertTrue(RuleCatalog.get("AMB-Z001").isEmpty());
        assertEquals("performance", RuleCatalog.Category.PERFORMANCE.id());
        // every id written in the engine's sources is in the catalogue
        Pattern id = Pattern.compile("\"(AMB-[A-Z]\\d{3})\"");
        Set<String> used = new HashSet<>();
        for (String file : List.of("Findings.java", "rules/IndexRules.java", "rules/ConversionRule.java",
                "rules/QueryShapeRules.java", "rules/ProceduralRules.java")) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/github/amber/dialect/findings/" + file), StandardCharsets.UTF_8);
            Matcher m = id.matcher(src);
            while (m.find()) {
                used.add(m.group(1));
            }
        }
        assertTrue(ids.containsAll(used), () -> "missing from the catalogue: " + used);
        // quick-fix flags match the rewrite rules
        for (String fix : List.of("AMB-S002", "AMB-S004", "AMB-S008", "AMB-S009", "AMB-S010", "AMB-S011", "AMB-S012",
                "AMB-D002", "AMB-P003")) {
            assertTrue(RuleCatalog.get(fix).orElseThrow().hasQuickFix(), fix);
        }
    }
}
