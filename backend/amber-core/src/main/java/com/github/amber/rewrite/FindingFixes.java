package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.findings.RuleCatalog;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Gives findings whose rule has a quick fix the verified rewrite of {@code rewrite.suggest} as their
 * {@link Finding.SuggestedSql}, so every finding with an optimized version shows it the same way. Findings
 * that already carry suggested SQL keep it.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class FindingFixes {

    /** At most this many statements are rewritten for one analysis (the rest keep their text-only hints). */
    static final int MAX_STATEMENTS = 200;

    private FindingFixes() {
    }

    /**
     * @param steps statements in step order (ids as in {@code analyze}) of the same parse
     */
    public static List<Finding> attach(Dialect dialect, String text, Catalog catalog, boolean allowFallback,
                                       List<Finding> findings, List<Statement> steps, Predicate<String> ruleEnabled) {
        Map<Statement, Integer> ids = new IdentityHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            ids.put(steps.get(i), i);
        }
        TreeSet<Integer> wanted = new TreeSet<>();
        for (Finding f : findings) {
            Integer id = ids.get(f.statement());
            if (f.suggested() == null && id != null && hasFix(f.rule())) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return findings;
        }
        List<Integer> limited = new ArrayList<>(wanted).subList(0, Math.min(MAX_STATEMENTS, wanted.size()));
        List<Rewrite> rewrites;
        try {
            rewrites = RewriteSuggester.suggest(dialect, text, catalog, limited, allowFallback,
                    ruleEnabled == null ? r -> true : ruleEnabled);
        } catch (RuntimeException e) {
            return findings;                         // suggestions are optional
        }
        List<Finding> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            Integer id = ids.get(f.statement());
            Rewrite match = null;
            if (f.suggested() == null && id != null) {
                for (Rewrite r : rewrites) {
                    if (r.statement() == id && r.rule().equals(f.rule()) && r.conflictsWith() == null) {
                        match = r;
                        break;
                    }
                }
            }
            out.add(match == null ? f : f.withSuggested(new Finding.SuggestedSql(match.rewrittenStatement(),
                    Finding.SqlKind.REWRITE_OF_STATEMENT, match.check(), note(match), match.id())));
        }
        return out;
    }

    private static String note(Rewrite r) {
        return switch (r.safety()) {
            case EQUIVALENT -> Messages.t("rewrite.note.equivalent");
            case EQUIVALENT_WITH_METADATA -> Messages.t("rewrite.note.equivalentWithMetadata", r.safetyNote());
            default -> Messages.t("rewrite.note.edgeCases", r.safetyNote());
        };
    }

    private static boolean hasFix(String rule) {
        return RuleCatalog.get(rule).map(RuleCatalog.RuleInfo::hasQuickFix).orElse(false);
    }
}
