package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AMB-S012: ORDER BY inside a derived table, an expression subquery or a CTE, with no row limit. The
 * outer query does not keep that order, so the ORDER BY only costs a sort and can be dropped.
 *
 * <p>It is kept whenever something could depend on it: a row limit in the block (TOP, LIMIT, FETCH,
 * OFFSET, ROWNUM), and anywhere else in the statement a row limit, ROWNUM, a window function, an
 * order-sensitive aggregate (LISTAGG, STRING_AGG, XMLAGG, GROUP_CONCAT, ARRAY_AGG…), a sequence
 * NEXTVAL, BULK COLLECT, FOR XML / FOR JSON or CONNECT BY; also in cursors and statements run in a loop.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SubqueryOrderByRule implements RewriteRule {

    /** Words outside the subquery that may rely on the order of its rows. */
    private static final Set<String> ORDER_DEPENDENT = Set.of("ROWNUM", "TOP", "LIMIT", "FETCH", "OFFSET", "OVER",
            "NEXTVAL", "LISTAGG", "XMLAGG", "STRING_AGG", "ARRAY_AGG", "GROUP_CONCAT", "JSON_AGG", "JSONB_AGG",
            "JSON_ARRAYAGG", "JSON_OBJECTAGG", "WM_CONCAT", "COLLECT", "BULK", "XML", "JSON", "CONNECT", "IDENTITY",
            "ROW_NUMBER", "FIRST", "LAST", "FIRST_VALUE", "LAST_VALUE", "PIPE", "XMLELEMENT", "XMLFOREST");
    private static final Set<String> BEFORE_SUBQUERY = Set.of("FROM", "JOIN", "IN", "EXISTS", "APPLY",
            "LATERAL", "SELECT", "THEN", "ELSE", "WHEN", "WHERE", "AND", "OR", "NOT", "ON", "HAVING");

    @Override
    public String id() {
        return "AMB-S012";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        Sql sql = ctx.sql();
        List<Candidate> out = new ArrayList<>();
        if (ctx.inCursorOrLoop()) {
            return out;
        }
        for (Block b : sql.blocks()) {
            if (b.orderBy < 0 || b.depth == 0 || b.limited || b.forClause >= 0 || b.exotic) {
                continue;
            }
            int close = b.end;
            int open = sql.close(close);
            if (!sql.sym(close, ')') || open < 0 || !isSubquery(sql, open, b)) {
                continue;
            }
            if (orderDependentOutside(sql, open, close)) {
                continue;
            }
            int last = b.clauseEnd(b.orderBy) - 1;
            int prev = b.orderBy - 1;
            int startOffset = sql.spaceBefore(sql.tok(b.orderBy).start()) == sql.tok(prev).end()
                    ? sql.tok(prev).end() : sql.tok(b.orderBy).start();
            Edit edit = ctx.edit(startOffset, sql.tok(last).end(), "");
            out.add(new Candidate(id(), Messages.t("rewrite.subqueryOrderBy.title"),
                    Messages.t("rewrite.subqueryOrderBy.explanation", sql.slice(b.orderBy, last)),
                    List.of(edit), Safety.EQUIVALENT,
                    Messages.t("rewrite.subqueryOrderBy.safety"),
                    List.of("Ordering and row limits:")));
        }
        return out;
    }

    /** True when the parenthesis at {@code open} starts a subquery, derived table or CTE body. */
    private static boolean isSubquery(Sql sql, int open, Block b) {
        int p = open - 1;
        if (p < 0) {
            return false;
        }
        if (sql.wordIn(p, Set.of("ANY", "SOME", "ALL"))) {
            return comparison(sql, p - 1);
        }
        if (sql.wordIn(p, BEFORE_SUBQUERY)) {
            return true;
        }
        if (comparison(sql, p) || sql.sym(p, '+') || sql.sym(p, '-') || sql.sym(p, '*') || sql.sym(p, '/')
                || sql.sym(p, '|')) {
            return true;
        }
        if (sql.sym(p, ',')) {
            Block outer = sql.blockAt(open);
            return outer != null && outer.from >= 0 && open > outer.from;
        }
        if (sql.word(p, "AS")) {                     // WITH name [(cols)] AS (…)
            int n = p - 1;
            if (sql.sym(n, ')') && sql.close(n) > 0) {
                n = sql.close(n) - 1;
            }
            return sql.isName(n) && (sql.word(n - 1, "WITH") || sql.sym(n - 1, ',') || sql.word(n - 1, "RECURSIVE"));
        }
        return false;
    }

    private static boolean comparison(Sql sql, int i) {
        return sql.sym(i, '=') || sql.sym(i, '<') || sql.sym(i, '>');
    }

    private static boolean orderDependentOutside(Sql sql, int open, int close) {
        for (int i = 0; i < sql.size(); i++) {
            if (i >= open && i <= close) {
                continue;
            }
            if (sql.wordIn(i, ORDER_DEPENDENT)) {
                return true;
            }
        }
        return false;
    }
}
