package com.github.amber.dialect.findings.rules;

import com.github.amber.ai.AiCheck;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.ParseOptions;
import com.github.amber.dialect.ScriptStatus;
import com.github.amber.dialect.findings.Finding.SqlKind;
import com.github.amber.dialect.findings.Finding.SuggestedSql;

import java.util.Map;

/**
 * Verifies the optimized SQL a rule wants to attach to a finding: it must parse strictly in the script's
 * dialect, and a rewrite is compared structurally with the original ({@link AiCheck}). SQL that fails is
 * never attached.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class SqlCheck {

    private SqlCheck() {
    }

    /** A verified rewrite of {@code original}, or null when {@code sql} does not parse. */
    static SuggestedSql rewrite(RuleContext ctx, String original, String sql, String note) {
        if (sql == null || sql.isBlank() || !parses(ctx, sql)) {
            return null;
        }
        AiCheck.Result r;
        try {
            r = AiCheck.check(ctx.dialect, original, sql, Map.of(), ctx.catalog);
        } catch (RuntimeException e) {
            return null;
        }
        if (r.label() == AiCheck.Label.INVALID_SQL) {
            return null;
        }
        return new SuggestedSql(sql.strip(), SqlKind.REWRITE_OF_STATEMENT, r.label().name(), note, null);
    }

    /** Additional DDL (e.g. CREATE INDEX), parsed only; null when it does not parse. */
    static SuggestedSql ddl(RuleContext ctx, String sql, String note) {
        if (sql == null || !parses(ctx, sql)) {
            return null;
        }
        return new SuggestedSql(sql.strip(), SqlKind.ADDITIONAL_DDL, "NOT_CHECKED", note, null);
    }

    static boolean parses(RuleContext ctx, String sql) {
        String s = sql.strip();
        String terminated = s.endsWith(";") ? s : s + ";";
        try {
            return DialectFrontends.get(ctx.dialect).parse(SourceText.of(terminated), ParseOptions.STRICT).status()
                    == ScriptStatus.SUCCESS;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code text} with source offsets [start, end) replaced, limited to [from, to) of the script. */
    static String replace(String script, int from, int to, int start, int end, String replacement) {
        return script.substring(from, start) + replacement + script.substring(end, to);
    }
}
