package com.github.amber.dialect.findings;

import com.github.amber.dialect.findings.Finding.Severity;

import java.util.List;
import java.util.Optional;

/**
 * Every finding rule Amber knows, with its default severity and what it needs: the list behind
 * {@code engine.rules}, so the app can show, enable, disable and re-rank rules (see {@link RuleConfig}).
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class RuleCatalog {

    /** What a rule is about. */
    public enum Category {
        RISK, PERFORMANCE, MAINTAINABILITY, INFO;

        /** Lower-case id used in the protocol. */
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One rule.
     *
     * @param id              stable id, e.g. {@code AMB-P001}
     * @param severity        default severity (some rules raise or lower it per finding; a configured
     *                        severity replaces it for every finding of the rule)
     * @param needsMetadata   the rule only reports when table metadata is known (DDL import, pack, online)
     * @param hasQuickFix     {@code rewrite.suggest} can offer a verified rewrite for some of its findings
     * @param title           short name, in English (all texts: {@code amber/i18n/findings*.properties})
     * @param description     one-sentence explanation, in English
     */
    public record RuleInfo(String id, Severity severity, String title, String description, Category category,
                           boolean needsMetadata, boolean hasQuickFix) {

        /** The title in the current request's language. */
        public String localizedTitle() {
            return com.github.amber.core.i18n.Messages.t("rule." + id + ".title");
        }

        /** The description in the current request's language. */
        public String localizedDescription() {
            return com.github.amber.core.i18n.Messages.t("rule." + id + ".description");
        }
    }

    private static final List<RuleInfo> RULES = List.of(
            r("AMB-S001", Severity.HIGH, Category.RISK, false, false),
            r("AMB-S002", Severity.MEDIUM, Category.RISK, false, true),
            r("AMB-S003", Severity.MEDIUM, Category.PERFORMANCE, false, false),
            r("AMB-S004", Severity.MEDIUM, Category.RISK, false, true),
            r("AMB-S005", Severity.MEDIUM, Category.PERFORMANCE, false, false),
            r("AMB-S006", Severity.MEDIUM, Category.PERFORMANCE, false, false),
            r("AMB-S007", Severity.MEDIUM, Category.RISK, false, false),
            r("AMB-S008", Severity.MEDIUM, Category.PERFORMANCE, false, true),
            r("AMB-S009", Severity.LOW, Category.PERFORMANCE, false, true),
            r("AMB-S010", Severity.LOW, Category.MAINTAINABILITY, false, true),
            r("AMB-S011", Severity.LOW, Category.PERFORMANCE, false, true),
            r("AMB-S012", Severity.LOW, Category.MAINTAINABILITY, false, true),
            r("AMB-M001", Severity.HIGH, Category.RISK, true, false),
            r("AMB-D001", Severity.LOW, Category.MAINTAINABILITY, false, false),
            r("AMB-D002", Severity.LOW, Category.MAINTAINABILITY, false, true),
            r("AMB-I001", Severity.INFO, Category.INFO, false, false),
            r("AMB-I002", Severity.INFO, Category.INFO, false, false),
            r("AMB-I003", Severity.INFO, Category.INFO, false, false),
            r("AMB-P001", Severity.INFO, Category.PERFORMANCE, true, false),
            r("AMB-P002", Severity.MEDIUM, Category.PERFORMANCE, true, false),
            r("AMB-P003", Severity.LOW, Category.PERFORMANCE, false, true),
            r("AMB-P004", Severity.INFO, Category.PERFORMANCE, false, false),
            r("AMB-P005", Severity.MEDIUM, Category.PERFORMANCE, false, false),
            r("AMB-P006", Severity.INFO, Category.PERFORMANCE, true, false),
            r("AMB-P007", Severity.LOW, Category.PERFORMANCE, false, false),
            r("AMB-P008", Severity.LOW, Category.PERFORMANCE, false, false),
            r("AMB-P009", Severity.LOW, Category.PERFORMANCE, true, false));

    private RuleCatalog() {
    }

    /** A rule whose title and description are the English texts {@code rule.<id>.title|description}. */
    private static RuleInfo r(String id, Severity severity, Category category, boolean needsMetadata,
                              boolean hasQuickFix) {
        var en = com.github.amber.core.i18n.Messages.en();
        return new RuleInfo(id, severity, en.text("rule." + id + ".title"), en.text("rule." + id + ".description"),
                category, needsMetadata, hasQuickFix);
    }

    /** All rules, in id order within each family (S, M, D, I, P). */
    public static List<RuleInfo> all() {
        return RULES;
    }

    public static Optional<RuleInfo> get(String id) {
        return RULES.stream().filter(r -> r.id().equalsIgnoreCase(id)).findFirst();
    }
}
