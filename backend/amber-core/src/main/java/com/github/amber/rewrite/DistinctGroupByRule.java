package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.dialect.split.ScanToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AMB-S011: {@code SELECT DISTINCT … GROUP BY …}. GROUP BY returns one row per group, so when every
 * GROUP BY expression is also in the select list, the rows already differ and DISTINCT removes nothing.
 * If a grouping expression is not selected, two groups can produce the same row and DISTINCT matters:
 * no rewrite then. ROLLUP / CUBE / GROUPING SETS are left alone.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class DistinctGroupByRule implements RewriteRule {

    private static final Set<String> GROUPING = Set.of("ROLLUP", "CUBE", "GROUPING");

    @Override
    public String id() {
        return "AMB-S011";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        Sql sql = ctx.sql();
        List<Candidate> out = new ArrayList<>();
        for (Block b : sql.blocks()) {
            if (b.distinct < 0 || b.groupBy < 0 || b.exotic || !sql.word(b.distinct, "DISTINCT")) {
                continue;
            }
            int gStart = b.groupBy + 2;
            int gEnd = b.clauseEnd(b.groupBy);
            boolean grouping = false;
            for (int i = gStart; i < gEnd; i++) {
                grouping |= sql.wordIn(i, GROUPING);
            }
            if (grouping || gStart >= gEnd) {
                continue;
            }
            List<Source> sources = b.sources();
            String single = sources.size() == 1 ? sources.get(0).exposed(sql) : null;
            List<int[]> items = b.items();
            Set<String> selected = new HashSet<>();
            boolean star = false;
            for (int[] item : items) {
                int[] e = b.itemExpression(item);
                star |= sql.sym(e[1] - 1, '*') && (e[1] - e[0] == 1 || sql.sym(e[1] - 2, '.'));
                selected.add(norm(sql, e[0], e[1], single));
            }
            if (star || items.isEmpty()) {
                continue;
            }
            boolean all = true;
            int s = gStart;
            for (int i = gStart; i <= gEnd && all; i++) {
                if (i == gEnd || (sql.depth[i] == b.depth && sql.sym(i, ','))) {
                    all = covered(sql, s, i, single, selected, items.size());
                    s = i + 1;
                }
            }
            if (!all) {
                continue;
            }
            out.add(new Candidate(id(), Messages.t("rewrite.distinctGroupBy.title"),
                    Messages.t("rewrite.distinctGroupBy.explanation"),
                    List.of(ctx.deleteWord(b.distinct)), Safety.EQUIVALENT,
                    Messages.t("rewrite.distinctGroupBy.safety"),
                    List.of("Ordering and row limits:")));
        }
        return out;
    }

    private static boolean covered(Sql sql, int from, int to, String single, Set<String> selected, int itemCount) {
        if (from >= to) {
            return false;
        }
        if (to - from == 1 && sql.tok(from).type() == ScanToken.Type.NUMBER) {
            try {
                int pos = Integer.parseInt(sql.tok(from).text());
                return pos >= 1 && pos <= itemCount;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return selected.contains(norm(sql, from, to, single));
    }

    /** Normalized expression; with a single source, its qualifier is dropped so {@code o.a} matches {@code a}. */
    private static String norm(Sql sql, int from, int to, String single) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (single != null && sql.isName(i) && sql.sym(i + 1, '.') && Sql.key(sql.tok(i).text()).equals(single)
                    && (i == from || !sql.sym(i - 1, '.'))) {
                i++;
                continue;
            }
            sb.append(sql.norm(i, i + 1)).append(' ');
        }
        return sb.toString().strip();
    }
}
