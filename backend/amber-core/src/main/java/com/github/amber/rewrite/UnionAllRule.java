package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.split.ScanToken;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AMB-S009: {@code UNION} → {@code UNION ALL}. UNION removes duplicate rows, so the change is only safe
 * when there are none to remove:
 * <ul>
 *   <li>{@link Safety#EQUIVALENT} when the set is only used for membership ({@code IN}, {@code EXISTS},
 *       {@code = ANY}), where duplicates cannot matter;</li>
 *   <li>{@link Safety#EQUIVALENT_WITH_METADATA} when no two branches can return the same row (some output
 *       column holds different constants: a literal discriminator, or a column filtered to different
 *       values) and every branch returns distinct rows itself (DISTINCT, a GROUP BY over selected
 *       columns, or the primary key of its single table selected);</li>
 *   <li>otherwise {@link Safety#CHANGES_BEHAVIOR_IN_EDGE_CASES}: acceptable only if duplicates are fine.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class UnionAllRule implements RewriteRule {

    private static final Set<String> MEMBERSHIP = Set.of("IN", "EXISTS");

    @Override
    public String id() {
        return "AMB-S009";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        Sql sql = ctx.sql();
        List<Candidate> out = new ArrayList<>();
        for (int i = 1; i + 1 < sql.size(); i++) {
            if (!sql.word(i, "UNION") || sql.wordIn(i + 1, Set.of("ALL", "DISTINCT"))) {
                continue;
            }
            ScanToken u = sql.tok(i);
            Edit edit = ctx.insert(u.end(), " " + RewriteContext.kw("ALL", u));
            List<Block> branches = chain(sql, i);
            String title = Messages.t("rewrite.unionAll.title");
            String explanation = Messages.t("rewrite.unionAll.explanation");
            if (branches != null && membership(sql, branches)) {
                out.add(new Candidate(id(), title, explanation, List.of(edit), Safety.EQUIVALENT,
                        Messages.t("rewrite.unionAll.safety.membership")));
                continue;
            }
            String proof = branches == null ? null : disjointAndDistinct(ctx, sql, branches);
            if (proof != null) {
                out.add(new Candidate(id(), title, explanation, List.of(edit), Safety.EQUIVALENT_WITH_METADATA, proof));
            } else {
                out.add(new Candidate(id(), title, explanation, List.of(edit), Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES,
                        Messages.t("rewrite.unionAll.safety.edgeCases")));
            }
        }
        return out;
    }

    /** The plain SELECT branches of the set operation around {@code op}, or null when a branch is not plain. */
    private static List<Block> chain(Sql sql, int op) {
        int d = sql.depth[op];
        List<Block> blocks = new ArrayList<>();
        for (Block b : sql.blocks()) {
            if (b.depth == d) {
                blocks.add(b);
            }
        }
        // walk left and right from op over set operators at the same depth
        Block left = blocks.stream().filter(b -> b.end == op).findFirst().orElse(null);
        if (left == null) {
            return null;
        }
        List<Block> chain = new ArrayList<>();
        chain.add(left);
        Block cur = left;
        while (true) {
            Block c = cur;
            Block prev = blocks.stream().filter(b -> b.end + 1 == c.start || (b.end + 2 == c.start
                    && sql.word(b.end + 1, "ALL"))).filter(b -> sql.wordIn(b.end, Sql.SET_OPERATORS)).findFirst().orElse(null);
            if (prev == null) {
                break;
            }
            chain.add(0, prev);
            cur = prev;
        }
        cur = left;
        while (sql.wordIn(cur.end, Sql.SET_OPERATORS)) {
            if (!sql.word(cur.end, "UNION")) {
                return null;                        // INTERSECT / EXCEPT / MINUS: no proof attempted
            }
            int next = sql.word(cur.end + 1, "ALL") ? cur.end + 2 : cur.end + 1;
            Block nb = blocks.stream().filter(b -> b.start == next).findFirst().orElse(null);
            if (nb == null) {
                return null;                        // parenthesized branch
            }
            chain.add(nb);
            cur = nb;
        }
        for (Block b : chain) {
            if (b.start > 0 && sql.wordIn(b.start - 1, Sql.SET_OPERATORS) && !sql.word(b.start - 1, "UNION")) {
                return null;
            }
        }
        return chain;
    }

    /** The set is the whole content of {@code IN (…)}, {@code EXISTS (…)} or {@code = ANY (…)}. */
    private static boolean membership(Sql sql, List<Block> chain) {
        Block first = chain.get(0);
        Block last = chain.get(chain.size() - 1);
        int open = first.openParen();
        if (open < 0 || sql.close(open) != last.end) {
            return false;
        }
        for (Block b : chain) {
            if (b.limited || b.orderBy >= 0 || b.exotic || b.forClause >= 0) {
                return false;
            }
        }
        if (sql.wordIn(open - 1, MEMBERSHIP)) {
            return true;
        }
        return sql.wordIn(open - 1, Set.of("ANY", "SOME")) && (sql.sym(open - 2, '=') || sql.sym(open - 2, '<')
                || sql.sym(open - 2, '>'));
    }

    /** A sentence proving no duplicates can occur, or null. */
    private static String disjointAndDistinct(RewriteContext ctx, Sql sql, List<Block> chain) {
        int width = -1;
        List<List<Object>> constants = new ArrayList<>();
        for (Block b : chain) {
            if (b.limited || b.exotic || b.from < 0 && b.items().isEmpty()) {
                return null;
            }
            List<int[]> items = b.items();
            if (width >= 0 && items.size() != width) {
                return null;
            }
            width = items.size();
            if (!distinctRows(ctx, sql, b)) {
                return null;
            }
            constants.add(constants(sql, b, items));
        }
        List<String> reasons = new ArrayList<>();
        for (int a = 0; a < chain.size(); a++) {
            for (int c = a + 1; c < chain.size(); c++) {
                int pos = differingPosition(constants.get(a), constants.get(c));
                if (pos < 0) {
                    return null;
                }
                reasons.add(Messages.t("rewrite.unionAll.reason", pos + 1, show(constants.get(a).get(pos)), a + 1,
                        show(constants.get(c).get(pos)), c + 1));
            }
        }
        return Messages.t("rewrite.unionAll.safety.disjoint",
                String.join(Messages.t("rewrite.unionAll.reasonSeparator"), reasons));
    }

    private static String show(Object o) {
        return o instanceof BigDecimal n ? n.toPlainString() : "'" + o + "'";
    }

    private static int differingPosition(List<Object> a, List<Object> b) {
        for (int i = 0; i < a.size() && i < b.size(); i++) {
            Object x = a.get(i);
            Object y = b.get(i);
            if (x == null || y == null || x.getClass() != y.getClass()) {
                continue;
            }
            boolean differ = x instanceof BigDecimal n ? n.compareTo((BigDecimal) y) != 0
                    : !x.toString().stripTrailing().equalsIgnoreCase(y.toString().stripTrailing());
            if (differ) {
                return i;
            }
        }
        return -1;
    }

    /** Per select item: the constant it always has in this branch (a String or BigDecimal), or null. */
    private static List<Object> constants(Sql sql, Block b, List<int[]> items) {
        List<int[]> terms = b.where >= 0 ? Predicates.conjuncts(sql, b.where + 1, b.whereEnd(), b.depth) : null;
        List<Object> out = new ArrayList<>();
        for (int[] item : items) {
            int[] e = b.itemExpression(item);
            Object c = e[1] - e[0] == 1 ? literal(sql.tok(e[0])) : null;
            if (c == null && terms != null && sql.nameEnd(e[0]) == e[1] - 1) {
                String col = sql.norm(e[0], e[1]);
                for (int[] t : terms) {
                    // col = literal  or  literal = col
                    if (t[1] - t[0] == e[1] - e[0] + 2 && sql.norm(t[0], t[1] - 2).equals(col) && sql.sym(t[1] - 2, '=')) {
                        c = literal(sql.tok(t[1] - 1));
                    } else if (t[1] - t[0] == e[1] - e[0] + 2 && sql.norm(t[0] + 2, t[1]).equals(col)
                            && sql.sym(t[0] + 1, '=')) {
                        c = literal(sql.tok(t[0]));
                    }
                    if (c != null) {
                        break;
                    }
                }
            }
            out.add(c);
        }
        return out;
    }

    private static Object literal(ScanToken t) {
        if (t.type() == ScanToken.Type.STRING && t.text().startsWith("'")) {
            return t.text().substring(1, t.text().length() - 1).replace("''", "'");
        }
        if (t.type() == ScanToken.Type.NUMBER) {
            try {
                return new BigDecimal(t.text());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The branch cannot return the same row twice. */
    private static boolean distinctRows(RewriteContext ctx, Sql sql, Block b) {
        if (b.distinct >= 0) {
            return true;
        }
        List<int[]> items = b.items();
        List<Source> sources = b.sources();
        if (b.groupBy >= 0) {
            return groupedBySelected(sql, b, items);
        }
        if (sources.size() != 1 || sources.get(0).kind() != Source.Kind.TABLE) {
            return false;
        }
        Source s = sources.get(0);
        var meta = ctx.meta(s);
        if (meta.isEmpty()) {
            return false;
        }
        List<String> selected = new ArrayList<>();
        for (int[] item : items) {
            int[] e = b.itemExpression(item);
            if (sql.nameEnd(e[0]) == e[1] - 1) {
                selected.add(sql.tok(e[1] - 1).text().toUpperCase(Locale.ROOT));
            }
        }
        TableMeta m = meta.get();
        if (!m.primaryKey().isEmpty() && selected.containsAll(upper(m.primaryKey()))) {
            return true;
        }
        for (List<String> uk : m.uniqueKeys()) {
            if (!uk.isEmpty() && selected.containsAll(upper(uk))
                    && uk.stream().allMatch(c -> m.column(c).map(col -> !col.nullable()).orElse(false))) {
                return true;
            }
        }
        return false;
    }

    private static boolean groupedBySelected(Sql sql, Block b, List<int[]> items) {
        int gs = b.groupBy + 2;
        int ge = b.clauseEnd(b.groupBy);
        List<String> selected = new ArrayList<>();
        for (int[] item : items) {
            int[] e = b.itemExpression(item);
            selected.add(sql.norm(e[0], e[1]));
        }
        int s = gs;
        for (int i = gs; i <= ge; i++) {
            if (i == ge || (sql.depth[i] == b.depth && sql.sym(i, ','))) {
                if (s >= i || sql.wordIn(s, Set.of("ROLLUP", "CUBE", "GROUPING")) || !selected.contains(sql.norm(s, i))) {
                    return false;
                }
                s = i + 1;
            }
        }
        return true;
    }

    private static List<String> upper(List<String> l) {
        return l.stream().map(x -> x.toUpperCase(Locale.ROOT)).toList();
    }
}
