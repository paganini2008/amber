package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.ir.StatementKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * AMB-S004: {@code x NOT IN (SELECT c FROM t … [WHERE …])} becomes
 * {@code NOT EXISTS (SELECT 1 FROM t … WHERE … AND c = x)}.
 *
 * <p>The outer value is always written qualified inside the new subquery, so it cannot be captured by a
 * column of the subquery's tables; when the subquery reads the same table without an alias, an alias is
 * added. The subquery column stays as written (it resolves in the same scope as before). Only a plain
 * single-column subquery without GROUP BY, HAVING, set operators, ORDER BY or row limits is rewritten.
 *
 * <p>Safety: NOT IN returns no row at all when the subquery yields a NULL, and excludes rows whose
 * {@code x} is NULL; NOT EXISTS ignores both. The rewrite is equivalent only when metadata says both
 * columns are NOT NULL.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class NotInRule implements RewriteRule {

    private static final Set<String> BOUNDARY = Set.of("WHERE", "AND", "OR", "ON", "WHEN", "HAVING");

    @Override
    public String id() {
        return "AMB-S004";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        Sql sql = ctx.sql();
        List<Candidate> out = new ArrayList<>();
        for (int i = 1; i + 3 < sql.size(); i++) {
            if (sql.word(i, "NOT") && sql.word(i + 1, "IN") && sql.sym(i + 2, '(') && sql.word(i + 3, "SELECT")) {
                Candidate c = rewrite(ctx, sql, i);
                if (c != null) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private Candidate rewrite(RewriteContext ctx, Sql sql, int not) {
        int open = not + 2;
        int close = sql.close(open);
        Block inner = sql.blocks().stream().filter(b -> b.start == not + 3).findFirst().orElse(null);
        if (close < 0 || inner == null || inner.end != close || inner.from < 0 || inner.groupBy >= 0
                || inner.having >= 0 || inner.orderBy >= 0 || inner.limited || inner.exotic || inner.connectBy >= 0
                || inner.startWith >= 0 || inner.forClause >= 0 || inner.into >= 0) {
            return null;
        }
        List<int[]> items = inner.items();
        if (items.size() != 1) {
            return null;
        }
        int[] expr = inner.itemExpression(items.get(0));
        if (sql.nameEnd(expr[0]) != expr[1] - 1 || expr[1] - expr[0] > 3) {
            return null;                            // not a plain [alias.]column
        }
        int colStart = expr[0];
        int colEnd = expr[1] - 1;
        // left operand: [q.]col right before NOT, after a predicate boundary
        int lhsEnd = not - 1;
        int lhsStart = lhsEnd;
        while (sql.sym(lhsStart - 1, '.') && sql.isName(lhsStart - 2)) {
            lhsStart -= 2;
        }
        if (!sql.isName(lhsEnd) || sql.wordIn(lhsEnd, Source.NOT_ALIAS) || lhsEnd - lhsStart > 2
                || !(sql.wordIn(lhsStart - 1, BOUNDARY) || sql.sym(lhsStart - 1, '('))
                || sql.tok(lhsStart).text().startsWith("@") || sql.tok(lhsStart).text().startsWith(":")) {
            return null;
        }
        List<Source> innerSources = inner.sources();
        if (innerSources.isEmpty() || innerSources.stream().anyMatch(s -> s.exposed(sql) == null)) {
            return null;
        }
        List<Source> outerSources = outerSources(ctx, sql, not);
        if (outerSources == null) {
            return null;
        }
        String lhsColumn = sql.tok(lhsEnd).text();
        Source owner;
        String qualifier;
        if (lhsEnd > lhsStart) {
            qualifier = Sql.key(sql.tok(lhsEnd - 2).text());
            owner = outerSources.stream().filter(s -> qualifier.equals(s.exposed(sql))).findFirst().orElse(null);
        } else {
            owner = ownerOf(ctx, sql, outerSources, lhsColumn);
            if (owner == null || owner.exposed(sql) == null) {
                return null;
            }
            qualifier = owner.exposed(sql);
        }
        List<Edit> edits = new ArrayList<>();
        // the outer qualifier must not be hidden by a name the subquery defines
        String innerAlias = null;
        boolean shadowed = innerSources.stream().anyMatch(s -> qualifier.equals(s.exposed(sql)));
        if (shadowed) {
            Source only = innerSources.size() == 1 ? innerSources.get(0) : null;
            if (only == null || only.kind() != Source.Kind.TABLE || only.alias() != null
                    || usesQualifier(sql, open, close, qualifier)) {
                return null;
            }
            innerAlias = freshAlias(sql);
            edits.add(ctx.insert(sql.tok(only.nameEnd()).end(), " " + innerAlias));
        }
        String lhsText = lhsEnd > lhsStart ? sql.slice(lhsStart, lhsEnd)
                : (owner.alias() != null ? owner.alias() : sql.tok(owner.nameEnd()).text()) + "." + lhsColumn;
        String colText = innerAlias != null && colEnd == colStart ? innerAlias + "." + sql.slice(colStart, colEnd)
                : sql.slice(colStart, colEnd);
        var ref = sql.tok(not);
        edits.add(ctx.replace(lhsStart, not + 1, RewriteContext.kw("NOT", ref) + " " + RewriteContext.kw("EXISTS", ref)));
        int itemFrom = inner.distinct >= 0 ? inner.distinct : items.get(0)[0];
        edits.add(ctx.replace(itemFrom, items.get(0)[1] - 1, "1"));
        String pred = colText + " = " + lhsText;
        int lastEnd = sql.tok(close - 1).end();
        if (inner.where >= 0) {
            if (Predicates.hasOr(sql, inner.where + 1, close, inner.depth)) {
                edits.add(ctx.insert(sql.tok(inner.where + 1).start(), "("));
                edits.add(ctx.insert(lastEnd, ") " + RewriteContext.kw("AND", ref) + " " + pred));
            } else {
                edits.add(ctx.insert(lastEnd, " " + RewriteContext.kw("AND", ref) + " " + pred));
            }
        } else {
            edits.add(ctx.insert(lastEnd, " " + RewriteContext.kw("WHERE", ref) + " " + pred));
        }
        // safety
        Boolean lhsNullable = owner == null ? null : ctx.nullable(owner, lhsColumn);
        Source colOwner = innerOwner(ctx, sql, innerSources, colStart, colEnd);
        Boolean colNullable = colOwner == null ? null : ctx.nullable(colOwner, sql.tok(colEnd).text());
        if (inner.where >= 0 && notNullFilter(sql, inner, colStart, colEnd)) {
            colNullable = Boolean.FALSE;
        }
        String original = sql.slice(lhsStart, close);
        String explanation = innerAlias == null ? Messages.t("rewrite.notIn.explanation", pred)
                : Messages.t("rewrite.notIn.explanation.aliased", pred, innerAlias, lhsText);
        if (Boolean.FALSE.equals(lhsNullable) && Boolean.FALSE.equals(colNullable)) {
            return new Candidate(id(), Messages.t("rewrite.notIn.title"), explanation, edits,
                    Safety.EQUIVALENT_WITH_METADATA, Messages.t("rewrite.notIn.safety.metadata", lhsText, colText),
                    // NOT IN becomes NOT EXISTS: the condition is written differently, proven equal above
                    List.of("Conditions", "Literal values"));
        }
        boolean unknown = lhsNullable == null || colNullable == null;
        return new Candidate(id(), Messages.t("rewrite.notIn.title"), explanation, edits,
                Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES, Messages.t(unknown ? "rewrite.notIn.safety.nulls.unknown"
                        : "rewrite.notIn.safety.nulls", original.replaceAll("\\s+", " "), colText, lhsText));
    }

    /** FROM items of the query block that holds the NOT IN, or the target of UPDATE / DELETE. */
    private static List<Source> outerSources(RewriteContext ctx, Sql sql, int not) {
        Block outer = sql.blockAt(not);
        if (outer != null) {
            List<Source> s = outer.sources();
            return s.isEmpty() ? null : s;
        }
        if (sql.depth[not] != 0) {
            return null;
        }
        StatementKind kind = ctx.statement().kind();
        if (kind != StatementKind.UPDATE && kind != StatementKind.DELETE) {
            return null;
        }
        List<Source> s = Source.dmlTarget(sql);
        return s.isEmpty() ? null : s;
    }

    /** The single outer item an unqualified column can belong to, using metadata when there are several. */
    private static Source ownerOf(RewriteContext ctx, Sql sql, List<Source> sources, String column) {
        if (sources.size() == 1) {
            Source s = sources.get(0);
            Optional<List<String>> cols = ctx.columns(s);
            if (cols.isPresent()) {
                return cols.get().stream().anyMatch(c -> Sql.key(c).equals(Sql.key(column))) ? s : null;
            }
            // inside a routine an unqualified name may be a variable; require the column to be known there
            return ctx.parent() == null ? s : null;
        }
        Source found = null;
        for (Source s : sources) {
            Optional<List<String>> cols = ctx.columns(s);
            if (cols.isEmpty()) {
                return null;
            }
            if (cols.get().stream().anyMatch(c -> Sql.key(c).equals(Sql.key(column)))) {
                if (found != null) {
                    return null;
                }
                found = s;
            }
        }
        return found;
    }

    private static Source innerOwner(RewriteContext ctx, Sql sql, List<Source> sources, int colStart, int colEnd) {
        if (colEnd > colStart) {
            String q = Sql.key(sql.tok(colEnd - 2).text());
            return sources.stream().filter(s -> q.equals(s.exposed(sql))).findFirst().orElse(null);
        }
        return ownerOf(ctx, sql, sources, sql.tok(colEnd).text());
    }

    /** True when the subquery's WHERE has a top-level {@code col IS NOT NULL} term. */
    private static boolean notNullFilter(Sql sql, Block inner, int colStart, int colEnd) {
        List<int[]> terms = Predicates.conjuncts(sql, inner.where + 1, inner.whereEnd(), inner.depth);
        if (terms == null) {
            return false;
        }
        String col = sql.norm(colStart, colEnd + 1);
        for (int[] t : terms) {
            int n = t[1] - t[0];
            if (n >= 4 && sql.word(t[1] - 3, "IS") && sql.word(t[1] - 2, "NOT") && sql.word(t[1] - 1, "NULL")
                    && sql.norm(t[0], t[1] - 3).equals(col)) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesQualifier(Sql sql, int open, int close, String qualifier) {
        for (int i = open + 1; i < close; i++) {
            if (sql.isName(i) && sql.sym(i + 1, '.') && Sql.key(sql.tok(i).text()).equals(qualifier)) {
                return true;
            }
        }
        return false;
    }

    /** An alias not used as a word anywhere in the statement. */
    static String freshAlias(Sql sql) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < sql.size(); i++) {
            if (sql.isName(i)) {
                used.add(Sql.key(sql.tok(i).text()));
            }
        }
        String base = sql.tok(0).text().equals(sql.tok(0).text().toLowerCase(Locale.ROOT)) ? "sq" : "SQ";
        for (int n = 1; ; n++) {
            String a = base + n;
            if (!used.contains(Sql.key(a))) {
                return a;
            }
        }
    }
}
