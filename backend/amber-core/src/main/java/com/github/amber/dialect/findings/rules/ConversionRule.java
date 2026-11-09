package com.github.amber.dialect.findings.rules;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.dialect.findings.Finding.Severity;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.split.ScanToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AMB-P002 implicit conversion: a column whose type is known from metadata compared with a literal or a
 * declared variable of another type. Reported cases, by dialect:
 *
 * <ul>
 *   <li>character column vs number: the column is converted on every row (no index; values such as
 *       {@code '007'} match {@code 7}); PostgreSQL rejects the comparison. Not SQLite (type affinity).</li>
 *   <li>number column vs non-numeric string: a run-time conversion error (MySQL silently compares with 0);
 *       vs a numeric string: LOW, the literal is converted once. Not PostgreSQL / SQLite (untyped literals).</li>
 *   <li>date column vs string: Oracle always (the conversion follows NLS_DATE_FORMAT); other dialects when
 *       the string is not an ISO date ({@code yyyy-mm-dd[ hh:mi[:ss]]}; SQL Server also {@code yyyymmdd}).</li>
 *   <li>SQL Server / Oracle: VARCHAR / CHAR column vs a national string {@code N'…'} or NVARCHAR variable:
 *       the column is converted to the national type and an index seek may become a scan.</li>
 * </ul>
 *
 * Variables are typed from {@code DECLARE @v type} (SQL Server) and {@code v type;} declarations in
 * PL/SQL, PL/pgSQL and SQL PL blocks; a name declared with different types is not used.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class ConversionRule {

    enum Kind {
        STRING, NSTRING, NUMBER, DATE, OTHER
    }

    private static final Set<String> STRING_TYPES = Set.of("CHAR", "VARCHAR", "VARCHAR2", "CHARACTER", "TEXT", "CLOB",
            "STRING", "LONGTEXT", "MEDIUMTEXT", "TINYTEXT", "BPCHAR", "GRAPHIC", "VARGRAPHIC", "LONG");
    private static final Set<String> NSTRING_TYPES = Set.of("NCHAR", "NVARCHAR", "NVARCHAR2", "NTEXT", "NCLOB",
            "NATIONAL");
    private static final Set<String> NUMBER_TYPES = Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC", "INT", "INTEGER",
            "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT", "FLOAT", "REAL", "DOUBLE", "MONEY", "SMALLMONEY",
            "BINARY_FLOAT", "BINARY_DOUBLE", "INT2", "INT4", "INT8", "FLOAT4", "FLOAT8", "SERIAL", "BIGSERIAL",
            "DECFLOAT", "PLS_INTEGER", "BINARY_INTEGER", "SIMPLE_INTEGER", "NATURAL", "POSITIVE");
    private static final Set<String> DATE_TYPES = Set.of("DATE", "DATETIME", "DATETIME2", "SMALLDATETIME", "TIMESTAMP",
            "TIMESTAMPTZ", "DATETIMEOFFSET");
    private static final Pattern NUMERIC = Pattern.compile("\\s*[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?\\s*");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?");
    private static final Pattern MSSQL_SAFE = Pattern.compile("\\d{8}( \\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?"
            + "|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");
    private static final Set<String> BEFORE = Set.of("WHERE", "AND", "OR", "ON", "NOT", "HAVING", "WHEN");

    private final RuleContext ctx;
    /** Upper-case variable name → declared kind (OTHER when declared with several kinds). */
    private final Map<String, Kind> variables = new HashMap<>();

    ConversionRule(RuleContext ctx) {
        this.ctx = ctx;
        readDeclarations();
    }

    static Kind kind(String type) {
        if (type == null || type.isBlank()) {
            return Kind.OTHER;
        }
        String t = type.strip().toUpperCase(Locale.ROOT);
        int cut = 0;
        while (cut < t.length() && (Character.isLetterOrDigit(t.charAt(cut)) || t.charAt(cut) == '_')) {
            cut++;
        }
        String base = t.substring(0, cut);
        if (NSTRING_TYPES.contains(base)) {
            return Kind.NSTRING;
        }
        if (STRING_TYPES.contains(base) && !t.contains("FOR BIT DATA")) {
            return Kind.STRING;
        }
        if (NUMBER_TYPES.contains(base)) {
            return Kind.NUMBER;
        }
        if (DATE_TYPES.contains(base)) {
            return Kind.DATE;
        }
        return Kind.OTHER;
    }

    // ----- declarations ------------------------------------------------------------------------------

    private void readDeclarations() {
        for (Statement st : ctx.script.flatten()) {
            if (st.kind() == StatementKind.DECLARE || st.kind().category() == StatementKind.Category.PROGRAM) {
                Toks k = st.kind() == StatementKind.DECLARE ? ctx.toks(st) : ctx.declarations(st);
                for (int i = 0; i + 1 < k.size(); i++) {
                    declaration(k, i);
                }
            }
        }
    }

    /** {@code @v [AS] type}, or {@code v [IN|OUT|INOUT] type} right after DECLARE, IS / AS, '(' , ',' or ';'. */
    private void declaration(Toks k, int i) {
        if (!k.isName(i) || k.wordIn(i, Toks.KEYWORDS) || k.wordIn(i, Set.of("CURSOR", "TYPE", "BEGIN", "IS",
                "CONSTANT", "EXCEPTION", "PRAGMA", "PROCEDURE", "FUNCTION", "RETURN", "RETURNS"))) {
            return;
        }
        boolean sigil = k.tok(i).text().startsWith("@");
        boolean after = k.wordIn(i - 1, Set.of("DECLARE", "IS", "AS")) || k.sym(i - 1, '(') || k.sym(i - 1, ',')
                || k.sym(i - 1, ';');
        if (!sigil && !after) {
            return;
        }
        int t = i + 1;
        while (k.wordIn(t, Set.of("AS", "IN", "OUT", "INOUT", "NOCOPY", "CONSTANT"))) {
            t++;
        }
        if (!k.type(t, ScanToken.Type.WORD) || k.sym(t + 1, '.') || k.sym(t + 1, '%')) {
            return;
        }
        int next = k.sym(t + 1, '(') && k.close(t + 1) > 0 ? k.close(t + 1) + 1 : t + 1;
        while (k.wordIn(next, Set.of("VARYING", "PRECISION", "CHAR", "BYTE"))) {
            next++;
        }
        if (!(k.sym(next, ';') || k.sym(next, ',') || k.sym(next, ')') || k.sym(next, ':') || k.sym(next, '=')
                || k.wordIn(next, Set.of("DEFAULT", "NOT", "NULL", "IS", "AS", "BEGIN", "OUTPUT", "OUT", "READONLY"))
                || !k.in(next))) {
            return;
        }
        Kind kind = kind(k.tok(t).text() + (k.word(t + 1, "VARYING") ? " VARYING" : ""));
        String name = k.tok(i).upper();
        Kind old = variables.putIfAbsent(name, kind);
        if (old != null && old != kind) {
            variables.put(name, Kind.OTHER);
        }
    }

    // ----- comparisons -------------------------------------------------------------------------------

    void statement(Statement st, Toks k) {
        List<Scope> scopes = new ArrayList<>(Scope.blocks(k));
        Scope dml = Scope.dml(k);
        if (dml != null) {
            scopes.add(dml);
        }
        Set<Integer> seen = new HashSet<>();
        for (Scope s : scopes) {
            if (s.opaque || s.sources.isEmpty()) {
                continue;
            }
            for (int[] r : s.predicateRanges()) {
                scan(st, k, s, r[0], r[1], seen);
            }
        }
    }

    private void scan(Statement st, Toks k, Scope s, int from, int to, Set<Integer> seen) {
        int d = k.depth[from < k.size() ? from : 0];
        for (int i = from; i < to; i++) {
            if (k.depth[i] < d || !(i == from || k.wordIn(i - 1, BEFORE) || k.sym(i - 1, '('))) {
                continue;
            }
            int end = s.refEnd(i);
            if (end >= 0) {
                String op = k.comparison(end + 1);
                if (op != null) {
                    int v = end + 1 + op.length();
                    check(st, k, s, i, end, v, valueEnd(k, v), valueEnd(k, v), seen);
                } else if (k.word(end + 1, "IN") && k.sym(end + 2, '(') && !k.word(end + 3, "SELECT")) {
                    int close = k.close(end + 2);
                    for (int j = end + 3; close > 0 && j < close; j++) {
                        if (k.depth[j] == k.depth[end + 3] && (j == end + 3 || k.sym(j - 1, ','))) {
                            if (check(st, k, s, i, end, j, valueEnd(k, j), valueEnd(k, j), seen)) {
                                break;
                            }
                        }
                    }
                } else if (k.word(end + 1, "BETWEEN")) {
                    check(st, k, s, i, end, end + 2, valueEnd(k, end + 2), valueEnd(k, end + 2), seen);
                }
                continue;
            }
            // literal op column
            int lit = valueEnd(k, i);
            String op = lit > i ? k.comparison(lit) : null;
            if (op != null && Cmp.literal(k, i, lit)) {
                int c = lit + op.length();
                int ce = s.refEnd(c);
                if (ce >= 0 && k.comparison(ce + 1) == null) {
                    check(st, k, s, c, ce, i, lit, ce + 1, seen);
                }
            }
        }
    }

    /** Exclusive end of a literal or variable value starting at {@code v} (one token, a sign, N'…'). */
    private static int valueEnd(Toks k, int v) {
        int i = v;
        if (k.sym(i, '-') || k.sym(i, '+')) {
            i++;
        }
        if (k.word(i, "N") && k.type(i + 1, ScanToken.Type.STRING)) {
            i++;
        }
        if (k.sym(i, ':') && k.isName(i + 1)) {
            i++;
        }
        return k.in(i) ? i + 1 : v;
    }

    /**
     * Checks column [c, ce] against value [v, ve); {@code after} is the token after the predicate, which must
     * end it. True when it reported.
     */
    private boolean check(Statement st, Toks k, Scope s, int c, int ce, int v, int ve, int after, Set<Integer> seen) {
        if (ve <= v || seen.contains(c)) {
            return false;
        }
        int a = after;
        if (k.in(a) && !(k.wordIn(a, Set.of("AND", "OR", "THEN", "WHERE", "GROUP", "ORDER", "HAVING", "UNION",
                "ESCAPE")) || k.sym(a, ')') || k.sym(a, ',') || k.sym(a, ';') || k.depth[a] < k.depth[v]
                || k.wordIn(a, Scope.SET_OPERATORS) || k.wordIn(a, Set.of("INNER", "LEFT", "RIGHT", "FULL", "JOIN",
                "CROSS", "LIMIT", "FETCH", "RETURNING", "OPTION")))) {
            return false;
        }
        Scope.ColRef ref = s.column(c, ce, ctx);
        if (ref == null) {
            return false;
        }
        TableMeta m = ctx.meta(ref.src().name()).orElse(null);
        TableMeta.ColumnMeta col = m == null ? null : m.column(ref.column()).orElse(null);
        if (col == null) {
            return false;
        }
        Kind colKind = kind(col.type());
        if (colKind == Kind.OTHER) {
            return false;
        }
        String value = k.slice(v, ve - 1);
        Kind valueKind;
        String variable = null;
        int last = ve - 1;
        boolean national = k.word(v, "N") || (k.sym(v, '-') && k.word(v + 1, "N"));
        if (k.type(last, ScanToken.Type.NUMBER)) {
            valueKind = Kind.NUMBER;
        } else if (k.type(last, ScanToken.Type.STRING)) {
            valueKind = national ? Kind.NSTRING : Kind.STRING;
        } else if (k.isName(last) && ve - v == 1 && !k.sym(last + 1, '(')) {
            Kind declared = variables.get(k.tok(last).upper());
            boolean isVariable = k.tok(last).text().startsWith("@") || Cmp.variable(ctx, s, k.tok(last).text());
            if (declared == null || declared == Kind.OTHER || !isVariable) {
                return false;
            }
            valueKind = declared;
            variable = k.tok(last).text();
        } else {
            return false;
        }
        String column = k.slice(c, ce);
        String why = verdict(colKind, col.type(), valueKind, variable != null, value);
        if (why == null) {
            return false;
        }
        seen.add(c);
        Severity sev = why.startsWith("~") ? Severity.LOW : Severity.MEDIUM;
        String text = why.startsWith("~") ? why.substring(1) : why;
        ctx.add("AMB-P002", sev, Messages.t("rule.AMB-P002.title"), Messages.t("rules.P002.message", column,
                col.type().toUpperCase(Locale.ROOT), m.qualifiedName(), variable != null
                        ? Messages.t("rules.P002.variable", variable,
                                Messages.t("rules.P002.kind." + valueKind.name().toLowerCase(Locale.ROOT)))
                        : value, text), suggestion(colKind, valueKind, column, value),
                st, ctx.range(k, Math.min(c, v), Math.max(ce, ve - 1)), Confidence.CERTAIN,
                variable != null ? null : fixed(st, k, v, ve, colKind, col.type(), valueKind, value));
        return true;
    }

    /** The statement with the literal written in the column's type, when that is mechanical; else null. */
    private Finding.SuggestedSql fixed(Statement st, Toks k, int v, int ve,
                                                                         Kind col, String colType, Kind value,
                                                                         String literal) {
        String s = unquote(literal).strip();
        String text = null;
        String note = null;
        if ((col == Kind.STRING || col == Kind.NSTRING) && value == Kind.NUMBER) {
            text = "'" + literal.replace(" ", "") + "'";
            note = Messages.t("rules.P002.note.text", s);
        } else if (col == Kind.NUMBER && value == Kind.STRING && NUMERIC.matcher(s).matches()) {
            text = s;
            note = Messages.t("rules.P002.note.number");
        } else if (col == Kind.DATE && s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            if (ctx.dialect == Dialect.ORACLE) {
                text = "DATE '" + s + "'";
            } else if (ctx.dialect == Dialect.SQLSERVER) {
                text = "'" + s.replace("-", "") + "'";
            }
            note = Messages.t("rules.P002.note.date");
        } else if (col == Kind.STRING && value == Kind.NSTRING && s.chars().allMatch(ch -> ch < 128)) {
            text = literal.substring(literal.indexOf('\''));
            note = Messages.t("rules.P002.note.ascii");
        }
        if (text == null) {
            return null;
        }
        return SqlCheck.rewrite(ctx, ctx.text(st), ctx.replaced(st, k.tok(v).start(), k.tok(ve - 1).end(), text), note);
    }

    /** Why the comparison converts, or null when it does not matter; a leading '~' means LOW. */
    private String verdict(Kind col, String colType, Kind value, boolean variable, String literal) {
        Dialect d = ctx.dialect;
        if (d == Dialect.SQLITE && col != Kind.DATE) {
            return null;                                     // type affinity converts the value, not the column
        }
        if ((col == Kind.STRING || col == Kind.NSTRING) && value == Kind.NUMBER) {
            if (d == Dialect.POSTGRESQL) {
                return Messages.t("rules.P002.why.pgTextNumber");
            }
            return Messages.t("rules.P002.why.columnToNumber");
        }
        if (col == Kind.NUMBER && (value == Kind.STRING || value == Kind.NSTRING)) {
            if (d == Dialect.POSTGRESQL || variable) {
                return null;
            }
            String s = unquote(literal);
            if (NUMERIC.matcher(s).matches()) {
                return "~" + Messages.t("rules.P002.why.numericString");
            }
            return d == Dialect.MYSQL || d == Dialect.MARIADB
                    ? Messages.t("rules.P002.why.mysqlString")
                    : Messages.t("rules.P002.why.notANumber");
        }
        if (col == Kind.DATE && (value == Kind.STRING || value == Kind.NSTRING) && !variable) {
            String s = unquote(literal);
            if (d == Dialect.ORACLE) {
                return Messages.t("rules.P002.why.oracleDate");
            }
            if (d == Dialect.SQLSERVER) {
                String t = colType.toUpperCase(Locale.ROOT);
                boolean legacy = t.startsWith("DATETIME") && !t.startsWith("DATETIME2") || t.startsWith("SMALLDATETIME");
                if (MSSQL_SAFE.matcher(s).matches() || (!legacy && ISO_DATE.matcher(s).matches())) {
                    return null;
                }
                return Messages.t("rules.P002.why.sqlserverDate");
            }
            if (ISO_DATE.matcher(s).matches()) {
                return null;
            }
            return Messages.t("rules.P002.why.nonIsoDate");
        }
        if ((d == Dialect.SQLSERVER || d == Dialect.ORACLE) && col == Kind.STRING && value == Kind.NSTRING) {
            return Messages.t("rules.P002.why.national");
        }
        return null;
    }

    private String suggestion(Kind col, Kind value, String column, String literal) {
        if ((col == Kind.STRING || col == Kind.NSTRING) && value == Kind.NUMBER) {
            return Messages.t("rules.P002.suggestion.string", column, unquote(literal).strip());
        }
        if (col == Kind.NUMBER) {
            return Messages.t("rules.P002.suggestion.number", column, unquote(literal).strip());
        }
        if (col == Kind.DATE) {
            return switch (ctx.dialect) {
                case ORACLE -> Messages.t("rules.P002.suggestion.date.oracle");
                case SQLSERVER -> Messages.t("rules.P002.suggestion.date.sqlserver");
                default -> Messages.t("rules.P002.suggestion.date");
            };
        }
        return Messages.t("rules.P002.suggestion.national");
    }

    private static String unquote(String literal) {
        String s = literal;
        if (s.startsWith("N") || s.startsWith("n")) {
            s = s.substring(1).strip();
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s;
    }
}
