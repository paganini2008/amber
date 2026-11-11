package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.dialect.split.ScanToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * AMB-D002: the same subquery written several times in one statement is computed once in a CTE and
 * referenced by name. A derived table becomes the CTE name (its alias is kept); an expression subquery
 * becomes {@code (SELECT * FROM name)}.
 *
 * <p>Only subqueries that mean the same thing everywhere are factored. Nothing may refer to the outer query:
 * a derived table in FROM / JOIN cannot (only LATERAL and APPLY can), and in an expression subquery every
 * column must be qualified by a table of the subquery or provably belong to one of its tables (metadata).
 * Nothing in it may change between evaluations (no SYSDATE / NOW / random / sequences / ROWNUM / row
 * limits). Two things are allowed with a stated edge case: a window function that numbers rows
 * (ROW_NUMBER, LAG…), which may order ties differently in each copy, and a function Amber does not know,
 * which is assumed to return the same value for the same arguments. The WITH goes where the dialect accepts
 * it for the statement kind (before INSERT in SQL Server and SQLite).
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class RepeatedSubqueryRule implements RewriteRule {

    private static final Set<StatementKind> KINDS = Set.of(StatementKind.SELECT, StatementKind.SELECT_INTO,
            StatementKind.INSERT, StatementKind.CREATE_VIEW, StatementKind.CREATE_TABLE_AS,
            StatementKind.CREATE_MATERIALIZED_VIEW);
    private static final Set<String> VOLATILE = Set.of("SYSDATE", "SYSTIMESTAMP", "NOW", "CURRENT_TIMESTAMP",
            "CURRENT_DATE", "CURRENT_TIME", "LOCALTIMESTAMP", "LOCALTIME", "GETDATE", "GETUTCDATE", "SYSDATETIME",
            "RAND", "RANDOM", "NEWID", "NEXTVAL", "CURRVAL", "ROWNUM", "SYS_GUID", "UUID", "DBMS_RANDOM", "NEXT",
            "TOP", "LIMIT", "FETCH", "OFFSET", "LEVEL", "CURRENT", "USER", "CONNECT", "SAMPLE", "TABLESAMPLE");
    private static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NVL", "NVL2",
            "ISNULL", "IFNULL", "NULLIF", "CAST", "CONVERT", "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "SUBSTR",
            "SUBSTRING", "LENGTH", "LEN", "CONCAT", "REPLACE", "ROUND", "TRUNC", "FLOOR", "CEIL", "CEILING", "ABS", "MOD",
            "DECODE", "TO_CHAR", "TO_NUMBER", "TO_DATE", "EXTRACT", "DATE_TRUNC", "DATEADD", "DATEDIFF", "DATEPART",
            "YEAR", "MONTH", "DAY", "DATE", "GREATEST", "LEAST", "SIGN", "POWER", "SQRT", "INSTR", "CHARINDEX",
            "POSITION", "LPAD", "RPAD", "LEFT", "RIGHT", "IIF", "STDDEV", "VARIANCE", "MEDIAN");
    private static final Set<String> KEYWORDS = Set.of("SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS",
            "NULL", "AS", "ON", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "GROUP", "BY", "ORDER",
            "HAVING", "DISTINCT", "ALL", "UNION", "EXCEPT", "INTERSECT", "MINUS", "CASE", "WHEN", "THEN", "ELSE", "END",
            "BETWEEN", "LIKE", "EXISTS", "ASC", "DESC", "NULLS", "TRUE", "FALSE", "ESCAPE", "ANY", "SOME", "USING",
            "PARTITION", "ROWS", "RANGE", "UNBOUNDED", "PRECEDING", "FOLLOWING", "ROW");
    /** Window functions whose result depends on how ties are ordered. */
    private static final Set<String> TIE_SENSITIVE = Set.of("ROW_NUMBER", "NTILE", "LAG", "LEAD", "FIRST_VALUE",
            "LAST_VALUE", "NTH_VALUE");
    /** Window functions that give the same result whatever the order of ties. */
    private static final Set<String> WINDOW = Set.of("RANK", "DENSE_RANK", "PERCENT_RANK", "CUME_DIST",
            "RATIO_TO_REPORT");
    /** Words that are keywords only in typed positions and may otherwise be column names. */
    private static final Set<String> UNITS = Set.of("DAY", "MONTH", "YEAR", "HOUR", "MINUTE", "SECOND");

    @Override
    public String id() {
        return "AMB-D002";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        List<Candidate> out = new ArrayList<>();
        StatementKind kind = ctx.statement().kind();
        if (!KINDS.contains(kind)) {
            return out;
        }
        Sql sql = ctx.sql();
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i + 1 < sql.size(); i++) {
            if (sql.sym(i, '(') && sql.word(i + 1, "SELECT") && sql.close(i) - i >= 8) {
                groups.computeIfAbsent(sql.exact(i + 1, sql.close(i)), k -> new ArrayList<>()).add(i);
            }
        }
        List<int[]> taken = new ArrayList<>();
        for (List<Integer> opens : groups.values()) {
            if (opens.size() < 2 || nested(sql, opens) || opens.stream().anyMatch(o -> inside(o, taken))) {
                continue;
            }
            Candidate c = rewrite(ctx, sql, opens);
            if (c != null) {
                out.add(c);
                opens.forEach(o -> taken.add(new int[]{o, sql.close(o)}));
            }
        }
        return out;
    }

    private Candidate rewrite(RewriteContext ctx, Sql sql, List<Integer> opens) {
        int open = opens.get(0);
        int close = sql.close(open);
        // A derived table (not LATERAL / APPLY) cannot see the outer query, whatever its columns look like.
        boolean derivedOnly = opens.stream().allMatch(o -> derived(sql, o) && !sql.wordIn(o - 1, Set.of("APPLY", "LATERAL")));
        List<String> caveats = caveats(ctx, sql, open, close, derivedOnly);
        if (caveats == null) {
            return null;
        }
        Placement place = placement(ctx, sql);
        if (place == null) {
            return null;
        }
        for (int o : opens) {
            if (place.cteBodies.stream().anyMatch(r -> o > r[0] && o < r[1])) {
                return null;                        // inside an existing CTE: ordering of CTEs would change
            }
        }
        String name = freshName(sql);
        String body = sql.slice(open + 1, close - 1);
        ScanToken ref = sql.tok(0);
        List<Edit> edits = new ArrayList<>();
        String cte = name + " " + RewriteContext.kw("AS", ref) + " (" + body + ")";
        if (place.afterCte >= 0) {
            edits.add(ctx.insert(sql.tok(place.afterCte).end(), ",\n" + place.indent + cte));
        } else {
            edits.add(ctx.insert(sql.tok(place.before).start(), RewriteContext.kw("WITH", ref) + " " + cte + "\n"
                    + place.indent));
        }
        for (int o : opens) {
            int c = sql.close(o);
            if (derived(sql, o)) {
                edits.add(ctx.replace(o, c, name));
            } else {
                edits.add(ctx.replace(o + 1, c - 1, RewriteContext.kw("SELECT", ref) + " * "
                        + RewriteContext.kw("FROM", ref) + " " + name));
            }
        }
        Safety safety = caveats.isEmpty() ? Safety.EQUIVALENT : Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES;
        String note = caveats.isEmpty() ? Messages.t("rewrite.repeatedSubquery.safety", opens.size())
                : String.join(" ", caveats);
        return new Candidate(id(), Messages.t("rewrite.repeatedSubquery.title"),
                Messages.t("rewrite.repeatedSubquery.explanation", opens.size(), name),
                edits, safety, note, List.of("Ordering and row limits:"));
    }

    // ----- checks -----------------------------------------------------------------------------------

    private static boolean nested(Sql sql, List<Integer> opens) {
        for (int a : opens) {
            for (int b : opens) {
                if (a != b && b > a && b < sql.close(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inside(int o, List<int[]> ranges) {
        return ranges.stream().anyMatch(r -> o >= r[0] && o <= r[1]);
    }

    /**
     * Null when the subquery cannot be shared (it refers to the outer query or uses something volatile);
     * otherwise the edge cases sharing it brings, empty when there are none.
     */
    private static List<String> caveats(RewriteContext ctx, Sql sql, int open, int close, boolean derivedOnly) {
        Set<String> tieSensitive = new TreeSet<>();
        Set<String> unknown = new TreeSet<>();
        Set<String> names = new HashSet<>();
        Set<Integer> sourceTokens = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        List<Source> sources = new ArrayList<>();
        for (Block b : sql.blocks()) {
            if (b.start <= open || b.start >= close) {
                continue;
            }
            if (b.limited || b.exotic || b.connectBy >= 0 || b.forClause >= 0) {
                return null;
            }
            for (Source s : b.sources()) {
                if (s.exposed(sql) == null) {
                    if (derivedOnly) {
                        continue;                   // an inner derived table without an alias: nothing refers to it
                    }
                    return null;
                }
                names.add(s.exposed(sql));
                sources.add(s);
                for (int k = s.nameStart(); k <= s.nameEnd() && s.kind() == Source.Kind.TABLE; k++) {
                    sourceTokens.add(k);
                }
                if (s.aliasIndex() >= 0) {
                    sourceTokens.add(s.aliasIndex());
                }
            }
            for (int[] item : b.items()) {
                String a = b.itemAlias(item);
                if (a != null) {
                    aliases.add(a);
                    sourceTokens.add(item[1] - 1);
                }
            }
        }
        for (int j = open + 1; j < close; j++) {
            ScanToken t = sql.tok(j);
            if (t.type() == ScanToken.Type.UNTERMINATED) {
                return null;
            }
            if (!sql.isName(j) || sourceTokens.contains(j)) {
                continue;
            }
            String key = Sql.key(t.text());
            if (t.type() == ScanToken.Type.WORD && VOLATILE.contains(key)) {
                return null;
            }
            if (sql.sym(j - 1, '.') || sql.sym(j - 1, ':') || t.text().startsWith("@") || sql.word(j - 1, "AS")) {
                continue;                           // a qualified part, a bind variable, an alias or a type
            }
            if (keywordHere(sql, j, key)) {
                continue;
            }
            if (sql.sym(j + 1, '(')) {
                if (TIE_SENSITIVE.contains(key)) {
                    tieSensitive.add(key);
                } else if (!FUNCTIONS.contains(key) && !WINDOW.contains(key)
                        && !sql.wordIn(j, Set.of("IN", "EXISTS", "ANY", "SOME", "ALL", "AS", "OVER"))) {
                    unknown.add(t.text());
                }
                continue;
            }
            if (derivedOnly) {
                continue;                           // a column: it can only belong to the subquery's own tables
            }
            if (sql.sym(j + 1, '.')) {
                if (!names.contains(key)) {
                    return null;                   // qualified by a name the subquery does not define
                }
                continue;
            }
            if (t.type() == ScanToken.Type.WORD && aliases.contains(key)) {
                continue;
            }
            if (!innerColumn(ctx, sources, key)) {
                return null;
            }
        }
        List<String> out = new ArrayList<>();
        if (!tieSensitive.isEmpty()) {
            out.add(Messages.t("rewrite.repeatedSubquery.ties", String.join(", ", tieSensitive)));
        }
        if (!unknown.isEmpty()) {
            out.add(Messages.t("rewrite.repeatedSubquery.functions", String.join(", ", unknown)));
        }
        return out;
    }

    /** True when the word at {@code j} is used as a keyword here, not as a column name. */
    private static boolean keywordHere(Sql sql, int j, String key) {
        if (sql.tok(j).type() != ScanToken.Type.WORD) {
            return false;
        }
        if (KEYWORDS.contains(key)) {
            return true;
        }
        if (Set.of("DATE", "TIMESTAMP", "INTERVAL").contains(key)) {
            return j + 1 < sql.size() && sql.tok(j + 1).type() == ScanToken.Type.STRING;
        }
        if (Set.of("FIRST", "LAST").contains(key)) {
            return sql.word(j - 1, "NULLS");
        }
        if (UNITS.contains(key)) {
            return sql.word(j + 1, "FROM") || (j > 0 && (sql.tok(j - 1).type() == ScanToken.Type.STRING
                    || sql.tok(j - 1).type() == ScanToken.Type.NUMBER)) || (sql.sym(j - 1, '(') && sql.sym(j + 1, ','));
        }
        return false;
    }

    private static boolean innerColumn(RewriteContext ctx, List<Source> sources, String column) {
        for (Source s : sources) {
            Optional<List<String>> cols = ctx.columns(s);
            if (cols.isPresent() && cols.get().stream().anyMatch(c -> Sql.key(c).equals(column))) {
                return true;
            }
        }
        return false;
    }

    /** True when the subquery at {@code open} is a derived table (used like a table in FROM / JOIN). */
    private static boolean derived(Sql sql, int open) {
        if (sql.wordIn(open - 1, Set.of("FROM", "JOIN", "APPLY", "LATERAL"))) {
            return true;
        }
        if (sql.sym(open - 1, ',')) {
            Block b = sql.blockAt(open - 1);
            return b != null && b.from >= 0 && open > b.from && (b.where < 0 || open < b.where);
        }
        return false;
    }

    // ----- where the WITH goes ----------------------------------------------------------------------

    /**
     * @param before     token before which {@code WITH name AS (…)} is inserted, or -1
     * @param afterCte   closing parenthesis of the last existing CTE to append after, or -1
     * @param cteBodies  token ranges of the existing CTE bodies
     */
    private record Placement(int before, int afterCte, String indent, List<int[]> cteBodies) {
    }

    private static Placement placement(RewriteContext ctx, Sql sql) {
        StatementKind kind = ctx.statement().kind();
        Dialect d = ctx.dialect();
        int q;
        if (kind == StatementKind.SELECT || kind == StatementKind.SELECT_INTO
                || (kind == StatementKind.INSERT && (d == Dialect.SQLSERVER || d == Dialect.SQLITE))) {
            q = 0;
        } else if (kind == StatementKind.INSERT) {
            q = firstAtDepth0(sql, 1, Set.of("SELECT", "WITH"));
        } else {
            q = -1;
            for (int i = 1; i + 1 < sql.size(); i++) {
                if (sql.depth[i] == 0 && sql.word(i, "AS") && (sql.word(i + 1, "SELECT") || sql.word(i + 1, "WITH"))) {
                    q = i + 1;
                    break;
                }
                // CREATE TABLE t AS ( SELECT … ): WITH … ( SELECT … ) parses, ( WITH … SELECT … ) does not
                if (sql.depth[i] == 0 && sql.word(i, "AS") && sql.sym(i + 1, '(') && sql.close(i + 1) == lastToken(sql)
                        && (sql.word(i + 2, "SELECT") || sql.word(i + 2, "WITH"))) {
                    q = sql.word(i + 2, "WITH") ? i + 2 : i + 1;
                    break;
                }
            }
        }
        if (q < 0 || !(sql.word(q, "SELECT") || sql.word(q, "WITH") || sql.sym(q, '(') || (q == 0 && sql.word(q, "INSERT")))) {
            return null;
        }
        String indent = indent(sql, q);
        if (!sql.word(q, "WITH")) {
            return new Placement(q, -1, indent, List.of());
        }
        // existing WITH [RECURSIVE] name [(cols)] AS [NOT] [MATERIALIZED] (…) [, …]
        int i = q + 1;
        if (sql.word(i, "RECURSIVE")) {
            i++;
        }
        List<int[]> bodies = new ArrayList<>();
        int lastClose = -1;
        while (true) {
            if (!sql.isName(i)) {
                return null;
            }
            i++;
            if (sql.sym(i, '(')) {
                i = sql.close(i) + 1;
            }
            if (!sql.word(i, "AS")) {
                return null;
            }
            i++;
            while (sql.wordIn(i, Set.of("NOT", "MATERIALIZED"))) {
                i++;
            }
            if (!sql.sym(i, '(') || sql.close(i) < 0) {
                return null;
            }
            bodies.add(new int[]{i, sql.close(i)});
            lastClose = sql.close(i);
            i = lastClose + 1;
            if (!sql.sym(i, ',')) {
                break;
            }
            i++;
        }
        return new Placement(-1, lastClose, indent, bodies);
    }

    /** The last token of the statement, a trailing ';' not counted. */
    private static int lastToken(Sql sql) {
        int last = sql.size() - 1;
        return sql.sym(last, ';') ? last - 1 : last;
    }

    private static int firstAtDepth0(Sql sql, int from, Set<String> words) {
        for (int i = from; i < sql.size(); i++) {
            if (sql.depth[i] == 0 && sql.wordIn(i, words)) {
                return i;
            }
        }
        return -1;
    }

    /** The whitespace before token {@code i} on its line when nothing else precedes it there, else one space. */
    private static String indent(Sql sql, int i) {
        int start = sql.tok(i).start();
        int ls = start;
        while (ls > 0 && sql.text.charAt(ls - 1) != '\n' && sql.text.charAt(ls - 1) != '\r') {
            ls--;
        }
        String prefix = sql.text.substring(ls, start);
        return prefix.isBlank() ? prefix : "";
    }

    private static String freshName(Sql sql) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < sql.size(); i++) {
            if (sql.isName(i)) {
                used.add(Sql.key(sql.tok(i).text()));
            }
        }
        boolean lower = sql.tok(0).text().equals(sql.tok(0).text().toLowerCase(Locale.ROOT));
        for (int n = 1; ; n++) {
            String name = (lower ? "shared_q" : "SHARED_Q") + n;
            if (!used.contains(Sql.key(name))) {
                return name;
            }
        }
    }
}
