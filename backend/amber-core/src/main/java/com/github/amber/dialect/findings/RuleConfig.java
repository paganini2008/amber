package com.github.amber.dialect.findings;

import com.github.amber.dialect.findings.Finding.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The user's rule settings: rules turned off and severities changed, like the rule configuration of
 * mainstream linters. Rule ids are matched case-insensitively; ids Amber does not know are ignored (a
 * newer settings file stays usable), an unknown severity is an error.
 *
 * @param disabled upper-case ids of rules whose findings are dropped
 * @param severity upper-case id → severity that replaces the rule's own for every finding
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public record RuleConfig(Set<String> disabled, Map<String, Severity> severity) {

    /** Every rule on, with its own severity. */
    public static final RuleConfig DEFAULT = new RuleConfig(Set.of(), Map.of());

    public RuleConfig {
        disabled = disabled == null ? Set.of() : Set.copyOf(disabled);
        severity = severity == null ? Map.of() : Map.copyOf(severity);
    }

    /**
     * Reads the protocol form {@code {"disabled": [...], "severity": {"AMB-P001": "LOW"}}}.
     *
     * @throws IllegalArgumentException for a severity that is not HIGH, MEDIUM, LOW or INFO
     */
    public static RuleConfig of(List<String> disabled, Map<String, String> severity) {
        Set<String> off = new HashSet<>();
        if (disabled != null) {
            for (String id : disabled) {
                if (id != null && !id.isBlank()) {
                    off.add(id.strip().toUpperCase(Locale.ROOT));
                }
            }
        }
        Map<String, Severity> sev = new HashMap<>();
        if (severity != null) {
            for (Map.Entry<String, String> e : severity.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                try {
                    sev.put(e.getKey().strip().toUpperCase(Locale.ROOT),
                            Severity.valueOf(e.getValue().strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Unknown severity '" + e.getValue() + "' for rule " + e.getKey()
                            + "; use HIGH, MEDIUM, LOW or INFO");
                }
            }
        }
        return new RuleConfig(off, sev);
    }

    public boolean enabled(String rule) {
        return rule == null || !disabled.contains(rule.toUpperCase(Locale.ROOT));
    }

    /** True when nothing is changed. */
    public boolean isDefault() {
        return disabled.isEmpty() && severity.isEmpty();
    }

    /** Drops findings of disabled rules and applies severity overrides; the input is not changed. */
    public List<Finding> apply(List<Finding> findings) {
        if (isDefault()) {
            return findings;
        }
        List<Finding> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            if (!enabled(f.rule())) {
                continue;
            }
            Severity s = severity.get(f.rule().toUpperCase(Locale.ROOT));
            out.add(s == null || s == f.severity() ? f : f.withSeverity(s));
        }
        return out;
    }
}
