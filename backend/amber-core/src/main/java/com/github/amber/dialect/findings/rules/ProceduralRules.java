package com.github.amber.dialect.findings.rules;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.findings.Finding.Severity;
import com.github.amber.dialect.findings.Finding;
import com.github.amber.dialect.split.ScanToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rules over sequences of statements and loops:
 *
 * <ul>
 *   <li>AMB-P004 two consecutive UPDATEs of the same table with the same FROM and WHERE, where the second
 *       neither sets a column the first sets nor reads one (so one UPDATE gives the same result); and an
 *       UPDATE next to an INSERT … WHERE NOT EXISTS on the same table (an upsert in two passes).</li>
 *   <li>AMB-P005 a cursor loop whose body is one INSERT / UPDATE / DELETE / MERGE that uses the cursor row:
 *       {@code FOR r IN (SELECT …) LOOP} (Oracle), {@code FOR r IN SELECT … LOOP} (PL/pgSQL),
 *       {@code FOR r AS SELECT … DO} (Db2), {@code FOR r IN cursor LOOP}, and FETCH loops
 *       ({@code WHILE @@FETCH_STATUS = 0}, MySQL {@code LOOP FETCH …; IF done THEN LEAVE …}). With only
 *       one DML and no assignment in the body, nothing but the cursor row changes between iterations. The
 *       suggestion shows the set-based statement when it can be built from the texts.</li>
 *   <li>AMB-P008 COUNT used to test existence: {@code (SELECT COUNT(*) …) > 0} and
 *       {@code SELECT COUNT(*) INTO v …} followed by {@code IF v > 0} when {@code v} is not used again.</li>
 * </ul>
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class ProceduralRules {

    private static final Set<StatementKind> DML = Set.of(StatementKind.INSERT, StatementKind.UPDATE,
            StatementKind.DELETE, StatementKind.MERGE);
    private static final int MAX_SQL = 1200;

    private final RuleContext ctx;
    private final boolean strict;

    ProceduralRules(RuleContext ctx, boolean strict) {
        this.ctx = ctx;
        this.strict = strict;
    }

    void run() {
        siblings(ctx.script.statements());
        for (Statement st : ctx.script.flatten()) {
            if (!st.children().isEmpty()) {
                siblings(st.children());
            }
            if (st.kind() == StatementKind.LOOP) {
                safe(() -> cursorLoop(st));
            }
            if (RuleContext.readable(st)) {
                safe(() -> countSubquery(st, ctx.toks(st)));
            } else if (st.kind() == StatementKind.IF || st.kind() == StatementKind.LOOP) {
                safe(() -> countSubquery(st, ctx.header(st)));
            }
        }
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            if (strict) {
                throw e;
            }
        }
    }

    private void siblings(List<Statement> list) {
        for (int i = 0; i + 1 < list.size(); i++) {
            Statement a = list.get(i);
            Statement b = list.get(i + 1);
            if (a.kind() == StatementKind.UPDATE && b.kind() == StatementKind.UPDATE) {
                safe(() -> updates(a, b));
            } else if ((a.kind() == StatementKind.UPDATE && b.kind() == StatementKind.INSERT)
                    || (a.kind() == StatementKind.INSERT && b.kind() == StatementKind.UPDATE)) {
                safe(() -> upsert(a, b));
            } else if (a.kind() == StatementKind.SELECT && b.kind() == StatementKind.IF) {
                safe(() -> countThenIf(a, b));
            }
        }
    }

    // ----- AMB-P004 -----------------------------------------------------------------------------------

    /** The parts of {@code UPDATE target SET assignments [FROM …] [WHERE …]}. */
    private record Update(Toks k, String table, String target, String from, String where, Set<String> sets,
                          int setStart, int setEnd, Set<String> reads, boolean query) {
    }

    private static Update update(Toks k) {
        if (!k.word(0, "UPDATE")) {
            return null;
        }
        int set = -1;
        int setEnd = -1;
        int from = -1;
        int where = -1;
        int end = k.size();
        for (int i = 1; i < k.size(); i++) {
            if (k.depth[i] != 0) {
                continue;
            }
            if (k.wordIn(i, Set.of("RETURNING", "OUTPUT", "ORDER", "LIMIT", "OPTION"))) {
                return null;
            }
            if (k.sym(i, ';')) {
                end = Math.min(end, i);
            } else if (set < 0 && k.word(i, "SET")) {
                set = i;
            } else if (set >= 0 && from < 0 && where < 0 && k.word(i, "FROM")) {
                from = i;
            } else if (set >= 0 && where < 0 && k.word(i, "WHERE")) {
                where = i;
            }
        }
        if (set < 2 || k.word(where + 1, "CURRENT") || !k.isName(1)) {
            return null;
        }
        setEnd = from >= 0 ? from : where >= 0 ? where : end;
        Set<String> sets = new HashSet<>();
        Set<String> reads = new HashSet<>();
        int s = set + 1;
        for (int i = set + 1; i <= setEnd; i++) {
            if (i == setEnd || (k.depth[i] == 0 && k.sym(i, ','))) {
                int eq = -1;
                for (int j = s; j < i; j++) {
                    if (k.depth[j] == 0 && k.sym(j, '=')) {
                        eq = j;
                        break;
                    }
                }
                if (eq < 0 || !k.isName(eq - 1) || k.sym(s, '(')) {
                    return null;
                }
                sets.add(Toks.key(k.tok(eq - 1).text()));
                names(k, eq + 1, i, reads);
                s = i + 1;
            }
        }
        names(k, setEnd, end, reads);
        String table = k.norm(1, k.nameEnd(1) + 1);
        return new Update(k, table, k.norm(1, set), from < 0 ? "" : k.norm(from, where >= 0 ? where : end),
                where < 0 ? "" : k.norm(where, end), sets, set + 1, setEnd, reads, k.hasSelect(set, end));
    }

    private static void names(Toks k, int from, int to, Set<String> out) {
        for (int i = from; i < to; i++) {
            if (k.isName(i)) {
                out.add(Toks.key(k.tok(i).text()));
            }
        }
    }

    private void updates(Statement a, Statement b) {
        Update x = update(ctx.toks(a));
        Update y = update(ctx.toks(b));
        if (x == null || y == null || !x.target().equals(y.target()) || !x.from().equals(y.from())
                || !x.where().equals(y.where()) || y.query() || ctx.ignored(a, "AMB-P004")) {
            return;
        }
        for (String c : x.sets()) {
            if (y.sets().contains(c) || y.reads().contains(c)) {
                return;                                 // the second UPDATE depends on the first one's result
            }
        }
        Toks k = x.k();
        int insertAt = k.tok(x.setEnd() - 1).end();
        String combined = ctx.source.text().substring(a.range().start().offset(), insertAt) + ", "
                + y.k().slice(y.setStart(), y.setEnd() - 1) + ctx.source.text().substring(insertAt,
                a.range().end().offset());
        combined = combined.strip();
        ctx.add("AMB-P004", Severity.INFO, Messages.t("rule.AMB-P004.title"),
                Messages.t(x.where().isEmpty() ? "rules.P004.combine.message.noWhere" : "rules.P004.combine.message.where",
                        x.table(), a.range().start().line()),
                combined.length() <= MAX_SQL ? Messages.t("rules.P004.combine.suggestion.sql", combined)
                        : Messages.t("rules.P004.combine.suggestion"), b, b.range(), Confidence.CERTAIN,
                SqlCheck.rewrite(ctx, terminated(ctx.text(a)) + "\n" + terminated(ctx.text(b)), combined,
                        Messages.t("rules.P004.combine.note")));
    }

    private void upsert(Statement a, Statement b) {
        Statement upd = a.kind() == StatementKind.UPDATE ? a : b;
        Statement ins = upd == a ? b : a;
        Toks u = ctx.toks(upd);
        Toks n = ctx.toks(ins);
        Update x = update(u);
        if (x == null || !n.word(0, "INSERT") || !n.word(1, "INTO") || !n.isName(2) || ctx.ignored(a, "AMB-P004")) {
            return;
        }
        String target = n.norm(2, n.nameEnd(2) + 1);
        if (!sameTable(target, x.table())) {
            return;
        }
        boolean notExists = false;
        for (int i = 3; i + 2 < n.size() && !notExists; i++) {
            if (n.word(i, "NOT") && n.word(i + 1, "EXISTS") && n.sym(i + 2, '(')) {
                int close = n.close(i + 2);
                for (int j = i + 3; j < close; j++) {
                    if (n.word(j, "FROM") && n.isName(j + 1) && sameTable(n.norm(j + 1, n.nameEnd(j + 1) + 1), target)) {
                        notExists = true;
                        break;
                    }
                }
            }
        }
        if (!notExists || (upd == a && setsWhatTheInsertTests(x, n))) {
            return;
        }
        String how = switch (ctx.dialect) {
            case MYSQL, MARIADB -> Messages.t("rules.P004.upsert.how.mysql");
            case SQLITE -> Messages.t("rules.P004.upsert.how.sqlite");
            case POSTGRESQL -> Messages.t("rules.P004.upsert.how.postgresql");
            default -> Messages.t("rules.P004.upsert.how.merge");
        };
        ctx.add("AMB-P004", Severity.INFO, Messages.t("rule.AMB-P004.title"), Messages.t("rules.P004.upsert.message",
                b == ins ? "INSERT … WHERE NOT EXISTS" : "UPDATE", b == ins ? "UPDATE" : "INSERT",
                a.range().start().line(), x.table()), Messages.t("rules.P004.upsert.suggestion", how), b, b.range(),
                Confidence.INFERRED);
    }

    /**
     * The UPDATE (run first) sets a column the INSERT's NOT EXISTS reads, e.g. a slowly changing dimension
     * that closes the current row and then inserts a new version: not an upsert of one row per key.
     */
    private static boolean setsWhatTheInsertTests(Update u, Toks n) {
        for (int i = 0; i + 2 < n.size(); i++) {
            if (n.word(i, "NOT") && n.word(i + 1, "EXISTS") && n.sym(i + 2, '(')) {
                for (int j = i + 3; j < n.close(i + 2); j++) {
                    if (n.isName(j) && u.sets().contains(Toks.key(n.tok(j).text()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean sameTable(String a, String b) {
        ObjectName x = ObjectName.parse(ctx.dialect, a);
        ObjectName y = ObjectName.parse(ctx.dialect, b);
        if (x == null || y == null) {
            return false;
        }
        boolean qualified = x.parts().size() > 1 && y.parts().size() > 1;
        return qualified ? x.key().equals(y.key()) : x.simpleKey().equals(y.simpleKey());
    }

    private static String terminated(String sql) {
        String s = sql.strip();
        return s.endsWith(";") ? s : s + ";";
    }

    // ----- AMB-P005 -----------------------------------------------------------------------------------

    private void cursorLoop(Statement loop) {
        int start = loop.range().start().offset();
        int end = loop.range().end().offset();
        for (Statement c : loop.children()) {
            end = Math.max(end, c.range().end().offset());
        }
        Toks k = new Toks(ctx.dialect, ctx.source.text(), start, end);
        String record = null;
        String query = null;
        int headerEnd = -1;
        Map<String, String> fetchVars = null;
        if (k.word(0, "FOR") && k.isName(1) && (k.word(2, "IN") || k.word(2, "AS"))) {
            record = k.tok(1).text();
            int q = 3;
            if (k.word(2, "AS") && k.isName(3) && k.word(4, "CURSOR")) {
                q = k.word(5, "FOR") ? 6 : -1;          // Db2: FOR r AS c CURSOR FOR SELECT …
            }
            if (q < 0) {
                return;
            }
            if (k.sym(q, '(') && k.word(q + 1, "SELECT") && k.close(q) > 0) {
                query = k.slice(q + 1, k.close(q) - 1);
                headerEnd = k.close(q) + 1;
            } else if (k.word(q, "SELECT") || k.word(q, "WITH")) {
                headerEnd = q;
                while (k.in(headerEnd) && !(k.depth[headerEnd] == 0 && k.wordIn(headerEnd, Set.of("LOOP", "DO")))) {
                    headerEnd++;
                }
                if (!k.in(headerEnd)) {
                    return;
                }
                query = k.slice(q, headerEnd - 1);
            } else if (k.isName(q) && k.wordIn(q + 1, Set.of("LOOP", "DO"))) {
                query = cursorQuery(k.tok(q).text());
                headerEnd = q + 1;
            } else {
                return;                                 // numeric FOR, parameterized cursor, …
            }
            if (!k.wordIn(headerEnd, Set.of("LOOP", "DO"))) {
                return;
            }
        }
        List<Statement> body = new ArrayList<>();
        int bodyStart = headerEnd >= 0 ? k.tok(headerEnd).end() : start;
        for (Statement c : loop.children()) {
            if (c.range().start().offset() >= bodyStart) {
                body.add(c);
            }
        }
        if (body.size() == 1 && body.get(0).kind() == StatementKind.BLOCK) {
            body = body.get(0).children();
        }
        Statement dml = null;
        String fetchCursor = null;
        for (Statement s : body) {
            Toks t = ctx.toks(s);
            if (DML.contains(s.kind()) && dml == null) {
                dml = s;
            } else if (record == null && s.kind() == StatementKind.CURSOR && t.word(0, "FETCH") && fetchVars == null) {
                fetchCursor = fetch(t);
                fetchVars = fetchCursor == null ? null : fetchVariables(t);
                if (fetchVars == null) {
                    return;
                }
            } else if (record == null && s.kind() == StatementKind.IF && leaveOnly(s)) {
                continue;
            } else {
                return;
            }
        }
        if (dml == null || ctx.ignored(loop, "AMB-P005")) {
            return;
        }
        Toks d = ctx.toks(dml);
        for (int i = 0; i < d.size(); i++) {
            if (d.wordIn(i, Set.of("RETURNING", "INTO", "OUTPUT", "BULK")) && !(i == 1 && d.word(0, "INSERT"))
                    && !(d.word(0, "MERGE") && i == 1)) {
                return;
            }
        }
        Map<String, String> names = null;
        if (record == null) {
            if (fetchVars == null) {
                return;
            }
            query = cursorQuery(fetchCursor);
            names = query == null ? null : columnNames(query, fetchVars);
            if (!usesAny(d, fetchVars.keySet())) {
                return;
            }
            headerEnd = -1;
        } else if (!usesRecord(d, record)) {
            return;
        }
        String alias = record != null ? record : fresh(d, query);
        String sql = query == null || (record == null && names == null) ? null
                : setBased(dml, d, query, alias, names);
        int headerLast = headerEnd >= 0 ? headerEnd : Math.min(k.size() - 1, firstChildToken(k, loop));
        String what = dml.kind().name().toLowerCase(Locale.ROOT);
        String message = switch (dml.kind()) {
            case INSERT, UPDATE, DELETE, MERGE -> Messages.t("rules.P005.message." + what);
            default -> Messages.t("rules.P005.message.other", what);
        };
        ctx.add("AMB-P005", Severity.MEDIUM, Messages.t("rule.AMB-P005.title"), message,
                sql != null && sql.length() <= MAX_SQL ? Messages.t("rules.P005.suggestion.sql", sql)
                        : Messages.t("rules.P005.suggestion"), loop,
                ctx.range(k, 0, Math.max(0, headerLast)), Confidence.INFERRED,
                sql == null ? null : SqlCheck.rewrite(ctx, k.slice(0, k.size() - 1), sql,
                        Messages.t("rules.P005.note")));
    }

    private int firstChildToken(Toks k, Statement loop) {
        int first = loop.children().isEmpty() ? Integer.MAX_VALUE : loop.children().get(0).range().start().offset();
        int i = 0;
        while (i + 1 < k.size() && k.tok(i + 1).start() < first) {
            i++;
        }
        return i;
    }

    /** MySQL: {@code IF done THEN LEAVE label; END IF}. */
    private boolean leaveOnly(Statement s) {
        Toks t = ctx.toks(s);
        boolean leave = false;
        for (int i = 0; i < t.size(); i++) {
            leave |= t.wordIn(i, Set.of("LEAVE", "EXIT", "BREAK"));
        }
        for (Statement c : s.children()) {
            if (c.kind() != StatementKind.OTHER) {
                return false;
            }
        }
        return leave;
    }

    /** {@code FETCH [NEXT|PRIOR…] [FROM] c INTO …}: the cursor name, or null. */
    private static String fetch(Toks t) {
        int i = 1;
        while (t.wordIn(i, Set.of("NEXT", "PRIOR", "FIRST", "LAST", "FROM", "IN"))) {
            i++;
        }
        return t.isName(i) && t.word(i + 1, "INTO") ? Toks.key(t.tok(i).text()) : null;
    }

    /** Upper-case variable → position (as a string index) of {@code FETCH … INTO v1, v2}; null when unusual. */
    private static Map<String, String> fetchVariables(Toks t) {
        int into = -1;
        for (int i = 0; i < t.size(); i++) {
            if (t.word(i, "INTO")) {
                into = i;
                break;
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = into + 1; into > 0 && i < t.size(); i++) {
            if (t.isName(i)) {
                out.put(t.tok(i).upper(), String.valueOf(out.size()));
            } else if (!t.sym(i, ',') && !t.sym(i, ';')) {
                return null;
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** The SELECT of {@code CURSOR c IS SELECT …} / {@code DECLARE c CURSOR … FOR SELECT …} / {@code c CURSOR FOR …}. */
    private String cursorQuery(String name) {
        String key = Toks.key(name);
        for (Statement st : ctx.script.flatten()) {
            if (st.kind() != StatementKind.CURSOR) {
                continue;
            }
            Toks t = ctx.toks(st);
            int n = t.word(0, "CURSOR") ? 1 : t.word(0, "DECLARE") ? 1 : 0;
            if (!t.isName(n) || !Toks.key(t.tok(n).text()).equals(key)) {
                continue;
            }
            for (int i = n + 1; i < t.size(); i++) {
                if (t.sym(i, '(') && i == n + 1) {
                    return null;                        // parameterized cursor
                }
                if (t.word(i, "SELECT") || (t.word(i, "WITH") && t.wordIn(i - 1, Set.of("IS", "FOR")))) {
                    int e = t.size();
                    for (int j = i; j < t.size(); j++) {
                        if (t.depth[j] == 0 && (t.sym(j, ';') || (t.word(j, "FOR") && t.wordIn(j + 1,
                                Set.of("UPDATE", "READ"))))) {
                            if (t.word(j, "FOR")) {
                                return null;
                            }
                            e = j;
                            break;
                        }
                    }
                    return e > i ? t.slice(i, e - 1) : null;
                }
            }
        }
        return null;
    }

    /** Variable → output column name of the cursor query, by position; null when an item has no plain name. */
    private Map<String, String> columnNames(String query, Map<String, String> vars) {
        Toks q = new Toks(ctx.dialect, query, 0, query.length());
        if (!q.word(0, "SELECT")) {
            return null;
        }
        Scope s = Scope.select(q, 0);
        List<int[]> items = s.items();
        if (items.size() != vars.size()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> v : vars.entrySet()) {
            int[] item = items.get(Integer.parseInt(v.getValue()));
            int last = item[1] - 1;
            if (!q.isPlainName(last) || (last > item[0] && !q.sym(last - 1, '.') && !q.word(last - 1, "AS")
                    && !q.isName(last - 1) && !q.sym(last - 1, ')'))) {
                return null;
            }
            out.put(v.getKey(), Toks.unquote(q.tok(last).text()));
        }
        return out;
    }

    private static boolean usesRecord(Toks d, String record) {
        String key = Toks.key(record);
        for (int i = 0; i + 1 < d.size(); i++) {
            if (d.isName(i) && d.sym(i + 1, '.') && Toks.key(d.tok(i).text()).equals(key) && !d.sym(i - 1, '.')) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesAny(Toks d, Set<String> vars) {
        for (int i = 0; i < d.size(); i++) {
            if (d.isName(i) && vars.contains(d.tok(i).upper())) {
                return true;
            }
        }
        return false;
    }

    private static String fresh(Toks d, String query) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < d.size(); i++) {
            if (d.isName(i)) {
                used.add(Toks.key(d.tok(i).text()));
            }
        }
        String q = query == null ? "" : query.toUpperCase(Locale.ROOT);
        for (String a : List.of("src", "cur", "src_row")) {
            if (!used.contains(a.toUpperCase(Locale.ROOT)) && !q.contains(a.toUpperCase(Locale.ROOT))) {
                return a;
            }
        }
        return null;
    }

    /**
     * The set-based form of the loop's DML, or null when the shape is not a simple one. FETCH variables are
     * replaced by {@code alias.column} first.
     */
    private String setBased(Statement dml, Toks d, String query, String alias, Map<String, String> vars) {
        if (alias == null) {
            return null;
        }
        String text = dmlText(d, alias, vars);
        Toks t = new Toks(ctx.dialect, text, 0, text.length());
        Dialect dialect = ctx.dialect;
        String q = "(" + query.strip() + ") " + alias;
        if (dml.kind() == StatementKind.UPDATE) {
            Scope s = Scope.dml(t);
            int set = -1;
            for (int i = 1; i < t.size(); i++) {
                if (t.depth[i] == 0 && t.word(i, "SET")) {
                    set = i;
                    break;
                }
            }
            if (s == null || s.where < 0 || set < 0 || s.whereEnd <= s.where + 1) {
                return null;
            }
            String target = t.slice(1, set - 1);
            String assignments = t.slice(set + 1, s.where - 1);
            String pred = t.slice(s.where + 1, s.whereEnd - 1);
            switch (dialect) {
                case ORACLE, DB2, H2: {
                    Set<String> predNames = new HashSet<>();
                    names(t, s.where + 1, s.whereEnd, predNames);
                    Update u = update(t);
                    if (u == null || u.sets().stream().anyMatch(predNames::contains)) {
                        return null;                    // MERGE cannot update the columns of its ON condition
                    }
                    return "MERGE INTO " + target + " USING " + q + " ON (" + pred + ") WHEN MATCHED THEN UPDATE SET "
                            + assignments;
                }
                case SQLSERVER: {
                    String updated = set - 1 > s.sources.get(0).nameEnd() ? t.tok(set - 1).text() : target;
                    return "UPDATE " + updated + " SET " + assignments + " FROM " + target + " JOIN " + q + " ON " + pred;
                }
                case MYSQL, MARIADB:
                    return "UPDATE " + target + " JOIN " + q + " ON " + pred + " SET " + assignments;
                default:
                    return "UPDATE " + target + " SET " + assignments + " FROM " + q + " WHERE " + pred;
            }
        }
        if (dml.kind() == StatementKind.INSERT) {
            int values = -1;
            for (int i = 0; i < t.size(); i++) {
                if (t.depth[i] == 0 && t.word(i, "VALUES")) {
                    values = i;
                    break;
                }
            }
            int close = t.close(values + 1);
            if (values < 0 || !t.sym(values + 1, '(') || close < 0 || close + 1 < t.size() && !t.sym(close + 1, ';')) {
                return null;                            // one VALUES row only
            }
            return t.slice(0, values - 1) + " SELECT " + t.slice(values + 2, close - 1) + " FROM " + q;
        }
        if (dml.kind() == StatementKind.DELETE && dialect != Dialect.MYSQL && dialect != Dialect.MARIADB) {
            Scope s = Scope.dml(t);
            if (s == null || s.where < 0) {
                return null;
            }
            for (int i = s.where + 1; i < s.whereEnd; i++) {
                if (t.isPlainName(i) && !t.sym(i - 1, '.') && !t.sym(i + 1, '.')) {
                    return null;                        // an unqualified column could bind to the cursor query
                }
            }
            return t.slice(0, s.where - 1) + " WHERE EXISTS (SELECT 1 FROM " + q + " WHERE "
                    + t.slice(s.where + 1, s.whereEnd - 1) + ")";
        }
        return null;
    }

    private static String dmlText(Toks d, String alias, Map<String, String> vars) {
        int end = d.size();
        while (end > 0 && d.sym(end - 1, ';')) {
            end--;
        }
        StringBuilder sb = new StringBuilder();
        int pos = d.tok(0).start();
        for (int i = 0; i < end; i++) {
            ScanToken tok = d.tok(i);
            if (vars != null && d.isName(i) && vars.containsKey(tok.upper())) {
                sb.append(d.text, pos, tok.start()).append(alias).append('.').append(vars.get(tok.upper()));
                pos = tok.end();
            }
        }
        sb.append(d.text, pos, d.tok(end - 1).end());
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    // ----- AMB-P008 -----------------------------------------------------------------------------------

    private void countSubquery(Statement st, Toks k) {
        for (int i = 0; i + 6 < k.size(); i++) {
            if (!k.sym(i, '(') || !k.word(i + 1, "SELECT") || !countCall(k, i + 2)) {
                continue;
            }
            int close = k.close(i);
            if (close < 0) {
                continue;
            }
            Scope inner = Scope.select(k, i + 1);
            if (inner.end != close || inner.groupBy >= 0 || inner.having >= 0 || inner.from < 0
                    || inner.listEnd != inner.from || inner.from != inner.listStart + 4) {
                continue;
            }
            String test = existenceTest(k, close + 1);
            if (test == null) {
                continue;
            }
            int last = close + 1 + test.indexOf(' ');
            String exists = test.startsWith("=") ? "NOT EXISTS" : "EXISTS";
            Finding.SuggestedSql sql = null;
            if (RuleContext.readable(st) && (k.wordIn(i - 1, Set.of("WHERE", "AND", "OR", "ON", "HAVING", "WHEN"))
                    || k.sym(i - 1, '(')) && (!k.in(last + 1) || !k.type(last + 1, ScanToken.Type.WORD)
                    || k.wordIn(last + 1, Set.of("AND", "OR", "THEN", "GROUP", "ORDER", "UNION")))) {
                String replacement = exists + " (SELECT 1 " + k.slice(inner.from, close - 1) + ")";
                sql = SqlCheck.rewrite(ctx, ctx.text(st), ctx.replaced(st, k.tok(i).start(), k.tok(last).end(),
                        replacement), Messages.t("rules.P008.note"));
            }
            report(st, ctx.range(k, i, Math.min(k.size() - 1, last)), exists, sql);
        }
    }

    private static boolean countCall(Toks k, int i) {
        return k.word(i, "COUNT") && k.sym(i + 1, '(') && (k.sym(i + 2, '*') || (k.type(i + 2, ScanToken.Type.NUMBER)
                && k.tok(i + 2).text().equals("1"))) && k.sym(i + 3, ')');
    }

    /**
     * {@code > 0}, {@code >= 1}, {@code <> 0}, {@code != 0} or {@code = 0} at {@code i}, followed by the end of
     * the condition: the operator and value as written with a space ("> 0"), or null.
     */
    private static String existenceTest(Toks k, int i) {
        String op = k.comparison(i);
        if (op == null) {
            return null;
        }
        int v = i + op.length();
        if (!k.type(v, ScanToken.Type.NUMBER)) {
            return null;
        }
        String n = k.tok(v).text();
        boolean ok = (n.equals("0") && (op.equals(">") || op.equals("<>") || op.equals("!=") || op.equals("=")))
                || (n.equals("1") && op.equals(">="));
        if (!ok || (k.in(v + 1) && !(k.sym(v + 1, ')') || k.sym(v + 1, ';') || k.wordIn(v + 1, Set.of("THEN", "AND",
                "OR", "BEGIN", "LOOP", "DO", "ELSE", "END")) || k.type(v + 1, ScanToken.Type.WORD)))) {
            return null;
        }
        return op + " " + n;
    }

    private void report(Statement st, TextRange range, String exists,
                        Finding.SuggestedSql sql) {
        String hint = switch (ctx.dialect) {
            case ORACLE -> Messages.t("rules.P008.hint.oracle");
            case SQLSERVER -> Messages.t("rules.P008.hint.sqlserver");
            default -> "";
        };
        ctx.add("AMB-P008", Severity.LOW, Messages.t("rule.AMB-P008.title"), Messages.t("rules.P008.message"),
                Messages.t("rules.P008.suggestion", exists, hint), st, range, Confidence.INFERRED, sql);
    }

    /** {@code SELECT COUNT(*) INTO v …} / {@code SELECT @v = COUNT(*) …} then {@code IF v > 0}. */
    private void countThenIf(Statement sel, Statement iff) {
        Toks k = ctx.toks(sel);
        String var;
        int count;
        if (k.word(0, "SELECT") && countCall(k, 1) && k.word(5, "INTO") && k.isName(6) && k.word(7, "FROM")) {
            var = k.tok(6).upper();
            count = 1;
        } else if (k.word(0, "SELECT") && k.isName(1) && k.tok(1).text().startsWith("@") && k.sym(2, '=')
                && countCall(k, 3) && k.word(7, "FROM")) {
            var = k.tok(1).upper();
            count = 3;
        } else {
            return;
        }
        Scope s = Scope.select(k, 0);
        if (s.groupBy >= 0 || s.having >= 0 || s.opaque) {
            return;
        }
        Toks h = ctx.header(iff);
        int i = 1;
        int open = 0;
        while (h.sym(i, '(')) {
            i++;
            open++;
        }
        if (!h.word(0, "IF") || !h.isName(i) || !h.tok(i).upper().equals(var)) {
            return;
        }
        String test = existenceTest(h, i + 1);
        if (test == null) {
            return;
        }
        int after = i + 2 + test.indexOf(' ');
        for (int p = 0; p < open; p++) {
            if (!h.sym(after, ')')) {
                return;
            }
            after++;
        }
        if (h.in(after) && !h.wordIn(after, Set.of("THEN", "BEGIN"))) {
            return;
        }
        if (usedLater(iff, var, h.in(i + 1) ? h.tok(i).end() : iff.range().start().offset())) {
            return;
        }
        report(sel, ctx.range(k, count, count + 3), test.startsWith("=") ? "NOT EXISTS" : "EXISTS", null);
    }

    /** Whether {@code var} appears after {@code offset} in the rest of the enclosing statement or script. */
    private boolean usedLater(Statement iff, String var, int offset) {
        int end = ctx.source.length();
        for (Statement st : ctx.script.flatten()) {
            if (st.children().contains(iff)) {
                end = st.range().end().offset();
                for (Statement c : st.children()) {
                    end = Math.max(end, c.range().end().offset());
                }
            }
        }
        Toks rest = new Toks(ctx.dialect, ctx.source.text(), offset, end);
        for (int i = 0; i < rest.size(); i++) {
            if (rest.isName(i) && rest.tok(i).upper().equals(var)) {
                return true;
            }
        }
        return false;
    }
}
