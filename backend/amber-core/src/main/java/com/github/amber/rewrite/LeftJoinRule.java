package com.github.amber.rewrite;

import com.github.amber.dialect.split.ScanToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AMB-S002: a LEFT JOIN whose right table is filtered in WHERE by a condition that is never true for
 * the NULLs the outer join adds. Those rows are dropped anyway, so the join is an inner join: write
 * {@code JOIN}. Only top-level AND conditions of the shape {@code alias.col <op> value} count
 * ({@code =, <>, <, >, LIKE, IN, BETWEEN, IS NOT NULL}); a WHERE with a top-level OR is left alone.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class LeftJoinRule implements RewriteRule {

    @Override
    public String id() {
        return "AMB-S002";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        Sql sql = ctx.sql();
        List<Candidate> out = new ArrayList<>();
        for (Block b : sql.blocks()) {
            if (b.from < 0 || b.where < 0) {
                continue;
            }
            List<Source> sources = b.sources();
            List<int[]> conjuncts = Predicates.conjuncts(sql, b.where + 1, b.whereEnd(), b.depth);
            if (sources.isEmpty() || conjuncts == null) {
                continue;
            }
            for (Source s : sources) {
                if (!s.join().equals("LEFT JOIN") && !s.join().equals("LEFT OUTER JOIN")) {
                    continue;
                }
                String exposed = s.exposed(sql);
                if (exposed == null || sources.stream().filter(o -> exposed.equals(o.exposed(sql))).count() != 1) {
                    continue;
                }
                for (int[] c : conjuncts) {
                    if (nullRejecting(sql, c[0], c[1], exposed)) {
                        List<Edit> edits = new ArrayList<>();
                        for (int k = s.joinStart(); k < s.joinEnd(); k++) {
                            edits.add(ctx.deleteWord(k));
                        }
                        String cond = sql.slice(c[0], c[1] - 1);
                        out.add(new Candidate(id(), "LEFT JOIN → JOIN",
                                "The WHERE condition " + cond + " is never true for the rows the LEFT JOIN adds when "
                                + s.exposed(sql).toLowerCase(java.util.Locale.ROOT) + " has no match (their columns are "
                                + "NULL), so those rows are filtered out and the join already behaves as an inner join. "
                                + "Writing JOIN says so. If the outer join was intended, move the condition into the "
                                + "ON clause instead.",
                                edits, Safety.EQUIVALENT, "The WHERE condition rejects NULL, so the result is the same "
                                + "for any data."));
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** {@code alias.col <op> …} or {@code value <op> alias.col} where the comparison is false for NULL. */
    static boolean nullRejecting(Sql sql, int from, int to, String alias) {
        if (to - from >= 4 && qualified(sql, from, alias)) {
            int k = from + 3;
            if (sql.sym(k, '=') || sql.sym(k, '<') || sql.sym(k, '>') || (sql.sym(k, '!') && sql.sym(k + 1, '='))
                    || sql.word(k, "LIKE") || sql.word(k, "IN") || sql.word(k, "BETWEEN")
                    || (sql.word(k, "NOT") && (sql.word(k + 1, "IN") || sql.word(k + 1, "LIKE") || sql.word(k + 1, "BETWEEN")))) {
                return true;
            }
            return sql.word(k, "IS") && sql.word(k + 1, "NOT") && sql.word(k + 2, "NULL") && to == k + 3;
        }
        if (to - from >= 5 && qualified(sql, to - 3, alias)) {
            int k = to - 4;
            boolean op = sql.sym(k, '=') || sql.sym(k, '<') || sql.sym(k, '>');
            return op && (k == from + 1 || (k == from + 2 && (sql.sym(k - 1, '<') || sql.sym(k - 1, '>')
                    || sql.sym(k - 1, '!'))))
                    && simpleOperand(sql.tok(from));
        }
        return false;
    }

    private static boolean qualified(Sql sql, int i, String alias) {
        return sql.isName(i) && Sql.key(sql.tok(i).text()).equals(alias) && sql.sym(i + 1, '.') && sql.isName(i + 2)
                && !sql.sym(i + 3, '.') && !sql.sym(i + 3, '(');
    }

    private static boolean simpleOperand(ScanToken t) {
        return t.type() == ScanToken.Type.NUMBER || t.type() == ScanToken.Type.STRING
                || (t.type() == ScanToken.Type.WORD && !t.isWordIn(Set.of("NOT", "NULL", "CASE", "EXISTS")));
    }
}
