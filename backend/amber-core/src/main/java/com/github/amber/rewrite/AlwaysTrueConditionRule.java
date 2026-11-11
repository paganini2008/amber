package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.dialect.split.ScanToken;

import java.util.ArrayList;
import java.util.List;

/**
 * AMB-R001: {@code WHERE 1=1 AND …} and {@code … AND 1=1}, the placeholder legacy scripts keep so that every
 * condition can start with AND. A condition that is always true filters nothing, so it is removed together
 * with its AND; a WHERE that holds nothing else goes too. Only {@code n = n} with the same number on both
 * sides is touched, and only when it is joined by AND at the WHERE's own level (never next to an OR).
 *
 * @Author: Fred Feng
 * @Date: 11/11/2026
 * @Version 1.0.0
 */
final class AlwaysTrueConditionRule implements RewriteRule {

    @Override
    public String id() {
        return "AMB-R001";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        Sql sql = ctx.sql();
        List<Candidate> out = new ArrayList<>();
        for (Block b : sql.blocks()) {
            if (b.where < 0) {
                continue;
            }
            int from = b.where + 1;
            int to = b.clauseEnd(b.where);
            if (hasOr(sql, b, from, to)) {
                continue;
            }
            for (int i = from; i + 2 < to; i++) {
                if (sql.depth[i] != b.depth || !alwaysTrue(sql, i)) {
                    continue;
                }
                Edit edit;
                if (i == from && i + 3 == to) {
                    edit = ctx.edit(sql.tok(b.where - 1).end(), sql.tok(i + 2).end(), "");      // WHERE 1=1 alone
                } else if (i == from && sql.word(i + 3, "AND")) {
                    edit = ctx.edit(sql.tok(i).start(), sql.tok(i + 4).start(), "");          // WHERE 1=1 AND x
                } else if (sql.word(i - 1, "AND")) {
                    edit = ctx.edit(sql.tok(i - 2).end(), sql.tok(i + 2).end(), "");          // x AND 1=1
                } else {
                    continue;
                }
                out.add(new Candidate(id(), Messages.t("rewrite.alwaysTrue.title"),
                        Messages.t("rewrite.alwaysTrue.explanation", sql.slice(i, i + 2)), List.of(edit),
                        Safety.EQUIVALENT, Messages.t("rewrite.alwaysTrue.safety"), List.of("Filters", "Conditions")));
                break;                              // one per block; the next pass finds another
            }
        }
        return out;
    }

    /** {@code n = n}: the same number on both sides. */
    private static boolean alwaysTrue(Sql sql, int i) {
        ScanToken a = sql.tok(i);
        return a.type() == ScanToken.Type.NUMBER && sql.sym(i + 1, '=') && i + 2 < sql.size()
                && sql.tok(i + 2).type() == ScanToken.Type.NUMBER && a.text().equals(sql.tok(i + 2).text())
                && !sql.sym(i - 1, '.') && !sql.sym(i + 3, '.') && !isOperator(sql, i - 1) && !isOperator(sql, i + 3);
    }

    private static boolean isOperator(Sql sql, int i) {
        return sql.sym(i, '+') || sql.sym(i, '-') || sql.sym(i, '*') || sql.sym(i, '/') || sql.sym(i, '|')
                || sql.sym(i, '%');
    }

    private static boolean hasOr(Sql sql, Block b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (sql.depth[i] == b.depth && sql.word(i, "OR")) {
                return true;
            }
        }
        return false;
    }
}
