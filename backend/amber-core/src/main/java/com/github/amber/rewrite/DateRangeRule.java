package com.github.amber.rewrite;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.Dialect;
import com.github.amber.dialect.split.ScanToken;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AMB-S008: a day-truncating function on a filtered column, {@code TRUNC(d) = x} (Oracle),
 * {@code CAST(d AS DATE) = x} / {@code CONVERT(DATE, d) = x} (SQL Server), {@code d::date = x} /
 * {@code date_trunc('day', d) = x} (PostgreSQL), {@code DATE(d) = x} (MySQL, MariaDB, Db2, SQLite),
 * becomes a range on the bare column, {@code d >= x AND d < x + 1 day}, which an index on {@code d}
 * can serve. {@code >=, <, >, <=} and {@code BETWEEN} are rewritten the same way. Functions that do not
 * map to a range ({@code UPPER(col) = 'X'}, …) are never rewritten.
 *
 * <p>Equivalent when {@code d} is a date/time column (known from metadata) and {@code x} has no time of
 * day (a date literal, or an expression that truncates to a day). Otherwise the rewrite is offered with
 * a note on what it assumes.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class DateRangeRule implements RewriteRule {

    private static final Set<String> BOUNDARY = Set.of("WHERE", "AND", "OR", "ON", "WHEN", "HAVING", "NOT");
    private static final Set<String> VALUE_END = Set.of("AND", "OR", "THEN", "GROUP", "ORDER", "HAVING", "UNION",
            "EXCEPT", "INTERSECT", "MINUS", "LIMIT", "FETCH", "OFFSET", "WHEN", "ELSE", "END", "CONNECT", "START",
            "FOR", "WINDOW", "QUALIFY", "RETURNING", "INTO", "LOOP", "UNTIL");
    private static final Pattern ISO_DATE = Pattern.compile("'\\d{4}-\\d{2}-\\d{2}'");
    private static final Pattern DATE_TYPE = Pattern.compile("(?i).*\\b(DATE|DATETIME\\d?|DATETIMEOFFSET|SMALLDATETIME|TIMESTAMP\\w*|TIMESTAMPTZ)\\b.*");
    private static final Pattern ZONED_TYPE = Pattern.compile("(?i).*(TIME\\s+ZONE|DATETIMEOFFSET).*");

    /** A recognized day-truncation of one column: tokens [start, end) and the column inside. */
    private record DayOf(int start, int end, int colStart, int colEnd) {
    }

    @Override
    public String id() {
        return "AMB-S008";
    }

    @Override
    public List<Candidate> propose(RewriteContext ctx) {
        Sql sql = ctx.sql();
        List<Candidate> out = new ArrayList<>();
        for (int i = 1; i < sql.size(); i++) {
            if (!(sql.wordIn(i - 1, BOUNDARY) || sql.sym(i - 1, '('))) {
                continue;
            }
            DayOf f = dayOf(sql, i);
            if (f == null) {
                continue;
            }
            Candidate c = rewrite(ctx, sql, f);
            if (c != null) {
                out.add(c);
                i = f.end;
            }
        }
        return out;
    }

    private Candidate rewrite(RewriteContext ctx, Sql sql, DayOf f) {
        int k = f.end;
        String op;
        int vStart;
        if (sql.sym(k, '=')) {
            op = "=";
            vStart = k + 1;
        } else if ((sql.sym(k, '>') || sql.sym(k, '<')) && sql.sym(k + 1, '=')
                && sql.tok(k + 1).start() == sql.tok(k).end()) {
            op = sql.tok(k).text() + "=";
            vStart = k + 2;
        } else if ((sql.sym(k, '>') || sql.sym(k, '<')) && !sql.sym(k + 1, '>') && !sql.sym(k + 1, '=')) {
            op = sql.tok(k).text();
            vStart = k + 1;
        } else if (sql.word(k, "BETWEEN")) {
            op = "BETWEEN";
            vStart = k + 1;
        } else {
            return null;
        }
        int d = sql.depth[f.start];
        int vEnd = valueEnd(sql, vStart, d, op.equals("BETWEEN"));
        if (vEnd <= vStart) {
            return null;
        }
        int v2Start = -1;
        int v2End = -1;
        if (op.equals("BETWEEN")) {
            if (!sql.word(vEnd, "AND")) {
                return null;
            }
            v2Start = vEnd + 1;
            v2End = valueEnd(sql, v2Start, d, false);
            if (v2End <= v2Start) {
                return null;
            }
        }
        Value lo = value(ctx, sql, vStart, vEnd);
        Value hi = op.equals("BETWEEN") ? value(ctx, sql, v2Start, v2End) : lo;
        if (lo == null || hi == null) {
            return null;
        }
        ScanToken ref = sql.tok(f.start);
        String col = sql.slice(f.colStart, f.colEnd);
        String ge = " >= ";
        String and = " " + RewriteContext.kw("AND", ref) + " ";
        String replacement = switch (op) {
            case "=" -> col + ge + lo.text + and + col + " < " + nextDay(ctx.dialect(), lo, ref);
            case ">=" -> col + ge + lo.text;
            case "<" -> col + " < " + lo.text;
            case ">" -> col + ge + nextDay(ctx.dialect(), lo, ref);
            case "<=" -> col + " < " + nextDay(ctx.dialect(), lo, ref);
            default -> col + ge + lo.text + and + col + " < " + nextDay(ctx.dialect(), hi, ref);
        };
        int last = op.equals("BETWEEN") ? v2End - 1 : vEnd - 1;
        if ((op.equals("=") || op.equals("BETWEEN")) && (sql.word(f.start - 1, "NOT") || sql.word(f.start - 1, "OR")
                || (sql.word(last + 1, "OR") && sql.depth[last + 1] == d))) {
            replacement = "(" + replacement + ")";  // NOT needs it; next to OR it keeps the reading obvious
        }
        Edit edit = ctx.edit(sql.tok(f.start).start(), sql.tok(last).end(), replacement);
        String original = sql.slice(f.start, last);
        // safety
        Source owner = owner(ctx, sql, f);
        String type = owner == null ? null : ctx.type(owner, sql.tok(f.colEnd).text());
        if (type != null && !DATE_TYPE.matcher(type).matches()) {
            return null;                            // not a date/time column: the function means something else
        }
        boolean aligned = lo.dayAligned && hi.dayAligned;
        String explanation = Messages.t("rewrite.dateRange.explanation", sql.slice(f.start, f.end - 1), col);
        Safety safety;
        String note;
        boolean between = op.equals("BETWEEN");
        String value = between ? Messages.t("rewrite.dateRange.between", lo.text, hi.text) : lo.text;
        if (ctx.dialect() == Dialect.SQLITE) {
            safety = Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES;
            note = Messages.t("rewrite.dateRange.safety.sqlite", col);
        } else if (type == null) {
            safety = Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES;
            note = aligned ? Messages.t("rewrite.dateRange.safety.unknownType", col)
                    : Messages.t("rewrite.dateRange.safety.unknownType.time", col, value);
        } else if (ZONED_TYPE.matcher(type).matches()) {
            safety = Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES;
            note = Messages.t("rewrite.dateRange.safety.zoned", col, type);
        } else if (!aligned) {
            safety = Safety.CHANGES_BEHAVIOR_IN_EDGE_CASES;
            note = op.equals("=") ? Messages.t("rewrite.dateRange.safety.timeEquals", value,
                    original.replaceAll("\\s+", " ")) : Messages.t("rewrite.dateRange.safety.timeRange", value);
        } else {
            safety = Safety.EQUIVALENT_WITH_METADATA;
            note = Messages.t(between ? "rewrite.dateRange.safety.wholeDays" : "rewrite.dateRange.safety.wholeDay",
                    col, type, value);
        }
        return new Candidate(id(), Messages.t("rewrite.dateRange.title"), explanation, List.of(edit), safety, note);
    }

    // ----- the truncation ---------------------------------------------------------------------------

    /** A day truncation of a column starting at {@code i}, per dialect, or null. */
    private static DayOf dayOf(Sql sql, int i) {
        Dialect d = sql.dialect;
        String fn = sql.isName(i) ? sql.tok(i).upper() : "";
        boolean call = sql.sym(i + 1, '(');
        int close = call ? sql.close(i + 1) : -1;
        if (call && close > 0) {
            // FN ( col )
            if ((fn.equals("TRUNC") && (d == Dialect.ORACLE || d == Dialect.H2))
                    || (fn.equals("DATE") && (d == Dialect.MYSQL || d == Dialect.MARIADB || d == Dialect.DB2
                    || d == Dialect.SQLITE || d == Dialect.POSTGRESQL))) {
                int c = sql.nameEnd(i + 2);
                if (c > 0 && c + 1 == close) {
                    return new DayOf(i, close + 1, i + 2, c);
                }
                // TRUNC ( col , 'DD' )
                if (d == Dialect.ORACLE && fn.equals("TRUNC") && c > 0 && sql.sym(c + 1, ',') && c + 3 == close
                        && Set.of("'DD'", "'DDD'", "'J'").contains(sql.tok(c + 2).upper())) {
                    return new DayOf(i, close + 1, i + 2, c);
                }
                return null;
            }
            // CAST ( col AS DATE )
            if (fn.equals("CAST") && d != Dialect.ORACLE && d != Dialect.SQLITE) {
                int c = sql.nameEnd(i + 2);
                if (c > 0 && sql.word(c + 1, "AS") && sql.word(c + 2, "DATE") && c + 3 == close) {
                    return new DayOf(i, close + 1, i + 2, c);
                }
                return null;
            }
            // CONVERT ( DATE , col )
            if (fn.equals("CONVERT") && d == Dialect.SQLSERVER && sql.word(i + 2, "DATE") && sql.sym(i + 3, ',')) {
                int c = sql.nameEnd(i + 4);
                return c > 0 && c + 1 == close ? new DayOf(i, close + 1, i + 4, c) : null;
            }
            // date_trunc ( 'day' , col )
            if (fn.equals("DATE_TRUNC") && d == Dialect.POSTGRESQL && sql.tok(i + 2).text().equalsIgnoreCase("'day'")
                    && sql.sym(i + 3, ',')) {
                int c = sql.nameEnd(i + 4);
                return c > 0 && c + 1 == close ? new DayOf(i, close + 1, i + 4, c) : null;
            }
            return null;
        }
        // col :: date
        if (d == Dialect.POSTGRESQL && sql.isName(i)) {
            int c = sql.nameEnd(i);
            if (c > 0 && sql.sym(c + 1, ':') && sql.sym(c + 2, ':') && sql.word(c + 3, "DATE")
                    && !sql.sym(c + 4, '(') && !sql.sym(c + 4, '[')) {
                return new DayOf(i, c + 4, i, c);
            }
        }
        return null;
    }

    private static int valueEnd(Sql sql, int from, int depth, boolean between) {
        int i = from;
        while (i < sql.size()) {
            if (sql.depth[i] < depth || sql.sym(i, ';') || (sql.depth[i] == depth
                    && (sql.wordIn(i, VALUE_END) || sql.sym(i, ',') || sql.sym(i, '=') || sql.sym(i, '<')
                    || sql.sym(i, '>') || sql.wordIn(i, Set.of("IS", "LIKE", "IN", "BETWEEN"))))) {
                return i;
            }
            if (sql.depth[i] == depth && sql.isName(i) && sql.wordIn(i, Set.of("SELECT", "FROM", "WHERE", "CASE"))) {
                return -1;
            }
            i++;
        }
        return between ? -1 : i;
    }

    // ----- the compared value -------------------------------------------------------------------

    /**
     * @param text       value as written
     * @param literal    the date when the value is a date literal, else null
     * @param typed      the literal is written {@code DATE '…'}
     * @param dayAligned the value has no time of day
     * @param atom       the value needs no parentheses when an operator is applied to it
     */
    private record Value(String text, LocalDate literal, boolean typed, boolean dayAligned, boolean atom) {
    }

    private static Value value(RewriteContext ctx, Sql sql, int from, int to) {
        Dialect d = ctx.dialect();
        String text = sql.slice(from, to - 1);
        for (int i = from; i < to; i++) {
            if (sql.word(i, "SELECT")) {
                return null;
            }
        }
        int n = to - from;
        // typed literal DATE 'yyyy-mm-dd'
        if (n == 2 && sql.word(from, "DATE") && ISO_DATE.matcher(sql.tok(from + 1).text()).matches()
                && d != Dialect.SQLSERVER && d != Dialect.SQLITE) {
            LocalDate date = parse(sql.tok(from + 1).text());
            return date == null ? null : new Value(text, date, true, true, true);
        }
        if (n == 1 && sql.tok(from).type() == ScanToken.Type.STRING) {
            if (d == Dialect.ORACLE || !ISO_DATE.matcher(sql.tok(from).text()).matches()) {
                return null;                        // NLS-dependent or not a plain date: cannot add a day safely
            }
            LocalDate date = parse(sql.tok(from).text());
            if (date != null && d == Dialect.DB2) {
                String fn = RewriteContext.kw("DATE", sql.tok(0));
                return new Value(fn + "(" + text + ")", date, true, true, true);   // a date, not a string, for TIMESTAMP
            }
            return date == null ? null : new Value(text, date, false, true, true);
        }
        if (n == 1 && sql.tok(from).type() == ScanToken.Type.NUMBER) {
            return null;
        }
        if (d == Dialect.DB2 && n == 2 && sql.word(from, "CURRENT") && sql.word(from + 1, "DATE")) {
            return new Value(text, null, false, true, true);
        }
        boolean atom = atom(sql, from, to);
        return new Value(text, null, false, aligned(sql, from, to, atom), atom);
    }

    /** One name, bind, call, or {@code x::date}: no parentheses needed around it. */
    private static boolean atom(Sql sql, int from, int to) {
        int i = from;
        int e;
        if (sql.sym(i, '?')) {
            e = i;
        } else {
            if (sql.sym(i, ':') || sql.sym(i, '$') || sql.sym(i, '@')) {
                i++;
            }
            if (sql.isName(i)) {
                e = sql.nameEnd(i);
                if (sql.sym(e + 1, '(') && sql.close(e + 1) > 0) {
                    e = sql.close(e + 1);
                }
            } else if (i < to && sql.tok(i).type() == ScanToken.Type.NUMBER && i > from) {
                e = i;                              // $1
            } else {
                return false;
            }
        }
        if (sql.sym(e + 1, ':') && sql.sym(e + 2, ':') && sql.isName(e + 3)) {
            e += 3;
        }
        return e == to - 1;
    }

    /** True when the value is a whole day: a truncating function, CURRENT_DATE (not Oracle's), a date cast. */
    private static boolean aligned(Sql sql, int from, int to, boolean atom) {
        Dialect d = sql.dialect;
        if (!atom) {
            return false;
        }
        String first = sql.isName(from) ? sql.tok(from).upper() : "";
        if (dayOfValue(sql, from, to)) {
            return true;
        }
        if (to - from == 1 && first.equals("CURRENT_DATE") && d != Dialect.ORACLE) {
            return true;
        }
        if (d == Dialect.ORACLE && first.equals("TO_DATE") && sql.sym(from + 1, '(')) {
            int close = sql.close(from + 1);
            if (close == to - 1 && close - from == 5 && sql.sym(from + 3, ',')
                    && ISO_DATE.matcher(sql.tok(from + 2).text()).matches()
                    && sql.tok(from + 4).text().equalsIgnoreCase("'YYYY-MM-DD'")) {
                return true;
            }
        }
        if (d == Dialect.ORACLE && first.equals("TRUNC") && sql.sym(from + 1, '(')) {
            int close = sql.close(from + 1);
            int comma = -1;
            for (int i = from + 2; i < close; i++) {
                if (sql.depth[i] == sql.depth[from] + 1 && sql.sym(i, ',')) {
                    comma = i;
                }
            }
            return close == to - 1 && (comma < 0 || (comma + 2 == close
                    && Set.of("'DD'", "'DDD'", "'J'").contains(sql.tok(comma + 1).upper())));
        }
        if ((d == Dialect.MYSQL || d == Dialect.MARIADB) && to - from == 3 && first.equals("CURDATE")) {
            return true;
        }
        if (d == Dialect.SQLITE && first.equals("DATE")) {
            return true;
        }
        if (first.equals("CAST") && sql.sym(from + 1, '(') && sql.close(from + 1) == to - 1
                && sql.word(to - 2, "DATE") && sql.word(to - 3, "AS")) {
            return d != Dialect.ORACLE;
        }
        if (d == Dialect.POSTGRESQL && sql.word(to - 1, "DATE") && sql.sym(to - 2, ':') && sql.sym(to - 3, ':')) {
            return true;
        }
        return false;
    }

    /** The value is itself a day truncation of a column per the dialect (e.g. {@code TRUNC(b.d)}). */
    private static boolean dayOfValue(Sql sql, int from, int to) {
        DayOf f = dayOf(sql, from);
        return f != null && f.end == to;
    }

    private static LocalDate parse(String quoted) {
        try {
            return LocalDate.parse(quoted.substring(1, quoted.length() - 1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code v + 1 day} in the dialect's syntax; a literal becomes the next day's literal. */
    static String nextDay(Dialect d, Value v, ScanToken ref) {
        if (v.literal != null) {
            String next = "'" + v.literal.plusDays(1) + "'";
            if (v.typed && v.text.endsWith(")")) {
                return v.text.substring(0, v.text.indexOf('\'')) + next + ")";
            }
            return v.typed ? v.text.substring(0, v.text.indexOf('\'')) + next : next;
        }
        String x = v.atom ? v.text : "(" + v.text + ")";
        return switch (d) {
            case ORACLE -> x + " + 1";
            case SQLSERVER -> RewriteContext.kw("DATEADD", ref) + "(" + RewriteContext.kw("DAY", ref) + ", 1, " + v.text + ")";
            case POSTGRESQL -> x + " + " + RewriteContext.kw("INTERVAL", ref) + " '1 day'";
            case MYSQL, MARIADB -> x + " + " + RewriteContext.kw("INTERVAL", ref) + " 1 " + RewriteContext.kw("DAY", ref);
            case DB2 -> x + " + 1 " + RewriteContext.kw("DAY", ref);
            case H2 -> RewriteContext.kw("DATEADD", ref) + "(" + RewriteContext.kw("DAY", ref) + ", 1, " + v.text + ")";
            case SQLITE -> RewriteContext.kw("DATE", ref) + "(" + v.text + ", '+1 day')";
        };
    }

    /** The FROM item (or DML target) the truncated column belongs to, or null. */
    private static Source owner(RewriteContext ctx, Sql sql, DayOf f) {
        Block b = sql.blockAt(f.start);
        List<Source> sources = b != null ? b.sources() : Source.dmlTarget(sql);
        if (sources.isEmpty()) {
            return null;
        }
        if (f.colEnd > f.colStart) {
            String q = Sql.key(sql.tok(f.colEnd - 2).text());
            return sources.stream().filter(s -> q.equals(s.exposed(sql))).findFirst().orElse(null);
        }
        if (sources.size() == 1) {
            return sources.get(0);
        }
        String col = Sql.key(sql.tok(f.colEnd).text());
        Source found = null;
        for (Source s : sources) {
            if (ctx.meta(s).flatMap(m -> m.column(col)).isPresent()) {
                if (found != null) {
                    return null;
                }
                found = s;
            }
        }
        return found;
    }
}
