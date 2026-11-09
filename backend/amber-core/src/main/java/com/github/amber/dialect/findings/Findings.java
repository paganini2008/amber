package com.github.amber.dialect.findings;

import com.github.amber.core.Dialect;
import com.github.amber.core.config.Env;
import com.github.amber.core.i18n.Messages;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.IndirectRole;
import com.github.amber.core.meta.TableMeta;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.findings.Finding.Severity;
import com.github.amber.dialect.findings.rules.ExtraRules;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static rules over the IR, the lineage and the statement tokens. Each rule has an id, a severity, an
 * explanation and a suggestion; findings inside a statement that carries {@code -- amber:ignore ID}
 * (or has it on the line just above) are dropped.
 *
 * <table>
 *   <tr><th>Rule</th><th>What</th><th>Severity</th></tr>
 *   <tr><td>AMB-S001</td><td>Cartesian product: several tables in FROM, no join condition</td><td>HIGH</td></tr>
 *   <tr><td>AMB-S002</td><td>LEFT JOIN turned into INNER JOIN by a WHERE condition on the right table</td><td>MEDIUM</td></tr>
 *   <tr><td>AMB-S003</td><td>Leading wildcard {@code LIKE '%x'}</td><td>MEDIUM</td></tr>
 *   <tr><td>AMB-S004</td><td>{@code NOT IN (subquery)}: one NULL makes it match nothing</td><td>MEDIUM</td></tr>
 *   <tr><td>AMB-S005</td><td>Same table read several times in one statement</td><td>MEDIUM</td></tr>
 *   <tr><td>AMB-S006</td><td>Row-by-row DML inside a loop</td><td>MEDIUM</td></tr>
 *   <tr><td>AMB-S007</td><td>COMMIT inside a loop</td><td>MEDIUM</td></tr>
 *   <tr><td>AMB-S008</td><td>Function or calculation on a column in WHERE / JOIN (index may not be used)</td><td>MEDIUM</td></tr>
 *   <tr><td>AMB-S009</td><td>{@code UNION} where {@code UNION ALL} may be enough</td><td>LOW</td></tr>
 *   <tr><td>AMB-S010</td><td>{@code SELECT *} written into a table or view</td><td>LOW</td></tr>
 *   <tr><td>AMB-S011</td><td>{@code DISTINCT} together with {@code GROUP BY}</td><td>LOW</td></tr>
 *   <tr><td>AMB-S012</td><td>{@code ORDER BY} in a subquery or CTE without a row limit</td><td>LOW</td></tr>
 *   <tr><td>AMB-M001</td><td>Many-to-many join: join columns unique on neither side (metadata)</td><td>HIGH</td></tr>
 *   <tr><td>AMB-D001</td><td>Duplicate statement</td><td>LOW</td></tr>
 *   <tr><td>AMB-D002</td><td>Subquery repeated across the script</td><td>LOW</td></tr>
 *   <tr><td>AMB-I001</td><td>Dynamic SQL is not analyzed</td><td>INFO</td></tr>
 *   <tr><td>AMB-I002</td><td>Columns that could not be attributed to a table</td><td>INFO</td></tr>
 *   <tr><td>AMB-I003</td><td>Checks stopped early: the time budget ran out</td><td>INFO</td></tr>
 * </table>
 *
 * <p>The performance and maintenance hints AMB-P001 … AMB-P009 live in
 * {@link com.github.amber.dialect.findings.rules.ExtraRules}; {@link RuleCatalog} lists every rule.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Findings {

    private static final Pattern IGNORE = Pattern.compile("(?i)amber:ignore\\s+([A-Z]+-[A-Z]\\d{3}(?:\\s*,\\s*[A-Z]+-[A-Z]\\d{3})*)");
    private static final Set<String> CLAUSE_END = Set.of("GROUP", "ORDER", "HAVING", "UNION", "EXCEPT", "INTERSECT",
            "MINUS", "LIMIT", "FETCH", "WINDOW", "QUALIFY", "FOR", "RETURNING", "OFFSET", "CONNECT", "START", "MODEL");
    private static final Set<String> JOIN_WORDS = Set.of("JOIN", "ON", "USING", "CROSS", "APPLY", "NATURAL", "LATERAL");
    private static final Set<String> NOT_FUNCTIONS = Set.of("IN", "EXISTS", "AND", "OR", "NOT", "SELECT", "VALUES",
            "ANY", "ALL", "SOME", "WHERE", "ON", "WHEN", "THEN", "ELSE", "CASE", "BETWEEN", "LIKE", "IS", "AS");
    /** Words inside a function's arguments that are not columns. */
    private static final Set<String> NOT_COLUMNS = Set.of("SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "LOCALTIMESTAMP", "CURRENT_TIME", "NULL", "AS", "DATE", "TIMESTAMP", "INTERVAL", "DAY", "MONTH", "YEAR",
            "HOUR", "MINUTE", "SECOND", "INT", "INTEGER", "VARCHAR", "VARCHAR2", "CHAR", "NUMBER", "DECIMAL", "NUMERIC",
            "FROM", "FOR", "BOTH", "LEADING", "TRAILING", "TRUE", "FALSE", "USING", "IN", "AND", "OR", "NOT");
    private static final Set<StatementKind> WRITES_STAR = Set.of(StatementKind.INSERT, StatementKind.CREATE_TABLE_AS,
            StatementKind.CREATE_VIEW, StatementKind.CREATE_MATERIALIZED_VIEW, StatementKind.SELECT_INTO);

    private final Dialect dialect;
    private final SourceText source;
    private final Catalog catalog;
    private final List<Finding> out = new ArrayList<>();

    /**
     * Time budget for all checks of one script ({@code AMBER_FINDINGS_SECONDS}, default 60). A guard, not a
     * tuning knob: when it runs out the remaining statements are skipped and AMB-I003 says from where.
     */
    static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(Math.max(1,
            Env.get().integer("AMBER_FINDINGS_SECONDS", 60)));
    private long budgetNanos = BUDGET_NANOS;

    private Findings(Dialect dialect, SourceText source, Catalog catalog) {
        this.dialect = dialect;
        this.source = source;
        this.catalog = catalog;
    }

    public static List<Finding> of(Script script) {
        return of(script, new Catalog());
    }

    /** With a catalog, metadata rules run and static rules backed by metadata become certain. */
    public static List<Finding> of(Script script, Catalog catalog) {
        return of(script, catalog, BUDGET_NANOS);
    }

    /** With an explicit time budget in nanoseconds; for tests. */
    static List<Finding> of(Script script, Catalog catalog, long budgetNanos) {
        Findings f = new Findings(script.dialect(), script.source(), catalog);
        f.budgetNanos = budgetNanos;
        f.run(script);
        return f.out;
    }

    /** As {@link #of(Script, com.github.amber.core.lineage.Catalog)} with the user's rule settings applied. */
    public static List<Finding> of(Script script, Catalog catalog, RuleConfig config) {
        List<Finding> all = of(script, catalog);
        return config == null ? all : config.apply(all);
    }

    private void run(Script script) {
        Map<String, Statement> fingerprints = new HashMap<>();
        Map<String, List<TextRange>> subqueries = new LinkedHashMap<>();
        Map<String, Statement> subqueryOwner = new HashMap<>();
        long started = System.nanoTime();
        for (Statement st : script.flatten()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("findings cancelled");
            }
            if (System.nanoTime() - started > budgetNanos) {
                add("AMB-I003", Severity.INFO, t("findings.I003.message"), st, st.range(), Confidence.CERTAIN);
                suppress();
                return;
            }
            if (st.kind() == StatementKind.CLIENT_COMMAND || st.kind() == StatementKind.DATA) {
                continue;
            }
            if (st.kind() == StatementKind.LOOP) {
                loopRules(st);
            }
            if (st.kind() == StatementKind.DYNAMIC_SQL) {
                add("AMB-I001", Severity.INFO, t("findings.I001.message"), st, st.range(), Confidence.CERTAIN);
            }
            if (st.kind().isContainer()) {
                continue;
            }
            List<ScanToken> tokens = tokens(st.range());
            if (tokens.isEmpty()) {
                continue;
            }
            tokenRules(st, tokens);
            repeatedScans(st);
            unresolvedColumns(st);
            fanOut(st);
            if (st.kind().category() == StatementKind.Category.QUERY || st.kind().category() == StatementKind.Category.DML
                    || st.kind() == StatementKind.CREATE_TABLE_AS || st.kind() == StatementKind.CREATE_VIEW) {
                String fp = fingerprint(tokens, 0, tokens.size());
                Statement earlier = fingerprints.putIfAbsent(fp, st);
                if (earlier != null && tokens.size() > 4) {
                    add("AMB-D001", Severity.LOW, t("findings.D001.message", earlier.range().start().line()), st, st.range(), Confidence.CERTAIN);
                }
                collectSubqueries(st, tokens, subqueries, subqueryOwner);
            }
        }
        subqueries.forEach((fp, ranges) -> {
            if (ranges.size() >= 2) {
                Statement st = subqueryOwner.get(fp);
                add("AMB-D002", Severity.LOW, t("findings.D002.message", ranges.size()), st, ranges.get(0), Confidence.CERTAIN);
            }
        });
        out.addAll(ExtraRules.of(script, catalog));   // AMB-P001 … P009
        suppress();
    }

    // ----- token rules ------------------------------------------------------------------------------

    private void tokenRules(Statement st, List<ScanToken> t) {
        for (int i = 0; i < t.size(); i++) {
            ScanToken tok = t.get(i);
            // AMB-S003 leading wildcard
            if (tok.isWord("LIKE") && i + 1 < t.size() && t.get(i + 1).type() == ScanToken.Type.STRING
                    && unquote(t.get(i + 1).text()).startsWith("%")) {
                add("AMB-S003", Severity.MEDIUM, t("findings.S003.message", t.get(i + 1).text()), st, range(tok, t.get(i + 1)), Confidence.CERTAIN);
            }
            // AMB-S004 NOT IN (SELECT
            if (tok.isWord("NOT") && i + 3 < t.size() && t.get(i + 1).isWord("IN") && t.get(i + 2).isSymbol('(')
                    && t.get(i + 3).isWord("SELECT")) {
                // With metadata: a NOT NULL subquery column makes NOT IN safe; a nullable one makes the risk real.
                Boolean nullable = subqueryColumnNullable(t, i + 4);
                if (!Boolean.FALSE.equals(nullable)) {
                    add("AMB-S004", Severity.MEDIUM, t(nullable == null ? "findings.S004.message.unknown"
                            : "findings.S004.message.nullable"), st,
                            range(tok, t.get(i + 3)), nullable == null ? Confidence.INFERRED : Confidence.CERTAIN);
                }
            }
            // AMB-S009 UNION without ALL
            if (tok.isWord("UNION") && (i + 1 >= t.size() || !t.get(i + 1).isWord("ALL"))) {
                add("AMB-S009", Severity.LOW, t("findings.S009.message"), st, range(tok, tok), Confidence.INFERRED);
            }
        }
        if (WRITES_STAR.contains(st.kind())) {
            for (int i = 0; i + 1 < t.size(); i++) {
                if (t.get(i).isWord("SELECT") && (t.get(i + 1).isSymbol('*')
                        || (i + 2 < t.size() && (t.get(i + 1).isWord("DISTINCT") || t.get(i + 1).isWord("ALL"))
                        && t.get(i + 2).isSymbol('*')))) {
                    add("AMB-S010", Severity.LOW, t("findings.S010.message"), st,
                            range(t.get(i), t.get(i + 1)), Confidence.CERTAIN);
                    break;
                }
            }
        }
        blockRules(st, t);
    }

    private static final Set<String> ROW_LIMITS = Set.of("ROWNUM", "LIMIT", "FETCH", "TOP", "OFFSET", "QUALIFY");

    /**
     * Whether the query enclosing a subquery limits rows ({@code WHERE ROWNUM <= n}, {@code SELECT TOP n},
     * {@code LIMIT}, {@code FETCH FIRST}): then the subquery's ORDER BY decides which rows come back (top-N).
     */
    private static boolean outerLimited(List<ScanToken> t, int[] depth, int start, int end, int d) {
        int outer = d - 1;
        for (int j = end; j < t.size() && depth[j] >= outer; j++) {
            if (depth[j] == outer && t.get(j).isWordIn(ROW_LIMITS)) {
                return true;
            }
        }
        for (int j = start - 1; j >= 0 && depth[j] >= outer; j--) {
            if (depth[j] == outer && t.get(j).isWordIn(ROW_LIMITS)) {
                return true;
            }
        }
        return false;
    }

    /** Rules that need the SELECT blocks of the statement: S001, S002, S008, S011, S012. */
    private void blockRules(Statement st, List<ScanToken> t) {
        int[] depth = depths(t);
        for (int i = 0; i < t.size(); i++) {
            if (!t.get(i).isWord("SELECT")) {
                continue;
            }
            int d = depth[i];
            int end = i + 1;
            // A ')' closing the enclosing subquery has a lower depth, so the depth test alone ends the block.
            while (end < t.size() && depth[end] >= d) {
                if (depth[end] == d && end > i && t.get(end).isWord("SELECT")
                        && (t.get(end - 1).isWord("UNION") || t.get(end - 1).isWord("ALL") || t.get(end - 1).isWord("EXCEPT")
                        || t.get(end - 1).isWord("INTERSECT") || t.get(end - 1).isWord("MINUS"))) {
                    break;
                }
                end++;
            }
            block(st, t, depth, i, end);
        }
    }

    private void block(Statement st, List<ScanToken> t, int[] depth, int start, int end) {
        int d = depth[start];
        int from = -1;
        int where = -1;
        int whereEnd = end;
        boolean distinct = start + 1 < end && t.get(start + 1).isWord("DISTINCT");
        boolean groupBy = false;
        int orderBy = -1;
        boolean limited = false;
        for (int i = start + 1; i < end; i++) {
            if (depth[i] != d) {
                continue;
            }
            ScanToken tok = t.get(i);
            if (tok.isWord("FROM") && from < 0) {
                from = i;
            } else if (tok.isWord("WHERE") && where < 0) {
                where = i;
            } else if (where >= 0 && whereEnd == end && tok.isWordIn(CLAUSE_END)) {
                whereEnd = i;
            }
            if (tok.isWord("GROUP")) {
                groupBy = true;
            }
            if (tok.isWord("ORDER") && i + 1 < end && t.get(i + 1).isWord("BY")) {
                orderBy = i;
            }
            if (tok.isWordIn(Set.of("LIMIT", "FETCH", "TOP", "OFFSET", "ROWNUM"))) {
                limited = true;
            }
        }
        if (distinct && groupBy) {
            add("AMB-S011", Severity.LOW, t("findings.S011.message"), st,
                    range(t.get(start), t.get(start + 1)), Confidence.INFERRED);
        }
        if (orderBy >= 0 && !limited && d > 0 && !outerLimited(t, depth, start, end, d)) {
            add("AMB-S012", Severity.LOW, t("findings.S012.message"), st,
                    range(t.get(orderBy), t.get(orderBy + 1)), Confidence.INFERRED);
        }
        if (from < 0) {
            return;
        }
        int fromEnd = where >= 0 ? where : whereEnd;
        for (int i = from + 1; i < end; i++) {
            if (depth[i] == d && t.get(i).isWordIn(CLAUSE_END)) {
                fromEnd = Math.min(fromEnd, i);
                break;
            }
        }
        // AMB-S001: comma-joined tables with no WHERE
        int commas = 0;
        boolean hasJoin = false;
        for (int i = from + 1; i < fromEnd; i++) {
            if (depth[i] == d && t.get(i).isSymbol(',')) {
                commas++;
            }
            if (t.get(i).isWordIn(JOIN_WORDS)) {
                hasJoin = true;
            }
        }
        if (commas > 0 && where < 0 && !hasJoin) {
            add("AMB-S001", Severity.HIGH, t("findings.S001.message", commas + 1), st,
                    range(t.get(from), t.get(fromEnd - 1)), Confidence.CERTAIN);
        }
        // AMB-S002: LEFT JOIN alias filtered in WHERE
        if (where >= 0) {
            Set<String> outer = new LinkedHashSet<>();
            for (int i = from + 1; i < fromEnd; i++) {
                if (depth[i] == d && t.get(i).isWord("LEFT")) {
                    int j = i + 1;
                    while (j < fromEnd && !t.get(j).isWord("JOIN")) {
                        j++;
                    }
                    String alias = aliasAfterTable(t, j + 1, fromEnd);
                    if (alias != null) {
                        outer.add(alias);
                    }
                }
            }
            for (int i = where + 1; i + 2 < whereEnd; i++) {
                if (depth[i] != d || t.get(i).type() != ScanToken.Type.WORD || !t.get(i + 1).isSymbol('.')) {
                    continue;
                }
                String alias = t.get(i).upper();
                if (!outer.contains(alias)) {
                    continue;
                }
                int k = i + 3;
                if (k < whereEnd && (t.get(k).isWord("IS") || t.get(k).isWord("OR"))) {
                    continue;
                }
                if (k < whereEnd && (t.get(k).isSymbol('=') || t.get(k).isSymbol('<') || t.get(k).isSymbol('>')
                        || t.get(k).isWord("IN") || t.get(k).isWord("LIKE") || t.get(k).isWord("BETWEEN"))) {
                    add("AMB-S002", Severity.MEDIUM, t("findings.S002.message", t.get(i).text() + "." + t.get(i + 2).text()), st,
                            range(t.get(i), t.get(k)), Confidence.INFERRED);
                    outer.remove(alias);
                }
            }
            nonSargable(st, t, depth, d, where + 1, whereEnd);
        }
    }

    /** AMB-S008: {@code fn(col) op …} or {@code col + n op …} in WHERE. */
    private void nonSargable(Statement st, List<ScanToken> t, int[] depth, int d, int from, int to) {
        for (int i = from; i + 2 < to; i++) {
            ScanToken fn = t.get(i);
            if (fn.type() != ScanToken.Type.WORD || !t.get(i + 1).isSymbol('(') || fn.isWordIn(NOT_FUNCTIONS)) {
                continue;
            }
            if (i > from && (t.get(i - 1).isSymbol('=') || t.get(i - 1).isSymbol('<') || t.get(i - 1).isSymbol('>'))) {
                continue;                           // function on the value side is fine
            }
            int close = i + 2;
            int level = 1;
            boolean column = false;
            while (close < to && level > 0) {
                if (t.get(close).isSymbol('(')) {
                    level++;
                } else if (t.get(close).isSymbol(')')) {
                    level--;
                } else if (t.get(close).isWord("SELECT")) {
                    column = false;
                    break;
                } else if (t.get(close).type() == ScanToken.Type.WORD && !t.get(close).isWordIn(NOT_COLUMNS)
                        && !(close + 1 < to && t.get(close + 1).isSymbol('('))) {
                    column = true;                  // a name (qualified or not) that is not a call or keyword
                }
                close++;
            }
            String indexed = column ? indexedColumnIn(st, t, i + 2, close - 1) : null;
            if (column && close < to && (t.get(close).isSymbol('=') || t.get(close).isSymbol('<')
                    || t.get(close).isSymbol('>') || t.get(close).isWord("BETWEEN") || t.get(close).isWord("IN")
                    || t.get(close).isWord("LIKE"))) {
                add("AMB-S008", indexed != null ? Severity.HIGH : Severity.MEDIUM, indexed != null
                        ? t("findings.S008.message.indexed", fn.text().toUpperCase(Locale.ROOT), indexed)
                        : t("findings.S008.message.column", fn.text().toUpperCase(Locale.ROOT)),
                        st, range(fn, t.get(close - 1)), indexed != null ? Confidence.CERTAIN : Confidence.INFERRED);
                return;
            }
        }
    }

    /**
     * {@code SELECT [alias.]col FROM [schema.]table …} starting at {@code from}: whether that column is
     * nullable per the catalog; {@code null} when the shape or the metadata is unknown.
     */
    private Boolean subqueryColumnNullable(List<ScanToken> t, int from) {
        int i = from;
        if (i + 2 < t.size() && t.get(i + 1).isSymbol('.')) {
            i += 2;                                           // alias.col
        }
        if (i + 2 >= t.size() || t.get(i).type() == ScanToken.Type.STRING || !t.get(i + 1).isWord("FROM")) {
            return null;
        }
        String column = t.get(i).text();
        StringBuilder table = new StringBuilder(t.get(i + 2).text());
        for (int j = i + 3; j + 1 < t.size() && t.get(j).isSymbol('.'); j += 2) {
            table.append('.').append(t.get(j + 1).text());
        }
        ObjectName name = ObjectName.parse(dialect, table.toString());
        if (name == null) {
            return null;
        }
        return catalog.meta(name.key(), name.simpleKey())
                .flatMap(m -> m.column(column))
                .map(TableMeta.ColumnMeta::nullable)
                .orElse(null);
    }

    /** Name of an indexed column (per the catalog) among the words in [from, to), or null. */
    private String indexedColumnIn(Statement st, List<ScanToken> t, int from, int to) {
        for (int i = from; i < to; i++) {
            if (t.get(i).type() != ScanToken.Type.WORD || (i + 1 < t.size() && t.get(i + 1).isSymbol('.'))) {
                continue;
            }
            String col = t.get(i).text();
            for (ObjectRef r : st.refs()) {
                if (r.indirect()) {
                    continue;                         // touched inside a called routine, not in this text
                }
                var meta = catalog.meta(r.name().key(), r.name().simpleKey());
                if (meta.isPresent() && meta.get().isIndexedLeading(col)) {
                    return r.name() + "." + col;
                }
            }
        }
        return null;
    }

    /**
     * AMB-M001: a many-to-many join. Within one JOIN condition, when the columns used on every side
     * cover no unique key of that side (per metadata), each row can match many rows on both sides and
     * totals get multiplied. A normal master-detail join (unique on one side) is not reported.
     */
    private void fanOut(Statement st) {
        if (st.lineage() == null) {
            return;
        }
        Map<TextRange, Map<String, List<String>>> clauses = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (ColumnEdge e : st.lineage().edges()) {
            displayNames.putIfAbsent(e.from().ownerKey(), e.from().owner());
            if (!e.isDirect() && e.role() == IndirectRole.JOIN
                    && (e.from().ownerKind() == ColumnNode.OwnerKind.TABLE || e.from().ownerKind() == ColumnNode.OwnerKind.VIEW)) {
                clauses.computeIfAbsent(e.range(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(e.from().ownerKey(), k -> new ArrayList<>()).add(e.from().column());
            }
        }
        clauses.forEach((range, owners) -> {
            if (owners.size() < 2) {
                return;
            }
            List<String> sides = new ArrayList<>();
            for (Map.Entry<String, List<String>> side : owners.entrySet()) {
                String key = side.getKey();
                String simple = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
                var meta = catalog.meta(key, simple);
                if (meta.isEmpty() || !hasKeys(meta.get()) || meta.get().isUnique(side.getValue())
                        || side.getValue().stream().anyMatch(c -> meta.get().column(c).isEmpty())) {
                    return;                     // unknown, unique on this side, or metadata of another table
                }
                sides.add(displayNames.getOrDefault(key, meta.get().qualifiedName())
                        + " (" + String.join(", ", side.getValue()) + ")");
            }
            add("AMB-M001", Severity.HIGH, t("findings.M001.message", String.join(t("findings.M001.and"), sides)), st, range, Confidence.CERTAIN);
        });
    }

    private static boolean hasKeys(TableMeta m) {
        return !m.primaryKey().isEmpty() || !m.uniqueKeys().isEmpty()
                || m.indexes().stream().anyMatch(TableMeta.IndexMeta::unique);
    }

    private static String aliasAfterTable(List<ScanToken> t, int i, int end) {
        int j = i;
        if (j < end && t.get(j).isSymbol('(')) {
            int level = 0;
            while (j < end) {
                if (t.get(j).isSymbol('(')) {
                    level++;
                } else if (t.get(j).isSymbol(')') && --level == 0) {
                    break;
                }
                j++;
            }
            j++;
        } else {
            while (j + 1 < end && t.get(j + 1).isSymbol('.')) {
                j += 2;
            }
            j++;
        }
        if (j < end && t.get(j).isWord("AS")) {
            j++;
        }
        if (j < end && (t.get(j).type() == ScanToken.Type.WORD || t.get(j).type() == ScanToken.Type.QUOTED_IDENTIFIER)
                && !t.get(j).isWordIn(Set.of("ON", "USING", "WHERE", "LEFT", "RIGHT", "INNER", "JOIN", "FULL", "CROSS"))) {
            return t.get(j).text().replace("\"", "").replace("[", "").replace("]", "").replace("`", "")
                    .toUpperCase(Locale.ROOT);
        }
        if (i < end && t.get(i).type() == ScanToken.Type.WORD) {
            return t.get(i).upper();                // no alias: the table name qualifies columns
        }
        return null;
    }

    // ----- IR rules ---------------------------------------------------------------------------------

    private void repeatedScans(Statement st) {
        Map<String, List<ObjectRef>> reads = new LinkedHashMap<>();
        for (ObjectRef r : st.refs()) {
            if (r.access() == Access.READ && r.kind() != ObjectKind.CTE && !r.indirect()) {
                reads.computeIfAbsent(r.name().key(), k -> new ArrayList<>()).add(r);
            }
        }
        reads.forEach((key, refs) -> {
            if (refs.size() >= 2) {
                add("AMB-S005", Severity.MEDIUM, t("findings.S005.message", refs.get(0).name(), refs.size()), st, refs.get(1).range(), Confidence.CERTAIN);
            }
        });
    }

    private void unresolvedColumns(Statement st) {
        if (st.lineage() == null) {
            return;
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (ColumnEdge e : st.lineage().edges()) {
            if (e.from().ownerKind() == ColumnNode.OwnerKind.UNKNOWN) {
                unknown.add(e.from().column());
            }
        }
        if (!unknown.isEmpty()) {
            add("AMB-I002", Severity.INFO, t(unknown.size() == 1 ? "findings.I002.message.one" : "findings.I002.message.other",
                    String.join(", ", unknown)), st, st.range(), Confidence.CERTAIN);
        }
    }

    private void loopRules(Statement loop) {
        loop.forEach(s -> {
            if (s == loop) {
                return;
            }
            if (s.kind() == StatementKind.INSERT || s.kind() == StatementKind.UPDATE || s.kind() == StatementKind.DELETE
                    || s.kind() == StatementKind.MERGE) {
                add("AMB-S006", Severity.MEDIUM, t("findings.S006.message." + s.kind().name().toLowerCase(Locale.ROOT)), s, s.range(),
                        Confidence.INFERRED);
            }
            if (s.kind() == StatementKind.TRANSACTION && text(s.range()).strip().toUpperCase(Locale.ROOT).startsWith("COMMIT")) {
                add("AMB-S007", Severity.MEDIUM, t("findings.S007.message"), s, s.range(), Confidence.CERTAIN);
            }
        });
    }

    // ----- duplicates -------------------------------------------------------------------------------

    private void collectSubqueries(Statement st, List<ScanToken> t, Map<String, List<TextRange>> out,
                                   Map<String, Statement> owners) {
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!t.get(i).isSymbol('(') || !t.get(i + 1).isWord("SELECT")) {
                continue;
            }
            int level = 0;
            int j = i;
            while (j < t.size()) {
                if (t.get(j).isSymbol('(')) {
                    level++;
                } else if (t.get(j).isSymbol(')') && --level == 0) {
                    break;
                }
                j++;
            }
            if (j - i < 8 || j >= t.size()) {
                continue;                           // too small to be worth reporting
            }
            String fp = fingerprint(t, i + 1, j);
            out.computeIfAbsent(fp, k -> new ArrayList<>()).add(range(t.get(i), t.get(j)));
            owners.putIfAbsent(fp, st);
        }
    }

    /** Normalized statement text: keywords and names upper-case, literals replaced, whitespace and comments gone. */
    private static String fingerprint(List<ScanToken> t, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            ScanToken tok = t.get(i);
            sb.append(switch (tok.type()) {
                case STRING, NUMBER -> "?";
                case WORD, QUOTED_IDENTIFIER -> tok.upper();
                default -> tok.text();
            }).append(' ');
        }
        return sb.toString();
    }

    // ----- helpers ----------------------------------------------------------------------------------

    /** A finding with the rule's catalogue title and general suggestion, in the request's language. */
    private void add(String rule, Severity severity, String message, Statement st, TextRange range,
                     Confidence confidence) {
        out.add(new Finding(rule, severity, t("rule." + rule + ".title"), message, t("rule." + rule + ".suggestion"),
                st, range, confidence));
    }

    private static String t(String key, Object... args) {
        return Messages.t(key, args);
    }

    /** Drops findings whose statement (or the line above it) says {@code amber:ignore RULE}. */
    private void suppress() {
        out.removeIf(f -> {
            TextRange r = f.statement().range();
            int lineStart = r.start().offset();
            int prevLine = lineStart;
            String text = source.text();
            int i = lineStart;
            while (i > 0 && text.charAt(i - 1) != '\n') {
                i--;
            }
            if (i > 0) {
                prevLine = i - 1;
                while (prevLine > 0 && text.charAt(prevLine - 1) != '\n') {
                    prevLine--;
                }
            }
            Matcher m = IGNORE.matcher(text.substring(Math.min(prevLine, lineStart), r.end().offset()));
            while (m.find()) {
                for (String id : m.group(1).split("\\s*,\\s*")) {
                    if (id.equalsIgnoreCase(f.rule())) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private List<ScanToken> tokens(TextRange r) {
        SqlScanner sc = new SqlScanner(source.text(), ScannerOptions.forDialect(dialect));
        sc.reset(r.start().offset());
        List<ScanToken> list = new ArrayList<>();
        ScanToken t;
        while ((t = sc.next()) != null && t.start() < r.end().offset()) {
            list.add(t);
        }
        return list;
    }

    private static int[] depths(List<ScanToken> t) {
        int[] d = new int[t.size()];
        int level = 0;
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).isSymbol(')')) {
                level--;
            }
            d[i] = level;
            if (t.get(i).isSymbol('(')) {
                level++;
            }
        }
        return d;
    }

    private TextRange range(ScanToken a, ScanToken b) {
        return source.range(a.start(), Math.max(a.start(), b.end()));
    }

    private String text(TextRange r) {
        return source.slice(r);
    }

    private static String unquote(String s) {
        int start = s.indexOf('\'');
        return start >= 0 && s.length() > start + 1 ? s.substring(start + 1) : s;
    }
}
