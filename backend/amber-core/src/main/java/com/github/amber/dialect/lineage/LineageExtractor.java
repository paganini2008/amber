package com.github.amber.dialect.lineage;

import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.QueryBlock;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.ColumnNode.OwnerKind;
import com.github.amber.core.lineage.IndirectRole;
import com.github.amber.core.lineage.StatementLineage;
import com.github.amber.core.lineage.Transform;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.ir.IrRules;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes the column lineage of one statement from its parse tree, driven by {@link LineageRules}.
 *
 * <p>Queries are analyzed block by block: the FROM clause gives the sources (tables, CTEs, derived
 * tables, each with its alias), each select item gives an output column with the source columns it
 * reads and how it transforms them, and WHERE / JOIN / GROUP BY columns become indirect edges. CTEs
 * and derived tables are analyzed recursively and become their own lineage nodes, so a trace shows
 * every hop. Set operations combine their branches by position.
 *
 * <p>Unqualified columns are resolved with the {@link Catalog} (the script's own DDL, later metadata).
 * When that is not enough and several tables are in scope, the script's own column evidence
 * ({@link com.github.amber.core.lineage.ColumnEvidence}) may show exactly one of them to have the column;
 * otherwise the column is attributed to an {@link OwnerKind#UNKNOWN} owner instead of a guess.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class LineageExtractor {

    private static final Set<String> AGGREGATES = Set.of("SUM", "COUNT", "AVG", "MIN", "MAX", "LISTAGG",
            "STRING_AGG", "GROUP_CONCAT", "WM_CONCAT", "ARRAY_AGG", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VARIANCE",
            "VAR_POP", "VAR_SAMP", "MEDIAN", "BIT_AND", "BIT_OR", "BOOL_AND", "BOOL_OR", "EVERY", "JSON_AGG",
            "JSONB_AGG", "JSON_ARRAYAGG", "JSON_OBJECTAGG", "XMLAGG", "COLLECT", "PERCENTILE_CONT",
            "PERCENTILE_DISC", "COUNT_BIG", "CHECKSUM_AGG", "APPROX_COUNT_DISTINCT", "TOTAL");

    private static final Set<String> CASTS = Set.of("CAST", "CONVERT", "TRY_CAST", "TRY_CONVERT", "TO_CHAR",
            "TO_DATE", "TO_NUMBER", "TO_TIMESTAMP", "TO_CLOB", "TO_NCHAR", "PARSE", "TRY_PARSE");

    private final IrRules ir;
    private final LineageRules lr;
    private final SourceText source;
    private final int base;
    private final Catalog catalog;
    private final List<ColumnEdge> edges = new ArrayList<>();
    private List<ObjectRef> statementRefs = List.of();
    /** Columns that filter or join inside nested scalar/EXISTS/IN subqueries; drained by the caller as indirect edges. */
    private final List<ColumnNode> nestedIndirect = new ArrayList<>();
    /** Nested query structure built while the lineage is traversed; the root collects top-level boxes. */
    private final QueryBlock structureRoot = new QueryBlock(QueryBlock.Kind.QUERY, null, null, null);
    /** The box opened for each query block node, so a query root can find the box of its own last block. */
    private final java.util.Map<ParserRuleContext, QueryBlock> boxOf = new java.util.IdentityHashMap<>();
    private final java.util.ArrayDeque<QueryBlock> open = new java.util.ArrayDeque<>(List.of(structureRoot));
    private final Set<ParserRuleContext> opened = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Variables visible to the statements of the tree, or {@code null} when variables are not followed. */
    private VariableScopes variables;
    /** The scope of the statement being extracted. */
    private VariableScopes.Scope varScope;
    /** Select items that assign a variable ({@code SELECT @v = expr}), with the variable name. */
    private final Map<Out, String> assignedItems = new java.util.IdentityHashMap<>();
    /** Calls of possible script routines already analyzed, by the offset of their opening parenthesis. */
    private final Map<Integer, ColumnNode> calls = new java.util.HashMap<>();
    /** Calls whose subtree is being walked (nested nodes that span the same call are the same call). */
    private final Set<Integer> activeCalls = new java.util.HashSet<>();
    /** Variables an UPDATE assigns ({@code SET @v = col}); they also take the statement's filters. */
    private final List<ColumnNode> assignedVars = new ArrayList<>();
    /** The query or blocks of an INSERT's VALUES source, whose rows become positional outputs. */
    private final Set<ParserRuleContext> valuesSources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public LineageExtractor(IrRules ir, LineageRules lr, SourceText source, int base, Catalog catalog) {
        this.ir = ir;
        this.lr = lr;
        this.source = source;
        this.base = base;
        this.catalog = catalog;
    }

    /**
     * Follows values through procedural variables: assignments, declarations, {@code SELECT … INTO},
     * {@code FETCH … INTO} write variables, and names that are no column read them.
     */
    public LineageExtractor variables(VariableScopes scopes) {
        this.variables = scopes;
        return this;
    }

    /** The nested query structure of the last {@link #extract} call, simplified; empty when it has no query. */
    public List<QueryBlock> structure() {
        return structureRoot.simplified().children();
    }

    /** Opens a structure box for {@code ctx} under the current one; a subtree seen twice gets a detached box. */
    private QueryBlock openBox(QueryBlock.Kind kind, String name, String role, ParserRuleContext ctx) {
        QueryBlock box = new QueryBlock(kind, name, role, range(ctx));
        if (opened.add(ctx)) {
            open.peek().addChild(box);
        }
        open.push(box);
        return box;
    }

    private void closeBox() {
        open.pop();
    }

    private static final java.util.regex.Pattern SET_OPERATOR = java.util.regex.Pattern.compile(
            "(?i)\\b(UNION(?:\\s+ALL|\\s+DISTINCT)?|INTERSECT(?:\\s+ALL)?|EXCEPT(?:\\s+ALL)?|MINUS)\\b");

    private static final java.util.regex.Pattern CLAUSE_KEYWORD = java.util.regex.Pattern.compile(
            "(?i)\\b(SELECT|FROM|JOIN|WHERE|HAVING|ON|SET)\\b");

    /** Clause keywords that describe a query block, in display order. */
    private static final List<String> CLAUSE_ORDER = List.of("DISTINCT", "TOP", "JOIN", "WHERE", "START", "CONNECT",
            "GROUP", "HAVING", "WINDOW", "QUALIFY", "ORDER", "LIMIT", "OFFSET", "FETCH", "PIVOT", "UNPIVOT", "FOR");

    /** Fills a query box with what the block reads, outputs and which clauses it uses. */
    private void describe(QueryBlock box, ParserRuleContext block, Scope scope, List<Out> outs) {
        for (Source src : scope.sources) {
            String alias = src.alias == null || src.alias.isEmpty()
                    || src.alias.equalsIgnoreCase(lastPart(src.owner.display)) ? null : src.alias.toLowerCase(Locale.ROOT);
            box.addSource(new QueryBlock.Source(src.owner.display, src.owner.kind.name(), alias));
        }
        outs.forEach(o -> box.addOutput(o.name));
        List<org.antlr.v4.runtime.tree.TerminalNode> tokens = clauseTokens(block, block, false);
        clauseLabels(counts(tokens)).forEach(box::addClause);
        addPhases(box, tokens, range(block).end().offset());
    }

    private static List<String> clauseLabels(java.util.Map<String, Integer> found) {
        List<String> out = new ArrayList<>();
        for (String k : CLAUSE_ORDER) {
            Integer n = found.get(k);
            if (n == null) {
                continue;
            }
            out.add(switch (k) {
                case "GROUP" -> "GROUP BY";
                case "ORDER" -> "ORDER BY";
                case "CONNECT" -> "CONNECT BY";
                case "START" -> "START WITH";
                case "JOIN" -> n > 1 ? "JOIN ×" + n : "JOIN";
                default -> k;
            });
        }
        return out;
    }

    /** Clause keyword tokens of a block in source order: outside nested queries and parentheses (JOIN anywhere). */
    private List<org.antlr.v4.runtime.tree.TerminalNode> clauseTokens(ParseTree node, ParserRuleContext block,
                                                                     boolean root) {
        List<org.antlr.v4.runtime.tree.TerminalNode> out = new ArrayList<>();
        scanClauses(node, block, root, out, new int[1]);
        return out;
    }

    private void scanClauses(ParseTree node, ParserRuleContext block, boolean root,
                             List<org.antlr.v4.runtime.tree.TerminalNode> out, int[] depth) {
        if (node instanceof org.antlr.v4.runtime.tree.TerminalNode t) {
            String w = t.getText().toUpperCase(Locale.ROOT);
            if (w.equals("(")) {
                depth[0]++;
            } else if (w.equals(")")) {
                depth[0] = Math.max(0, depth[0] - 1);
            } else if ((CLAUSE_ORDER.contains(w) || w.equals("SELECT") || w.equals("FROM"))
                    && (depth[0] == 0 || w.equals("JOIN"))) {
                boolean needsPartner = w.equals("FOR") || w.equals("START") || w.equals("TOP") || w.equals("OFFSET");
                if (!needsPartner || isClauseKeyword(t, w)) {
                    out.add(t);
                }
            }
            return;
        }
        if (node != block && node instanceof ParserRuleContext c) {
            String rule = ir.ruleName(c);
            if (lr.queryBlocks().contains(rule) || rule.equals(lr.cteDefinition())
                    || (!root && lr.queryRoots().contains(rule))) {
                return;                                   // nested queries describe themselves
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            scanClauses(node.getChild(i), block, root, out, depth);
        }
    }

    private static java.util.Map<String, Integer> counts(List<org.antlr.v4.runtime.tree.TerminalNode> tokens) {
        java.util.Map<String, Integer> found = new java.util.HashMap<>();
        for (var t : tokens) {
            found.merge(t.getText().toUpperCase(Locale.ROOT), 1, Integer::sum);
        }
        return found;
    }

    /** Logical processing rank of a clause keyword; the database applies lower ranks first. */
    private static final java.util.Map<String, Integer> PHASE_RANK = java.util.Map.ofEntries(
            java.util.Map.entry("FROM", 1), java.util.Map.entry("START", 2), java.util.Map.entry("CONNECT", 3),
            java.util.Map.entry("WHERE", 4), java.util.Map.entry("GROUP", 5), java.util.Map.entry("HAVING", 6),
            java.util.Map.entry("WINDOW", 7), java.util.Map.entry("SELECT", 8), java.util.Map.entry("QUALIFY", 9),
            java.util.Map.entry("DISTINCT", 10), java.util.Map.entry("ORDER", 11), java.util.Map.entry("OFFSET", 12),
            java.util.Map.entry("LIMIT", 13), java.util.Map.entry("FETCH", 13), java.util.Map.entry("TOP", 13));

    private static final java.util.regex.Pattern TOP_ARGUMENT = java.util.regex.Pattern.compile(
            "\\s*(\\([^)]*\\)|[\\w@:]+)(\\s+PERCENT)?(\\s+WITH\\s+TIES)?", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Adds the logical phases of a block (FROM, WHERE, GROUP BY, …, SELECT, DISTINCT, ORDER BY, LIMIT) with
     * their source ranges: each clause runs from its keyword to the next clause keyword.
     */
    private void addPhases(QueryBlock box, List<org.antlr.v4.runtime.tree.TerminalNode> tokens, int endOffset) {
        List<org.antlr.v4.runtime.tree.TerminalNode> delimiters = new ArrayList<>();
        for (var t : tokens) {
            String w = t.getText().toUpperCase(Locale.ROOT);
            if (PHASE_RANK.containsKey(w) && !w.equals("DISTINCT") && !w.equals("TOP")) {
                delimiters.add(t);
            }
        }
        for (int i = 0; i < delimiters.size(); i++) {
            int from = tokenStart(delimiters.get(i));
            int to = i + 1 < delimiters.size() ? tokenStart(delimiters.get(i + 1)) : endOffset;
            addPhase(box, delimiters.get(i).getText().toUpperCase(Locale.ROOT), from, to);
        }
        for (var t : tokens) {
            String w = t.getText().toUpperCase(Locale.ROOT);
            int from = tokenStart(t);
            int to = from + t.getText().length();
            if (w.equals("TOP")) {
                java.util.regex.Matcher m = TOP_ARGUMENT.matcher(source.text()).region(to, source.length());
                if (m.lookingAt()) {
                    to = m.end();
                }
                addPhase(box, w, from, to);
            } else if (w.equals("DISTINCT")) {
                addPhase(box, w, from, to);
            }
        }
    }

    private void addPhase(QueryBlock box, String keyword, int from, int to) {
        String text = source.text().substring(from, Math.max(from, Math.min(to, source.length())));
        String body = text.stripTrailing();
        while (body.endsWith(";") || body.endsWith("/")) {
            body = body.substring(0, body.length() - 1).stripTrailing();   // the statement terminator is not part of it
        }
        int end = from + body.length();
        String name = switch (keyword) {
            case "GROUP" -> "GROUP BY";
            case "ORDER" -> "ORDER BY";
            case "CONNECT" -> "CONNECT BY";
            case "START" -> "START WITH";
            default -> keyword;
        };
        box.addPhase(new QueryBlock.Phase(name, PHASE_RANK.get(keyword), source.range(from, Math.max(from, end))));
    }

    private int tokenStart(org.antlr.v4.runtime.tree.TerminalNode t) {
        return Math.min(base + t.getSymbol().getStartIndex(), source.length());
    }

    /** {@code START WITH}, {@code FOR UPDATE}, {@code TOP n}, {@code OFFSET n}: keywords that need a partner. */
    private boolean isClauseKeyword(org.antlr.v4.runtime.tree.TerminalNode t, String w) {
        int at = t.getSymbol().getStopIndex() + 1 + base;
        String rest = source.text().substring(Math.min(at, source.length()),
                Math.min(source.length(), at + 24)).stripLeading().toUpperCase(Locale.ROOT);
        return switch (w) {
            case "START" -> rest.startsWith("WITH");
            case "FOR" -> rest.startsWith("UPDATE") || rest.startsWith("XML") || rest.startsWith("JSON");
            default -> !rest.isEmpty();
        };
    }

    /** Where a subquery sits in its enclosing block: {@code WHERE IN}, {@code SELECT scalar}, {@code HAVING EXISTS}… */
    private String subqueryRole(ParserRuleContext sub) {
        String clause = null;
        ParserRuleContext enclosing = null;
        for (ParserRuleContext p = sub.getParent(); p != null; p = p.getParent()) {
            String rule = ir.ruleName(p);
            if (lr.queryBlocks().contains(rule)) {
                enclosing = p;
                break;
            }
            if (rule.equals(lr.selectItem())) {
                clause = "SELECT";
            } else if (lr.filterClauses() != null && lr.filterClauses().contains(rule)) {
                String w = firstWord(p).toUpperCase(Locale.ROOT);
                clause = Set.of("WHERE", "HAVING", "ON", "QUALIFY", "WHEN").contains(w) ? w : "WHERE";
            } else if (lr.joinClauses() != null && lr.joinClauses().contains(rule)) {
                clause = "ON";
            } else if (lr.setClauses().contains(rule)) {
                clause = "SET";
            }
            if (clause != null) {
                break;
            }
        }
        if (clause == null && enclosing != null) {
            // Grammars without clause rules (SQLite expr): the last clause keyword before the subquery.
            int from = range(enclosing).start().offset();
            int to = range(sub).start().offset();
            String head = source.text().substring(from, Math.max(from, to));
            for (String prev = null; !head.equals(prev); ) {
                prev = head;
                head = head.replaceAll("\\([^()]*\\)", " ");     // keywords of nested subqueries do not count
            }
            java.util.regex.Matcher k = CLAUSE_KEYWORD.matcher(head);
            while (k.find()) {
                clause = k.group(1).toUpperCase(Locale.ROOT);
            }
            if ("FROM".equals(clause) || "JOIN".equals(clause)) {
                clause = null;
            }
        }
        // The predicate right before the subquery's parenthesis: IN, EXISTS, ANY, ALL, SOME, else scalar.
        int start = range(sub).start().offset();
        String before = source.text().substring(Math.max(0, start - 40), start).toUpperCase(Locale.ROOT)
                .replaceAll("[\\s(]+$", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(NOT\\s+IN|NOT\\s+EXISTS|IN|EXISTS|ANY|ALL|SOME)$")
                .matcher(before);
        String predicate = m.find() ? m.group(1).replaceAll("\\s+", " ") : "scalar";
        return clause == null ? predicate : clause + " " + predicate;
    }

    /** Lineage of {@code st} built from {@code node}, or {@code null} for kinds without data flow. */
    public StatementLineage extract(Statement st, ParserRuleContext node) {
        statementRefs = st.refs();
        varScope = variables == null ? null : variables.scopeOf(node);
        if (st.kind().isContainer()) {
            return procedural(node);                  // assignments and declarations that are no statements
        }
        return switch (st.kind()) {
            case SELECT, CURSOR -> select(st, node);
            case DECLARE, ASSIGNMENT, SESSION, OTHER, RETURN -> procedural(node);
            case CALL -> call(node);
            case DYNAMIC_SQL -> dynamicInto(node);
            case INSERT -> insert(st, node);
            case CREATE_TABLE_AS, SELECT_INTO, CREATE_VIEW, CREATE_MATERIALIZED_VIEW -> create(st, node);
            case CREATE_TABLE -> {
                recordDdlColumns(st, node);
                yield null;
            }
            case CREATE_INDEX -> {
                recordIndex(st, node);
                yield null;
            }
            case ALTER -> {
                recordForeignKeys(st, node);
                yield null;
            }
            case UPDATE -> update(st, node, Access.UPDATE);
            case MERGE -> merge(st, node);
            case DELETE -> update(st, node, Access.DELETE);
            default -> null;
        };
    }

    // ----- statements -----------------------------------------------------------------------------

    private record Owner(OwnerKind kind, String display, String key) {
        ColumnNode column(String name) {
            return new ColumnNode(kind, display, key, name);
        }
    }

    private Owner resultOwner(Statement st) {
        return new Owner(OwnerKind.RESULT, "result", "result@" + st.range().start().offset());
    }

    /** A query, a cursor, or a {@code SELECT … INTO} / {@code SELECT @v = …} that assigns variables. */
    private StatementLineage select(Statement st, ParserRuleContext node) {
        ParserRuleContext q = firstQuery(node);
        if (q == null) {
            return st.kind() == StatementKind.CURSOR ? procedural(node) : valuesInto(node);
        }
        QueryOut out = query(q, statementScope(node, q));
        Owner owner = resultOwner(st);
        if (variables != null) {
            List<ColumnNode> targets = selectTargets(node, out);
            if (targets != null) {
                return emitVariables(out, targets);
            }
            if (st.kind() == StatementKind.CURSOR) {
                String name = VariableScopes.cursorName(text(node));
                if (name != null) {
                    varScope.addCursor(name, new VariableScopes.Cursor(owner.key(), out.names()));
                }
            }
        }
        return emit(out, owner, null);
    }

    /**
     * INSERT … SELECT and INSERT … VALUES: the query's (or the VALUES row's) columns map by position to
     * the insert column list, else to the table's known columns.
     */
    private StatementLineage insert(Statement st, ParserRuleContext node) {
        ObjectRef target = target(st, Access.INSERT);
        if (target == null) {
            return null;
        }
        Scope targetScope = new Scope(null);
        targetScope.sources.add(new Source(target.name().simpleKey().toUpperCase(Locale.ROOT), tableOwner(target),
                target.name(), null));
        List<ColumnNode> returned = returning(st, node, targetScope);
        StatementLineage main = insertBody(node, target);
        if (returned.isEmpty()) {
            return main;
        }
        if (main == null) {
            return result(returned);
        }
        List<ColumnNode> outputs = new ArrayList<>(main.outputs());
        returned.forEach(o -> addOutput(outputs, o));
        return new StatementLineage(outputs, main.edges());
    }

    private StatementLineage insertBody(ParserRuleContext node, ObjectRef target) {
        ParserRuleContext q = firstQuery(node);
        List<ParserRuleContext> rows = lr.valuesExpressionList() == null ? List.of()
                : findAll(node, lr.valuesExpressionList(), lr.queryBlocks());
        QueryOut out;
        boolean values;
        if (q != null && (rows.isEmpty() || !isInside(q, rows.get(0)))) {
            values = isValues(q);
            if (values) {
                valuesSources.add(q);
                valuesSources.addAll(findAllAny(q, lr.queryBlocks(), Set.of()));
            }
            out = query(q, statementScope(node, q));
        } else if (!rows.isEmpty()) {
            out = valuesOut(rows, new Scope(statementScope(node, null)));
            values = true;
        } else {
            return insertSet(node, target);
        }
        List<String> names = insertColumns(node);
        if (names.isEmpty()) {
            names = catalog.columns(target.name().key(), target.name().simpleKey()).orElse(List.of());
        }
        if (names.isEmpty() && values) {
            return null;                              // VALUES without known columns: positions mean nothing
        }
        return emit(out, tableOwner(target), names.isEmpty() ? null : names);
    }

    /** MySQL / MariaDB {@code INSERT … SET col = expr, …}: each assignment writes its column. */
    private StatementLineage insertSet(ParserRuleContext node, ObjectRef target) {
        Owner owner = tableOwner(target);
        Scope scope = new Scope(statementScope(node, null));
        List<ColumnNode> outputs = new ArrayList<>();
        for (String setRule : lr.setClauses()) {
            for (ParserRuleContext set : findAll(node, setRule)) {
                assign(set, scope, owner, outputs);
            }
        }
        return outputs.isEmpty() ? null : result(outputs);
    }

    private boolean isValues(ParserRuleContext node) {
        return text(node).stripLeading().regionMatches(true, 0, "VALUES", 0, 6);
    }

    /** The VALUES rows of a query or block that has no select list, as positional outputs; else null. */
    private QueryOut valuesQuery(ParserRuleContext node, Scope scope) {
        if (lr.valuesExpressionList() == null || !valuesSources.contains(node) || !isValues(node)) {
            return null;
        }
        List<ParserRuleContext> rows = findAll(node, lr.valuesExpressionList(), lr.queryBlocks());
        return rows.isEmpty() ? null : valuesOut(rows, scope);
    }

    /** One output per VALUES position; later rows add their sources to the same position. */
    private QueryOut valuesOut(List<ParserRuleContext> rows, Scope scope) {
        List<Out> outs = new ArrayList<>();
        List<Indirect> indirect = new ArrayList<>();
        for (ParserRuleContext row : rows) {
            List<ParserRuleContext> exprs = childRules(row);
            for (int i = 0; i < exprs.size(); i++) {
                ParserRuleContext e = exprs.get(i);
                List<ColumnNode> refs = refsIn(e, scope);
                drainNested(indirect, IndirectRole.FILTER, range(e));
                String exprText = text(e);
                Transform t = refs.size() == 1 && isPlainColumn(exprText) ? Transform.PASS_THROUGH : classify(e, refs.size());
                if (i < outs.size()) {
                    Out o = outs.get(i);
                    List<ColumnNode> merged = new ArrayList<>(o.sources);
                    merged.addAll(refs);
                    outs.set(i, new Out(o.name, o.transform == t ? t : Transform.EXPRESSION, merged, o.range, o.confidence));
                } else {
                    outs.add(new Out(exprName(exprText), t, refs, range(e), Confidence.INFERRED));
                }
            }
        }
        return new QueryOut(outs, indirect);
    }

    private String exprName(String exprText) {
        if (isPlainColumn(exprText)) {
            return lastPart(exprText.strip());
        }
        String name = exprText.strip().replaceAll("\\s+", " ");
        return name.length() > 40 ? name.substring(0, 37) + "…" : name;
    }

    private StatementLineage create(Statement st, ParserRuleContext node) {
        ObjectRef target = target(st, Access.CREATE);
        ParserRuleContext q = firstQuery(node);
        if (target == null || q == null) {
            return null;
        }
        QueryOut out = query(q, statementScope(node, q));
        StatementLineage lineage = emit(out, tableOwner(target), null);
        catalog.put(target.name().key(), target.name().simpleKey(), out.names());
        return lineage;
    }

    /** UPDATE ({@code SET col = expr}) and DELETE (indirect only). */
    private StatementLineage update(Statement st, ParserRuleContext node, Access access) {
        ObjectRef target = target(st, access);
        if (target == null) {
            return null;
        }
        Owner owner = tableOwner(target);
        Scope scope = new Scope(statementScope(node, null));
        collectSources(node, scope);
        List<ColumnNode> outputs = new ArrayList<>();
        for (String setRule : lr.setClauses()) {
            for (ParserRuleContext set : findAll(node, setRule)) {
                assign(set, scope, owner, outputs);
            }
        }
        for (var pair : lr.hooks().assignments(node)) {
            assignPair(pair.getKey(), pair.getValue(), scope, owner, outputs);
        }
        returning(st, node, scope).forEach(o -> addOutput(outputs, o));
        indirect(node, scope, owner);
        if (!assignedVars.isEmpty()) {
            // SET @v = col: the variable takes its value from the rows the statement filters.
            ColumnNode rows = owner.column(ColumnNode.ROWS);
            for (ColumnEdge e : new ArrayList<>(edges)) {
                if (!e.isDirect() && e.to().key().equals(rows.key())) {
                    for (ColumnNode v : assignedVars) {
                        edges.add(ColumnEdge.indirect(e.from(), rowsOf(v), e.role(), e.confidence(), e.range()));
                    }
                }
            }
        }
        return result(outputs);
    }

    /**
     * {@code RETURNING exprs INTO v1, v2} of a DML statement: the expressions (columns of the target
     * row) flow into the variables by position; without {@code INTO} (PostgreSQL, or PL/pgSQL whose
     * {@code INTO} was blanked before parsing) into the statement's result columns.
     *
     * @return the written variables or result columns
     */
    private List<ColumnNode> returning(Statement st, ParserRuleContext node, Scope scope) {
        Set<String> rules = lr.hooks().returningClauses();
        ParserRuleContext ret = rules.isEmpty() ? null : firstOf(node, rules);
        if (ret == null || ret.getChildCount() < 2) {
            return List.of();
        }
        ParserRuleContext into = variables == null ? null : firstOf(ret, variables.rules().intoClauses());
        int from = rangeOf(ret.getChild(0)).end().offset();
        int to = into != null ? range(into).start().offset() : range(ret).end().offset();
        List<ColumnNode> vars = null;
        if (into != null) {
            vars = new ArrayList<>();
            for (String name : VariableScopes.intoNames(text(into))) {
                vars.add(name == null ? null : intoVariable(name));
            }
        }
        Owner result = resultOwner(st);
        List<ColumnNode> outputs = new ArrayList<>();
        String text = source.text();
        List<int[]> spans = ArgumentText.split(text, from, to);
        for (int i = 0; i < spans.size(); i++) {
            int[] s = ArgumentText.trim(text, spans.get(i)[0], spans.get(i)[1]);
            String value = text.substring(s[0], s[1]);
            if (value.isEmpty() || value.endsWith("*")) {
                continue;
            }
            ColumnNode target = vars == null ? result.column(returningName(value))
                    : vars.size() == 1 ? vars.get(0) : i < vars.size() ? vars.get(i) : null;
            if (target == null) {
                continue;
            }
            List<ColumnNode> refs = new ArrayList<>();
            refsInSpan(ret, s[0], s[1], scope, refs);
            addOutput(outputs, target);
            Transform t = refs.size() == 1 && isPlainColumn(value) ? Transform.PASS_THROUGH : classifyText(value, refs.size());
            for (ColumnNode ref : refs) {
                if (!ref.key().equals(target.key())) {
                    edges.add(ColumnEdge.direct(ref, target, t, Confidence.INFERRED, source.range(s[0], s[1])));
                }
            }
        }
        nestedIndirect.clear();
        return outputs;
    }

    private static final java.util.regex.Pattern AS_ALIAS = java.util.regex.Pattern.compile(
            "(?is).*\\s+AS\\s+([\\p{L}_\"`\\[][\\p{L}\\p{N}_$#\"`\\]]*)\\s*");

    /** Name of a RETURNING item: its alias, its column, or its text. */
    private String returningName(String value) {
        java.util.regex.Matcher m = AS_ALIAS.matcher(value);
        if (m.matches()) {
            return m.group(1).replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
        }
        return exprName(value);
    }

    /** Db2 {@code VALUES expr [, …] INTO v [, …]}: the expressions flow into the variables by position. */
    private StatementLineage valuesInto(ParserRuleContext node) {
        if (variables == null) {
            return null;
        }
        ParserRuleContext into = firstOf(node, variables.rules().intoClauses());
        String t = text(node);
        if (into == null || !isValues(node)) {
            return null;
        }
        String text = source.text();
        int from = offset(node) + (t.length() - t.stripLeading().length()) + "VALUES".length();
        int to = range(into).start().offset();
        List<String> names = VariableScopes.intoNames(text(into));
        List<int[]> spans = ArgumentText.split(text, from, to);
        if (spans.size() == 1 && names.size() > 1) {
            int[] s = ArgumentText.trim(text, spans.get(0)[0], spans.get(0)[1]);
            if (s[1] > s[0] && text.charAt(s[0]) == '(' && ArgumentText.closing(text, s[0]) == s[1] - 1) {
                spans = ArgumentText.split(text, s[0] + 1, s[1] - 1);     // VALUES (a, b) INTO x, y
            }
        }
        Scope scope = new Scope(null);
        List<Out> outs = new ArrayList<>();
        for (int[] span : spans) {
            int[] s = ArgumentText.trim(text, span[0], span[1]);
            String value = text.substring(s[0], s[1]);
            List<ColumnNode> refs = new ArrayList<>();
            refsInSpan(node, s[0], s[1], scope, refs);
            Transform tr = refs.size() == 1 && isPlainColumn(value) ? Transform.PASS_THROUGH : classifyText(value, refs.size());
            outs.add(new Out(exprName(value), tr, refs, source.range(s[0], s[1]), Confidence.INFERRED));
        }
        List<Indirect> indirect = new ArrayList<>();
        drainNested(indirect, IndirectRole.FILTER, range(node));
        List<ColumnNode> vars = new ArrayList<>();
        for (String name : names) {
            vars.add(name == null ? null : intoVariable(name));
        }
        return vars.isEmpty() ? null : emitVariables(new QueryOut(outs, indirect), vars);
    }

    /**
     * {@code EXECUTE IMMEDIATE '…' INTO v} (and other dynamic SQL with {@code INTO}): the dynamic text is
     * not analyzed, so the variables are written (statement outputs) without any edge into them.
     */
    private StatementLineage dynamicInto(ParserRuleContext node) {
        if (variables == null || variables.rules().intoClauses().isEmpty()) {
            return null;
        }
        ParserRuleContext into = firstOf(node, variables.rules().intoClauses());
        if (into == null) {
            return null;
        }
        List<ColumnNode> outputs = new ArrayList<>();
        for (String name : VariableScopes.intoNames(text(into))) {
            if (name != null) {
                addOutput(outputs, intoVariable(name));
            }
        }
        return outputs.isEmpty() ? null : result(outputs);
    }

    // ----- calls of routines the script may define --------------------------------------------------

    private static final String NAME_RE = "[\\p{L}_\"\\[`][\\p{L}\\p{N}_$#\"\\]`]*";
    private static final java.util.regex.Pattern CALL_HEAD = java.util.regex.Pattern.compile(
            "(?s)\\s*(" + NAME_RE + "(?:\\s*\\.\\s*" + NAME_RE + ")*)\\s*\\(");
    private static final java.util.regex.Pattern FIELD_TAIL = java.util.regex.Pattern.compile(
            "(?s)(?:\\s*\\.\\s*" + NAME_RE + ")+\\s*");
    private static final java.util.regex.Pattern CALL_STATEMENT = java.util.regex.Pattern.compile(
            "(?is)\\s*(?:(?:CALL|EXEC|EXECUTE)\\s+)?(?:@[\\p{L}\\p{N}_@#$]+\\s*=\\s*)?(" + NAME_RE
                    + "(?:\\s*\\.\\s*" + NAME_RE + ")*)");
    private static final java.util.regex.Pattern NAMED_ARGUMENT = java.util.regex.Pattern.compile(
            "(?s)(@?" + NAME_RE + ")\\s*(=>|:=|=(?![=>]))\\s*");
    private static final java.util.regex.Pattern OUTPUT_ARGUMENT = java.util.regex.Pattern.compile(
            "(?is)(.*?)\\s+(?:OUTPUT|OUT)\\s*");

    /**
     * {@code name(args)} (optionally followed by {@code .field}) spanning a whole node.
     *
     * @param nameStart absolute offset of the name
     * @param open      absolute offset of the opening parenthesis
     * @param close     absolute offset of the closing parenthesis
     * @param plain     whether nothing follows the closing parenthesis
     */
    private record Call(String name, int nameStart, int open, int close, boolean plain) {
    }

    private Call callAt(ParserRuleContext node) {
        boolean collections = variables != null && ir.dialect() == com.github.amber.core.Dialect.ORACLE;
        if (!collections && !catalog.hasRoutines() || node.getStart() == null || node.getStop() == null) {
            return null;
        }
        if (!")".equals(node.getStop().getText()) && !(collections && variable(node.getStart().getText()) != null)) {
            return null;
        }
        String t = text(node);
        java.util.regex.Matcher m = CALL_HEAD.matcher(t);
        if (!m.lookingAt()) {
            return null;
        }
        int open = m.end() - 1;
        int close = ArgumentText.closing(t, open);
        if (close < 0) {
            return null;
        }
        String tail = t.substring(close + 1);
        boolean plain = tail.isBlank();
        if (!plain && !FIELD_TAIL.matcher(tail).matches()) {
            return null;
        }
        int at = offset(node);
        return new Call(m.group(1).replaceAll("\\s+", ""), at + m.start(1), at + open, at + close, plain);
    }

    /**
     * What a call node reads: a PL/SQL collection element {@code v(i)} / {@code v(i).field} reads the
     * variable {@code v}; a call of a routine the script may define reads the routine's result
     * (placeholder resolved by {@link CallLineage}), and its arguments flow into the parameters.
     * Null when the node is neither.
     */
    private ColumnNode callRead(Call call, ParserRuleContext node, Scope scope) {
        if (!call.name().contains(".") && variables != null && ir.dialect() == com.github.amber.core.Dialect.ORACLE) {
            ColumnNode v = variable(call.name());
            if (v != null) {
                return v;
            }
        }
        if (!call.plain()) {
            return null;
        }
        ObjectName name = ObjectName.parse(ir.dialect(), call.name());
        if (name == null || !catalog.isRoutine(name.simpleName())) {
            return null;
        }
        ColumnNode ret = calls.get(call.open());
        if (ret == null) {
            ret = CallLineage.result(call.name(), call.nameStart());
            calls.put(call.open(), ret);
            arguments(call.name(), call.nameStart(), node, call.open() + 1, call.close(), scope, false);
        }
        return ret;
    }

    /** CALL / EXEC / a PL/SQL procedure call statement of a routine the script may define. */
    private StatementLineage call(ParserRuleContext node) {
        String t = text(node);
        java.util.regex.Matcher m = CALL_STATEMENT.matcher(t);
        if (!m.lookingAt()) {
            return null;
        }
        String callee = m.group(1).replaceAll("\\s+", "");
        ObjectName name = ObjectName.parse(ir.dialect(), callee);
        if (name == null || !catalog.isRoutine(name.simpleName())) {
            return null;
        }
        String text = source.text();
        int at = offset(node);
        int end = range(node).end().offset();
        int i = at + m.end();
        while (i < end && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        int from;
        int to;
        if (i < end && text.charAt(i) == '(') {
            int close = ArgumentText.closing(text, i);
            if (close < 0 || close >= end) {
                return null;
            }
            from = i + 1;
            to = close;
        } else {
            from = i;
            to = end;
            while (to > from && (text.charAt(to - 1) == ';' || Character.isWhitespace(text.charAt(to - 1)))) {
                to--;
            }
        }
        List<ColumnNode> outputs = new ArrayList<>();
        arguments(callee, at + m.start(1), node, from, to, new Scope(statementScope(node, null)), true);
        return edges.isEmpty() ? null : result(outputs);
    }

    /**
     * Edges from each argument in {@code [from, to)} into the placeholder of its parameter slot ({@code #n}
     * or the name of a named argument); in a call statement, an argument that is a plain variable also
     * gets an edge back from the slot (an OUT parameter writes it).
     */
    private void arguments(String callee, int callAt, ParserRuleContext root, int from, int to, Scope scope,
                           boolean statement) {
        String text = source.text();
        int[] all = ArgumentText.trim(text, from, to);
        if (all[0] >= all[1]) {
            return;
        }
        int position = 0;
        for (int[] span : ArgumentText.split(text, from, to)) {
            position++;
            int[] s = ArgumentText.trim(text, span[0], span[1]);
            if (s[0] >= s[1]) {
                continue;
            }
            String slot = "#" + position;
            java.util.regex.Matcher named = NAMED_ARGUMENT.matcher(text.substring(s[0], s[1]));
            if (named.lookingAt() && (!named.group(2).equals("=") || named.group(1).startsWith("@"))) {
                slot = named.group(1);
                s[0] += named.end();
            }
            if (ir.dialect() == com.github.amber.core.Dialect.SQLSERVER) {
                java.util.regex.Matcher out = OUTPUT_ARGUMENT.matcher(text.substring(s[0], s[1]));
                if (out.matches()) {
                    s[1] = s[0] + out.group(1).length();
                }
            }
            String value = text.substring(s[0], s[1]).strip();
            List<ColumnNode> refs = new ArrayList<>();
            refsInSpan(root, s[0], s[1], scope, refs);
            nestedIndirect.clear();
            TextRange r = source.range(s[0], s[1]);
            boolean plain = refs.size() == 1 && isPlainColumn(value);
            Transform t = plain ? Transform.PASS_THROUGH : classifyText(value, refs.size());
            ColumnNode param = CallLineage.argument(callee, callAt, slot);
            for (ColumnNode ref : refs) {
                edges.add(ColumnEdge.direct(ref, param, t, Confidence.INFERRED, r));
            }
            if (statement && plain && (refs.get(0).ownerKind() == OwnerKind.VARIABLE
                    || refs.get(0).ownerKind() == OwnerKind.PARAMETER) && !CallLineage.isPlaceholder(refs.get(0))) {
                edges.add(ColumnEdge.direct(CallLineage.outArgument(callee, callAt, slot), refs.get(0),
                        Transform.PASS_THROUGH, Confidence.INFERRED, r));
            }
        }
    }

    /** References inside {@code [from, to)} of {@code node}'s subtree. */
    private void refsInSpan(ParserRuleContext node, int from, int to, Scope scope, List<ColumnNode> out) {
        TextRange r = range(node);
        int a = r.start().offset();
        int b = r.end().offset();
        if (b <= from || a >= to) {
            return;
        }
        if (a >= from && b <= to) {
            refsIn(node, scope, out);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree c = node.getChild(i);
            if (c instanceof ParserRuleContext rc) {
                refsInSpan(rc, from, to, scope, out);
            } else if (variables != null) {
                TextRange tr = rangeOf(c);
                if (tr.start().offset() >= from && tr.end().offset() <= to) {
                    ColumnNode v = prefixedVariable(c.getText());
                    if (v != null) {
                        out.add(v);
                    }
                }
            }
        }
    }

    /**
     * CTEs written at statement level, outside the query (T-SQL {@code WITH … INSERT …}, PostgreSQL
     * {@code WITH … UPDATE …}), registered before the query is analyzed.
     */
    private Scope statementScope(ParserRuleContext node, ParserRuleContext query) {
        Scope scope = new Scope(null);
        if (lr.cteDefinition() != null) {
            for (ParserRuleContext def : cteDefinitions(node)) {
                if (query == null || !isInside(def, query)) {
                    cte(def, scope);
                }
            }
        }
        return scope;
    }

    /** The statement's lineage with duplicate edges removed (MERGE UPDATE and INSERT often repeat them). */
    private StatementLineage result(List<ColumnNode> outputs) {
        Map<String, ColumnEdge> unique = new LinkedHashMap<>();
        for (ColumnEdge e : edges) {
            String key = e.from().key() + ">" + e.to().key() + ">" + e.transform() + ">" + e.role();
            unique.putIfAbsent(key, e);
        }
        return new StatementLineage(outputs, new ArrayList<>(unique.values()));
    }

    private StatementLineage merge(Statement st, ParserRuleContext node) {
        ObjectRef target = target(st, Access.MERGE);
        if (target == null) {
            return null;
        }
        Owner owner = tableOwner(target);
        Scope scope = new Scope(statementScope(node, null));
        collectSources(node, scope);
        List<ColumnNode> outputs = new ArrayList<>();
        for (String setRule : lr.setClauses()) {
            for (ParserRuleContext set : findAll(node, setRule)) {
                assign(set, scope, owner, outputs);
            }
        }
        if (lr.mergeInsert() != null) {
            for (ParserRuleContext ins : findAll(node, lr.mergeInsert())) {
                ParserRuleContext values = lr.valuesExpressionList() == null ? null : first(ins, lr.valuesExpressionList());
                if (values == null) {
                    continue;
                }
                ParserRuleContext holder = lr.insertColumnsParent() == null ? null : first(ins, lr.insertColumnsParent());
                if (holder == null) {
                    for (ParserRuleContext c : childRules(ins)) {
                        if (!isInside(values, c) && ir.ruleName(c).toLowerCase(Locale.ROOT).contains("column")) {
                            holder = c;
                            break;
                        }
                    }
                }
                List<String> cols = holder == null ? List.of() : columnNames(holder);
                List<ParserRuleContext> exprs = childRules(values);
                if (exprs.size() != cols.size()) {
                    // A grammar may model the row as one row-value expression; map plain columns by position.
                    List<ColumnNode> refs = refsIn(values, scope);
                    if (refs.size() == cols.size()) {
                        for (int i = 0; i < cols.size(); i++) {
                            ColumnNode to = owner.column(cols.get(i));
                            addOutput(outputs, to);
                            edges.add(ColumnEdge.direct(refs.get(i), to, Transform.PASS_THROUGH, Confidence.INFERRED,
                                    range(values)));
                        }
                    }
                    continue;
                }
                for (int i = 0; i < Math.min(cols.size(), exprs.size()); i++) {
                    ColumnNode to = owner.column(cols.get(i));
                    addOutput(outputs, to);
                    List<ColumnNode> froms = refsIn(exprs.get(i), scope);
                    Transform t = froms.size() == 1 && isPlainColumn(text(exprs.get(i)))
                            ? Transform.PASS_THROUGH : classify(exprs.get(i), froms.size());
                    for (ColumnNode from : froms) {
                        edges.add(ColumnEdge.direct(from, to, t, Confidence.INFERRED, range(exprs.get(i))));
                    }
                }
            }
        }
        indirect(node, scope, owner);
        return result(outputs);
    }

    /** T-SQL {@code UPDATE … SET @v = expr} and {@code SET @v = col = expr} (both take {@code expr}). */
    private void assignVariable(ParserRuleContext set, Scope scope, Owner owner, List<ColumnNode> outputs) {
        if (variables == null || set.getChildCount() < 3
                || !(set.getChild(0) instanceof org.antlr.v4.runtime.tree.TerminalNode target)
                || !target.getText().startsWith("@") || target.getText().startsWith("@@")) {
            return;
        }
        ColumnNode var = variable(target.getText());
        List<ParserRuleContext> parts = childRules(set);
        if (var == null || parts.isEmpty()) {
            return;
        }
        ParserRuleContext expr = parts.get(parts.size() - 1);
        if (parts.size() >= 2) {
            ColumnNode to = owner.column(lastPart(text(parts.get(0))));
            addOutput(outputs, to);
            List<ColumnNode> froms = refsIn(expr, scope);
            Transform t = froms.size() == 1 && isPlainColumn(text(expr)) ? Transform.PASS_THROUGH
                    : classify(expr, froms.size());
            for (ColumnNode from : froms) {
                edges.add(ColumnEdge.direct(from, to, t, Confidence.INFERRED, range(set)));
            }
        }
        flow(List.of(expr), var, "=", range(set), outputs, scope);
        assignedVars.add(var);
    }

    /** {@code target = expression}: the assignment's first rule child is the target column. */
    private void assign(ParserRuleContext set, Scope scope, Owner owner, List<ColumnNode> outputs) {
        ParserRuleContext col = childRules(set).isEmpty() ? null : childRules(set).get(0);
        if (col == null || text(set).strip().startsWith("@") || text(col).strip().startsWith("@")) {
            assignVariable(set, scope, owner, outputs);     // T-SQL UPDATE … SET @v = expr, not a column
            return;
        }
        String name = lastPart(text(col));
        ColumnNode to = owner.column(name);
        addOutput(outputs, to);
        List<ColumnNode> froms = new ArrayList<>();
        ParserRuleContext expr = null;
        for (int i = 0; i < set.getChildCount(); i++) {
            if (set.getChild(i) instanceof ParserRuleContext c && c != col) {
                froms.addAll(refsIn(c, scope));
                expr = expr == null ? c : expr;
            }
        }
        Transform t = classify(set, froms.size());
        if (froms.size() == 1 && expr != null && isPlainColumn(text(expr))) {
            t = Transform.PASS_THROUGH;
        }
        for (ColumnNode from : froms) {
            edges.add(ColumnEdge.direct(from, to, t, Confidence.INFERRED, range(set)));
        }
        ColumnNode rows = owner.column(ColumnNode.ROWS);
        for (ColumnNode n : nestedIndirect) {
            edges.add(ColumnEdge.indirect(n, rows, IndirectRole.FILTER, Confidence.INFERRED, range(set)));
        }
        nestedIndirect.clear();
    }

    /** {@code target = expr} from {@link LineageRules.Hooks#assignments}; a target list maps every column. */
    private void assignPair(ParserRuleContext target, ParserRuleContext expr, Scope scope, Owner owner,
                            List<ColumnNode> outputs) {
        List<ColumnNode> froms = refsIn(expr, scope);
        Transform t = froms.size() == 1 && isPlainColumn(text(expr)) ? Transform.PASS_THROUGH
                : classify(expr, froms.size());
        TextRange r = source.range(range(target).start().offset(), range(expr).end().offset());
        for (String col : text(target).replaceAll("[()]", "").split(",")) {
            if (col.isBlank()) {
                continue;
            }
            ColumnNode to = owner.column(lastPart(col.strip()));
            addOutput(outputs, to);
            for (ColumnNode from : froms) {
                edges.add(ColumnEdge.direct(from, to, t, Confidence.INFERRED, r));
            }
        }
        ColumnNode rows = owner.column(ColumnNode.ROWS);
        for (ColumnNode n : nestedIndirect) {
            edges.add(ColumnEdge.indirect(n, rows, IndirectRole.FILTER, Confidence.INFERRED, r));
        }
        nestedIndirect.clear();
    }

    private static void addOutput(List<ColumnNode> outputs, ColumnNode node) {
        if (outputs.stream().noneMatch(o -> o.key().equals(node.key()))) {
            outputs.add(node);
        }
    }

    private static final java.util.regex.Pattern INDEX_COLUMNS = java.util.regex.Pattern.compile("(?is)\\bON\\b[^(]*\\(([^)]*)\\)");

    /** {@code CREATE [UNIQUE] INDEX … ON t (a, b)}: adds the index to the table's metadata. */
    private void recordIndex(Statement st, ParserRuleContext node) {
        ObjectRef table = target(st, Access.ALTER);
        if (table == null) {
            return;
        }
        String text = text(node);
        java.util.regex.Matcher m = INDEX_COLUMNS.matcher(text);
        if (!m.find()) {
            return;
        }
        List<String> cols = new ArrayList<>();
        for (String c : m.group(1).split(",")) {
            String name = c.strip().split("\\s+")[0];
            if (!name.isEmpty()) {
                cols.add(lastPart(name));
            }
        }
        boolean unique = text.toUpperCase(Locale.ROOT).matches("(?s)\\s*CREATE\\s+UNIQUE\\b.*");
        var existing = catalog.meta(table.name().key(), table.name().simpleKey());
        List<String> parts = table.name().parts().stream().map(ObjectName.Part::text).toList();
        var base = existing.orElse(new com.github.amber.core.meta.TableMeta(null,
                parts.size() > 1 ? String.join(".", parts.subList(0, parts.size() - 1)) : null, parts.get(parts.size() - 1),
                "TABLE", List.of(), List.of(), List.of(), List.of(), -1, "script DDL"));
        List<com.github.amber.core.meta.TableMeta.IndexMeta> indexes = new ArrayList<>(base.indexes());
        indexes.add(new com.github.amber.core.meta.TableMeta.IndexMeta(st.name() == null ? null : st.name().toString(),
                cols, unique));
        catalog.add(new com.github.amber.core.meta.TableMeta(base.catalog(), base.schema(), base.name(), base.kind(),
                base.columns(), base.primaryKey(), base.uniqueKeys(), indexes, base.rowEstimate(), base.source(),
                base.foreignKeys()));
    }

    /** {@code ALTER TABLE t ADD [CONSTRAINT n] FOREIGN KEY (a) REFERENCES r (x)}: adds the key to t's metadata. */
    private void recordForeignKeys(Statement st, ParserRuleContext node) {
        ObjectRef table = target(st, Access.ALTER);
        if (table == null) {
            return;
        }
        var added = DdlText.tableForeignKeys(text(node), ir.dialect());
        if (added.isEmpty()) {
            return;
        }
        var existing = catalog.meta(table.name().key(), table.name().simpleKey());
        List<String> parts = table.name().parts().stream().map(ObjectName.Part::text).toList();
        var base = existing.orElse(new com.github.amber.core.meta.TableMeta(null,
                parts.size() > 1 ? String.join(".", parts.subList(0, parts.size() - 1)) : null, parts.get(parts.size() - 1),
                "TABLE", List.of(), List.of(), List.of(), List.of(), -1, "script DDL"));
        catalog.add(base.withForeignKeys(mergeForeignKeys(base.foreignKeys(), added)));
    }

    /** {@code have} plus the keys of {@code more} not already in it (same columns and referenced table). */
    private static List<com.github.amber.core.meta.TableMeta.ForeignKeyMeta> mergeForeignKeys(
            List<com.github.amber.core.meta.TableMeta.ForeignKeyMeta> have,
            List<com.github.amber.core.meta.TableMeta.ForeignKeyMeta> more) {
        List<com.github.amber.core.meta.TableMeta.ForeignKeyMeta> out = new ArrayList<>(have);
        Set<String> seen = new java.util.HashSet<>();
        for (var fk : have) {
            seen.add(fkKey(fk));
        }
        for (var fk : more) {
            if (seen.add(fkKey(fk))) {
                out.add(fk);
            }
        }
        return out;
    }

    private static String fkKey(com.github.amber.core.meta.TableMeta.ForeignKeyMeta fk) {
        return (fk.columns() + "->" + fk.refSchema() + "." + fk.refTable() + fk.refColumns()).toUpperCase(Locale.ROOT);
    }

    private void recordDdlColumns(Statement st, ParserRuleContext node) {
        ObjectRef target = target(st, Access.CREATE);
        if (target == null || lr.columnDefinition() == null) {
            return;
        }
        List<String> cols = new ArrayList<>();
        List<com.github.amber.core.meta.TableMeta.ColumnMeta> metas = new ArrayList<>();
        List<String> pk = new ArrayList<>();
        List<com.github.amber.core.meta.TableMeta.ForeignKeyMeta> fks = new ArrayList<>();
        for (ParserRuleContext def : findAll(node, lr.columnDefinition())) {
            ParserRuleContext name = lr.columnNameRule() == null ? null : first(def, lr.columnNameRule());
            String t = name != null ? text(name) : firstWord(def);
            if (t != null && !t.isBlank()) {
                ObjectName parsed = name == null ? null : ObjectName.parse(ir.dialect(), t.strip());
                // firstWord already returns a quoted name unquoted and whole ("my col")
                String col = name == null ? t : parsed != null ? parsed.simpleName() : lastPart(t);
                cols.add(col);
                String defText = text(def);
                metas.add(new com.github.amber.core.meta.TableMeta.ColumnMeta(col, DdlText.type(defText, t),
                        !DdlText.notNull(defText)));
                if (DdlText.inlinePrimaryKey(defText)) {
                    pk.add(col);
                }
                var fk = DdlText.inlineForeignKey(defText, col, ir.dialect());
                if (fk != null) {
                    fks.add(fk);
                }
            }
        }
        catalog.put(target.name().key(), target.name().simpleKey(), cols);
        if (!cols.isEmpty()) {
            String stmtText = text(node);
            if (pk.isEmpty()) {
                pk.addAll(DdlText.tablePrimaryKey(stmtText));
            }
            List<String> parts = target.name().parts().stream().map(ObjectName.Part::text).toList();
            catalog.add(new com.github.amber.core.meta.TableMeta(null,
                    parts.size() > 1 ? String.join(".", parts.subList(0, parts.size() - 1)) : null,
                    parts.get(parts.size() - 1), "TABLE", metas, pk, DdlText.uniqueKeys(stmtText), List.of(), -1, "script DDL",
                    mergeForeignKeys(fks, DdlText.tableForeignKeys(stmtText, ir.dialect()))));
        }
    }

    /** Edges from a query's outputs into an owner's columns (positional names when given). */
    private StatementLineage emit(QueryOut out, Owner owner, List<String> names) {
        List<ColumnNode> outputs = new ArrayList<>();
        for (int i = 0; i < out.outs.size(); i++) {
            Out o = out.outs.get(i);
            String name = names != null && i < names.size() ? names.get(i) : o.name;
            ColumnNode to = owner.column(name);
            outputs.add(to);
            for (ColumnNode from : o.sources) {
                edges.add(ColumnEdge.direct(from, to, o.transform, o.confidence, o.range));
            }
        }
        ColumnNode rows = owner.column(ColumnNode.ROWS);
        for (Indirect ind : out.indirect) {
            edges.add(ColumnEdge.indirect(ind.from, rows, ind.role, Confidence.INFERRED, ind.range));
        }
        return result(outputs);
    }

    // ----- queries --------------------------------------------------------------------------------

    private record Out(String name, Transform transform, List<ColumnNode> sources, TextRange range, Confidence confidence) {
    }

    private record Indirect(ColumnNode from, IndirectRole role, TextRange range) {
    }

    private record QueryOut(List<Out> outs, List<Indirect> indirect) {
        List<String> names() {
            return outs.stream().map(Out::name).toList();
        }
    }

    /** A data source visible in a query block. */
    private static final class Source {
        final String alias;            // upper-case
        final Owner owner;
        final ObjectName table;        // for tables
        final List<Out> outputs;       // for CTEs and derived tables

        Source(String alias, Owner owner, ObjectName table, List<Out> outputs) {
            this.alias = alias;
            this.owner = owner;
            this.table = table;
            this.outputs = outputs;
        }
    }

    private static final class Scope {
        final Scope parent;
        final List<Source> sources = new ArrayList<>();
        final Map<String, Source> ctes = new LinkedHashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        Source cte(String key) {
            for (Scope s = this; s != null; s = s.parent) {
                Source c = s.ctes.get(key);
                if (c != null) {
                    return c;
                }
            }
            return null;
        }
    }

    private QueryOut query(ParserRuleContext q, Scope outer) {
        Scope scope = new Scope(outer);
        if (lr.cteDefinition() != null) {
            for (ParserRuleContext def : cteDefinitions(q)) {
                cte(def, scope);
            }
        }
        List<ParserRuleContext> blocks = findAllAny(q, lr.queryBlocks(), lr.cteDefinition() == null ? Set.of()
                : Set.of(lr.cteDefinition()));
        if (blocks.isEmpty()) {
            QueryOut values = valuesQuery(q, scope);   // a VALUES query that is no block (Db2 values_clause)
            if (values != null) {
                return values;
            }
        }
        List<Out> outs = new ArrayList<>();
        List<Indirect> indirect = new ArrayList<>();
        boolean set = blocks.size() > 1;
        QueryBlock setBox = null;
        if (set) {
            java.util.LinkedHashSet<String> ops = new java.util.LinkedHashSet<>();
            for (int i = 1; i < blocks.size(); i++) {
                int a = range(blocks.get(i - 1)).end().offset();
                int b = range(blocks.get(i)).start().offset();
                java.util.regex.Matcher m = SET_OPERATOR.matcher(source.text().substring(Math.min(a, b), b));
                if (m.find()) {
                    ops.add(m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " "));
                }
            }
            setBox = openBox(QueryBlock.Kind.SET, ops.isEmpty() ? "UNION" : String.join(", ", ops), null, q);
        }
        try {
            for (ParserRuleContext block : blocks) {
                QueryOut b = block(block, scope);
            indirect.addAll(b.indirect);
            if (outs.isEmpty()) {
                outs.addAll(b.outs);
            } else {
                for (int i = 0; i < Math.min(outs.size(), b.outs.size()); i++) {
                    Out o = outs.get(i);
                    List<ColumnNode> merged = new ArrayList<>(o.sources);
                    merged.addAll(b.outs.get(i).sources);
                    Transform t = o.transform == b.outs.get(i).transform ? o.transform : Transform.EXPRESSION;
                    outs.set(i, new Out(o.name, t, merged, o.range, o.confidence));
                }
            }
            }
        } finally {
            if (set) {
                closeBox();
            }
        }
        // ORDER BY / LIMIT / FETCH written on the query root (outside every block) belong to the whole query.
        if (!lr.queryBlocks().contains(ir.ruleName(q)) && !blocks.isEmpty()) {
            List<org.antlr.v4.runtime.tree.TerminalNode> tokens = clauseTokens(q, q, true);
            QueryBlock target = setBox != null ? setBox : boxOf.get(blocks.get(blocks.size() - 1));
            if (target != null && !tokens.isEmpty()) {
                for (String label : clauseLabels(counts(tokens))) {
                    if (!target.clauses().contains(label)) {
                        target.addClause(label);
                    }
                }
                int before = target.phases().size();
                addPhases(target, tokens, range(q).end().offset());
                target.dedupePhases(before);
            }
        }
        return new QueryOut(outs, indirect);
    }

    /**
     * CTE definitions in source order, including those a grammar nests in the previous definition
     * ({@code cte : name AS (query) (',' cte)?} in MariaDB).
     */
    private List<ParserRuleContext> cteDefinitions(ParserRuleContext node) {
        List<ParserRuleContext> out = new ArrayList<>();
        for (ParserRuleContext def : findAll(node, lr.cteDefinition(), lr.queryBlocks())) {
            addCteDefinition(def, out);
        }
        return out;
    }

    private void addCteDefinition(ParserRuleContext def, List<ParserRuleContext> out) {
        out.add(def);
        for (ParserRuleContext c : childRules(def)) {
            if (ir.ruleName(c).equals(lr.cteDefinition())) {
                addCteDefinition(c, out);
            }
        }
    }

    private void cte(ParserRuleContext def, Scope scope) {
        ParserRuleContext nameNode = first(def, lr.cteNameRule());
        if (nameNode == null) {
            return;
        }
        String name = firstWord(nameNode);
        ParserRuleContext body = firstQuery(def);
        openBox(QueryBlock.Kind.CTE, name, null, def);
        try {
            cteBody(def, nameNode, name, body, scope);
        } finally {
            closeBox();
        }
    }

    private void cteBody(ParserRuleContext def, ParserRuleContext nameNode, String name, ParserRuleContext body,
                         Scope scope) {
        if (body == null) {
            // Data-modifying CTE (WITH x AS (DELETE … RETURNING …)): still a CTE, columns unknown.
            Owner owner = new Owner(OwnerKind.CTE, name, "cte:" + name.toUpperCase(Locale.ROOT) + "@" + offset(def));
            scope.ctes.put(name.toUpperCase(Locale.ROOT), new Source(name.toUpperCase(Locale.ROOT), owner, null, List.of()));
            return;
        }
        // A recursive CTE reads itself: register it before its body so the self-reference is not a table.
        Owner self = new Owner(OwnerKind.CTE, name, "cte:" + name.toUpperCase(Locale.ROOT) + "@" + offset(def));
        scope.ctes.putIfAbsent(name.toUpperCase(Locale.ROOT), new Source(name.toUpperCase(Locale.ROOT), self, null, List.of()));
        QueryOut out = query(body, scope);
        List<String> colNames = new ArrayList<>();
        if (lr.cteColumnRule() != null) {
            for (ParserRuleContext c : findAll(def, lr.cteColumnRule(), lr.queryBlocks())) {
                if (c != nameNode && !isInside(c, body)) {
                    colNames.add(lastPart(text(c)));
                }
            }
        }
        Owner owner = new Owner(OwnerKind.CTE, name, "cte:" + name.toUpperCase(Locale.ROOT) + "@" + offset(def));
        List<Out> outputs = new ArrayList<>();
        for (int i = 0; i < out.outs.size(); i++) {
            Out o = out.outs.get(i);
            String colName = i < colNames.size() ? colNames.get(i) : o.name;
            ColumnNode to = owner.column(colName);
            for (ColumnNode from : o.sources) {
                if (!from.equals(to)) {                   // a recursive CTE's column feeding itself
                    edges.add(ColumnEdge.direct(from, to, o.transform, o.confidence, o.range));
                }
            }
            outputs.add(new Out(colName, Transform.PASS_THROUGH, List.of(to), o.range, o.confidence));
        }
        ColumnNode rows = owner.column(ColumnNode.ROWS);
        for (Indirect ind : out.indirect) {
            edges.add(ColumnEdge.indirect(ind.from, rows, ind.role, Confidence.INFERRED, ind.range));
        }
        scope.ctes.put(name.toUpperCase(Locale.ROOT), new Source(name.toUpperCase(Locale.ROOT), owner, null, outputs));
    }

    private QueryOut block(ParserRuleContext block, Scope outer) {
        QueryBlock box = openBox(QueryBlock.Kind.QUERY, null, null, block);
        boxOf.putIfAbsent(block, box);
        try {
            Scope scope = new Scope(outer);
            QueryOut out = blockBody(block, scope);
            describe(box, block, scope, out.outs);
            return out;
        } finally {
            closeBox();
        }
    }

    private QueryOut blockBody(ParserRuleContext block, Scope scope) {
        ParserRuleContext from = lr.fromClause() == null ? null : first(block, lr.fromClause());
        if (from != null) {
            collectSources(from, scope);
        } else if (lr.fromClause() == null) {
            collectSources(block, scope);
        }
        List<Out> outs = new ArrayList<>();
        List<Indirect> indirect = new ArrayList<>();
        List<ParserRuleContext> items = findAll(block, lr.selectItem(), lr.queryBlocks());
        if (items.isEmpty() && lr.hooks().selectsAll(block, text(block))) {
            expandStar("", scope, outs, range(block));
        } else if (items.isEmpty()) {
            QueryOut values = valuesQuery(block, scope);   // VALUES (…) as a query block (PostgreSQL)
            if (values != null) {
                return values;
            }
        }
        for (ParserRuleContext item : items) {
            selectItem(item, scope, outs);
            drainNested(indirect, IndirectRole.FILTER, range(item));
        }
        addIndirect(block, scope, indirect, from);
        for (ParserRuleContext f : lr.hooks().filterNodes(block)) {
            refsIn(f, scope).forEach(n -> indirect.add(new Indirect(n, IndirectRole.FILTER, range(f))));
            drainNested(indirect, IndirectRole.FILTER, range(f));
        }
        for (ParserRuleContext g : lr.hooks().groupNodes(block)) {
            refsIn(g, scope).forEach(n -> indirect.add(new Indirect(n, IndirectRole.GROUP, range(g))));
        }
        return new QueryOut(outs, indirect);
    }

    private void selectItem(ParserRuleContext item, Scope scope, List<Out> outs) {
        String itemText = text(item);
        String star = lr.hooks().starQualifier(item, itemText);
        if (star != null) {
            expandStar(star, scope, outs, range(item));
            return;
        }
        if (assigningItem(item, scope, outs)) {
            return;
        }
        ParserRuleContext aliasNode = lr.hooks().selectAlias(item);
        if (aliasNode == null) {
            aliasNode = aliasAtEdge(item);
        }
        List<ColumnNode> refs = refsIn(item, scope);
        String exprText = aliasNode == null ? itemText
                : range(aliasNode).start().offset() == range(item).start().offset()
                ? itemText.substring(itemText.indexOf('=') + 1)
                : text(item, aliasNode);
        String name;
        if (aliasNode != null) {
            name = aliasName(text(aliasNode));
        } else if (isPlainColumn(exprText)) {
            name = lastPart(exprText.strip());
        } else {
            name = exprText.strip().replaceAll("\\s+", " ");
            if (name.length() > 40) {
                name = name.substring(0, 37) + "…";
            }
        }
        Transform t = refs.isEmpty() ? Transform.CONSTANT : classify(item, refs.size());
        if (t == Transform.EXPRESSION && refs.size() == 1 && isPlainColumn(exprText)) {
            t = Transform.PASS_THROUGH;
        }
        Confidence c = refs.stream().anyMatch(r -> r.ownerKind() == OwnerKind.UNKNOWN) ? Confidence.INFERRED : Confidence.INFERRED;
        outs.add(new Out(name, t, refs, range(item), c));
    }

    /**
     * The select item's alias: an alias-rule node at the end of the item ({@code expr AS a}) or at its
     * start ({@code a = expr}). Alias-rule nodes nested inside the expression (PostgreSQL reuses
     * {@code colLabel} for the column part of {@code t.col}) are not aliases.
     */
    private ParserRuleContext aliasAtEdge(ParserRuleContext item) {
        TextRange itemRange = range(item);
        ParserRuleContext atStart = null;
        for (String aliasRule : lr.selectAliases()) {
            for (ParserRuleContext a : findAll(item, aliasRule, lr.queryBlocks())) {
                TextRange r = range(a);
                if (r.end().offset() == itemRange.end().offset() && r.start().offset() > itemRange.start().offset()) {
                    return a;
                }
                if (r.start().offset() == itemRange.start().offset() && r.end().offset() < itemRange.end().offset()
                        && text(item).substring(r.length()).strip().startsWith("=")) {
                    atStart = a;
                }
            }
        }
        return atStart;
    }

    private void expandStar(String qualifier, Scope scope, List<Out> outs, TextRange range) {
        for (Source s : scope.sources) {
            if (!qualifier.isEmpty() && !matches(s, qualifier)) {
                continue;
            }
            if (s.outputs != null) {
                for (Out o : s.outputs) {
                    outs.add(new Out(o.name, Transform.PASS_THROUGH, List.of(s.owner.column(o.name)), range, Confidence.CERTAIN));
                }
            } else {
                List<String> cols = catalog.columns(s.table.key(), s.table.simpleKey()).orElse(null);
                if (cols != null) {
                    for (String c : cols) {
                        outs.add(new Out(c, Transform.PASS_THROUGH, List.of(s.owner.column(c)), range, Confidence.CERTAIN));
                    }
                } else {
                    outs.add(new Out("*", Transform.PASS_THROUGH, List.of(s.owner.column("*")), range, Confidence.INFERRED));
                }
            }
        }
    }

    private void addIndirect(ParserRuleContext block, Scope scope, List<Indirect> out, ParserRuleContext from) {
        addIndirect(block, lr.filterClauses(), IndirectRole.FILTER, scope, out, from);
        addIndirect(block, lr.groupClauses(), IndirectRole.GROUP, scope, out, null);
        addIndirect(from != null ? from : block, lr.joinClauses(), IndirectRole.JOIN, scope, out, null);
    }

    private void addIndirect(ParserRuleContext root, Set<String> rules, IndirectRole role, Scope scope, List<Indirect> out) {
        addIndirect(root, rules, role, scope, out, null);
    }

    /** @param exclude a subtree whose clauses belong elsewhere (join conditions inside FROM) */
    private void addIndirect(ParserRuleContext root, Set<String> rules, IndirectRole role, Scope scope, List<Indirect> out,
                             ParserRuleContext exclude) {
        if (rules == null) {
            return;
        }
        for (String rule : rules) {
            for (ParserRuleContext clause : findAll(root, rule, lr.queryBlocks())) {
                if (exclude != null && isInside(clause, exclude)) {
                    continue;
                }
                for (ColumnNode n : refsIn(clause, scope)) {
                    out.add(new Indirect(n, role, range(clause)));
                }
                drainNested(out, role, range(clause));
            }
        }
    }

    private void drainNested(List<Indirect> out, IndirectRole role, TextRange range) {
        for (ColumnNode n : nestedIndirect) {
            out.add(new Indirect(n, role, range));
        }
        nestedIndirect.clear();
    }

    /** Indirect edges for UPDATE / MERGE / DELETE statements, into the target's rows. */
    private void indirect(ParserRuleContext node, Scope scope, Owner owner) {
        List<Indirect> list = new ArrayList<>();
        addIndirect(node, lr.filterClauses(), IndirectRole.FILTER, scope, list);
        addIndirect(node, lr.joinClauses(), IndirectRole.JOIN, scope, list);
        for (ParserRuleContext f : lr.hooks().filterNodes(node)) {
            refsIn(f, scope).forEach(n -> list.add(new Indirect(n, IndirectRole.FILTER, range(f))));
            drainNested(list, IndirectRole.FILTER, range(f));
        }
        ColumnNode rows = owner.column(ColumnNode.ROWS);
        for (Indirect ind : list) {
            edges.add(ColumnEdge.indirect(ind.from, rows, ind.role, Confidence.INFERRED, ind.range));
        }
    }

    // ----- sources --------------------------------------------------------------------------------

    private void collectSources(ParserRuleContext node, Scope scope) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof org.antlr.v4.runtime.tree.TerminalNode token && ir.nameToken(token) != null) {
                ObjectName name = ObjectName.parse(ir.dialect(), token.getText());   // T-SQL FROM @t
                if (name != null) {
                    String alias = aliasNear(token);
                    scope.sources.add(new Source((alias != null ? alias : name.simpleName()).toUpperCase(Locale.ROOT),
                            tableOwner(name), name, null));
                }
                continue;
            }
            if (!(node.getChild(i) instanceof ParserRuleContext c)) {
                continue;
            }
            String rule = ir.ruleName(c);
            if (isExpressionContext(rule)) {
                continue;                     // subqueries here are scalar/IN/EXISTS, not data sources
            }
            if (lr.queryRoots().contains(rule) || lr.queryBlocks().contains(rule)) {
                String alias = aliasNear(c);
                openBox(QueryBlock.Kind.DERIVED, alias, "FROM", c);
                QueryOut sub;
                try {
                    sub = query(c, scope);
                } finally {
                    closeBox();
                }
                String display = alias == null ? "subquery" : alias;
                Owner owner = new Owner(OwnerKind.SUBQUERY, display, "sub:" + display.toUpperCase(Locale.ROOT) + "@" + offset(c));
                List<Out> outs = new ArrayList<>();
                for (Out o : sub.outs) {
                    ColumnNode to = owner.column(o.name);
                    for (ColumnNode from : o.sources) {
                        edges.add(ColumnEdge.direct(from, to, o.transform, o.confidence, o.range));
                    }
                    outs.add(new Out(o.name, Transform.PASS_THROUGH, List.of(to), o.range, o.confidence));
                }
                ColumnNode rows = owner.column(ColumnNode.ROWS);
                for (Indirect ind : sub.indirect) {
                    edges.add(ColumnEdge.indirect(ind.from, rows, ind.role, Confidence.INFERRED, ind.range));
                }
                scope.sources.add(new Source(alias == null ? "" : alias.toUpperCase(Locale.ROOT), owner, null, outs));
                continue;
            }
            if (ir.isNameRule(rule) && ir.acceptName(c)) {
                ObjectName name = ObjectName.parse(ir.dialect(), qualifiedText(c));
                if (name == null) {
                    continue;
                }
                if (name.parts().size() == 1 && scope.cte(name.simpleName().toUpperCase(Locale.ROOT)) == null
                        && statementRefs.stream().noneMatch(r -> r.name().sameObject(name))) {
                    continue;                         // an alias used as a DML target (UPDATE s … FROM t s)
                }
                String alias = aliasNear(c);
                Source cte = name.parts().size() == 1 ? scope.cte(name.simpleName().toUpperCase(Locale.ROOT)) : null;
                String aliasKey = (alias != null ? alias : name.simpleName()).toUpperCase(Locale.ROOT);
                if (cte != null) {
                    scope.sources.add(new Source(aliasKey, cte.owner, null, cte.outputs));
                } else {
                    scope.sources.add(new Source(aliasKey, tableOwner(name), name, null));
                }
                continue;
            }
            collectSources(c, scope);
        }
    }

    private boolean isExpressionContext(String rule) {
        // Join clauses are not skipped: in some grammars (T-SQL join_on) they contain the joined table.
        return lr.setClauses().contains(rule) || (lr.filterClauses() != null && lr.filterClauses().contains(rule))
                || rule.equals(lr.mergeInsert()) || rule.equals(lr.selectItem()) || lr.hooks().isExpression(rule);
    }

    private Owner tableOwner(ObjectRef ref) {
        return new Owner(ownerKind(ref.kind()), ref.name().toString(), ref.name().key());
    }

    private Owner tableOwner(ObjectName name) {
        ObjectKind kind = ObjectKind.TABLE;
        for (ObjectRef r : statementRefs) {
            if (r.name().sameObject(name)) {
                kind = r.kind();
                break;
            }
        }
        return new Owner(ownerKind(kind), name.toString(), name.key());
    }

    private static OwnerKind ownerKind(ObjectKind kind) {
        return switch (kind) {
            case TEMP_TABLE -> OwnerKind.TEMP_TABLE;
            case TABLE_VARIABLE -> OwnerKind.TABLE_VARIABLE;
            case VIEW -> OwnerKind.VIEW;
            case MATERIALIZED_VIEW -> OwnerKind.MATERIALIZED_VIEW;
            default -> OwnerKind.TABLE;
        };
    }

    // ----- column references ------------------------------------------------------------------------

    /** Resolved column references inside {@code node}; nested queries contribute their sources. */
    private List<ColumnNode> refsIn(ParserRuleContext node, Scope scope) {
        List<ColumnNode> out = new ArrayList<>();
        refsIn(node, scope, out);
        return out;
    }

    private void refsIn(ParserRuleContext node, Scope scope, List<ColumnNode> out) {
        String rule = ir.ruleName(node);
        if (lr.queryRoots().contains(rule) || lr.queryBlocks().contains(rule)) {
            openBox(QueryBlock.Kind.SUBQUERY, null, subqueryRole(node), node);
            QueryOut sub;
            try {
                sub = query(node, scope);
            } finally {
                closeBox();
            }
            for (Out o : sub.outs) {
                out.addAll(o.sources);
            }
            for (Indirect ind : sub.indirect) {
                nestedIndirect.add(ind.from);
            }
            return;
        }
        Call call = callAt(node);
        if (call != null && !activeCalls.contains(call.open())) {
            ColumnNode read = callRead(call, node, scope);
            if (read != null) {
                out.add(read);
                activeCalls.add(call.open());
                try {
                    childRefs(node, scope, out);
                } finally {
                    activeCalls.remove(call.open());
                }
                return;
            }
        }
        if (lr.columnRefs().contains(rule)) {
            String t = text(node).strip();
            if (isDatePartArgument(node, t)) {
                return;                               // DATEADD(day, …), TIMESTAMPDIFF(HOUR, …), EXTRACT(YEAR FROM …)
            }
            if (isCallPrefix(node)) {
                return;                               // dw.f_total(…), pkg.fn(…): a routine name, not a column
            }
            if (lr.hooks().isColumnRef(node, t)) {
                ColumnNode n = resolve(t, scope);
                if (n != null) {
                    out.add(n);
                }
                return;
            }
        }
        childRefs(node, scope, out);
    }

    private void childRefs(ParserRuleContext node, Scope scope, List<ColumnNode> out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof ParserRuleContext c) {
                refsIn(c, scope, out);
            } else if (variables != null && node.getChild(i) instanceof org.antlr.v4.runtime.tree.TerminalNode t) {
                ColumnNode v = prefixedVariable(t.getText());     // T-SQL @v, MySQL @v, PostgreSQL $1
                if (v != null) {
                    out.add(v);
                }
            }
        }
    }

    private static final Set<String> DATE_PART_FUNCTIONS = Set.of("TIMESTAMPDIFF", "TIMESTAMPADD", "DATEADD",
            "DATEDIFF", "DATEDIFF_BIG", "DATEPART", "DATENAME", "DATETRUNC", "EXTRACT", "DATE_PART");
    private static final java.util.regex.Pattern DATE_PART_CALL = java.util.regex.Pattern.compile(
            "(?i)\\b([A-Z_]+)\\s*\\(\\s*$");

    /**
     * Whether the reference is (the start of) a routine call: the source right after it continues with
     * {@code (} or with {@code .name(} — e.g. {@code dw.f_total(sysdate)} parsed with {@code dw} as a reference.
     */
    private boolean isCallPrefix(ParserRuleContext node) {
        String text = source.text();
        int i = range(node).end().offset();
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length()) {
            return false;
        }
        if (text.charAt(i) == '(') {
            return true;
        }
        if (text.charAt(i) != '.') {
            return false;
        }
        java.util.regex.Matcher m = CALL_REST.matcher(text).region(i, text.length());
        return m.lookingAt();
    }

    private static final java.util.regex.Pattern CALL_REST = java.util.regex.Pattern.compile(
            "(\\s*\\.\\s*[\\p{L}_\"][\\p{L}\\p{N}_$#\"]*)+\\s*\\(");

    /** A bare unit word that is the first argument of a date function is not a column. */
    private boolean isDatePartArgument(ParserRuleContext node, String text) {
        if (text.contains(".") || !text.chars().allMatch(c -> Character.isLetter(c) || c == '_')) {
            return false;
        }
        int start = range(node).start().offset();
        String before = source.text().substring(Math.max(0, start - 30), start);
        java.util.regex.Matcher m = DATE_PART_CALL.matcher(before);
        return m.find() && DATE_PART_FUNCTIONS.contains(m.group(1).toUpperCase(Locale.ROOT));
    }

    private ColumnNode resolve(String refText, Scope scope) {
        if (!refText.isEmpty() && (refText.charAt(0) == '@' || refText.charAt(0) == '$')) {
            return variables == null ? null : prefixedVariable(refText);
        }
        ObjectName ref = ObjectName.parse(ir.dialect(), refText);
        if (ref == null) {
            return null;
        }
        String column = ref.simpleName();
        if (!ref.parts().get(ref.parts().size() - 1).quoted() && lr.pseudoColumns().contains(column.toUpperCase(Locale.ROOT))) {
            return null;                      // a quoted "LEVEL" is a real column, LEVEL is the pseudo-column
        }
        char c0 = refText.charAt(0);
        if (c0 == '&' || c0 == ':' || c0 == '@' || c0 == '?' || c0 == '$') {
            return null;                      // substitution, bind and host variables are not columns
        }
        if (ref.parts().size() > 1) {
            String qualifier = String.join(".", ref.parts().subList(0, ref.parts().size() - 1).stream()
                    .map(ObjectName.Part::text).toList());
            for (Scope s = scope; s != null; s = s.parent) {
                for (Source src : s.sources) {
                    if (matches(src, qualifier)) {
                        return columnOf(src, column);
                    }
                }
            }
            if (ref.parts().size() == 2) {
                ColumnNode field = variables == null ? null
                        : variables.record(varScope, ref.parts().get(0).text(), column);     // FOR r IN (…) LOOP
                return field != null ? field : variable(ref.parts().get(0).text());     // r.field of a record variable
            }
            return null;                     // package variable, sequence.nextval …
        }
        // A known column wins over a variable of the same name; a declared variable wins over a column
        // the metadata does not know.
        for (Scope s = scope; s != null; s = s.parent) {
            List<Source> candidates = new ArrayList<>();
            for (Source src : s.sources) {
                if (hasColumn(src, column)) {
                    candidates.add(src);
                }
            }
            if (candidates.size() == 1) {
                return columnOf(candidates.get(0), column);
            }
            if (candidates.size() > 1) {
                return new ColumnNode(OwnerKind.UNKNOWN, "?", "?", column);
            }
            if (!s.sources.isEmpty()) {
                ColumnNode v = variable(column);
                if (v != null) {
                    return v;
                }
                if (s.sources.size() == 1) {
                    return columnOf(s.sources.get(0), column);
                }
                Source owner = byEvidence(s.sources, column);
                return owner != null ? columnOf(owner, column) : new ColumnNode(OwnerKind.UNKNOWN, "?", "?", column);
            }
        }
        return variable(column);
    }

    /**
     * The only source the script's own evidence shows to have {@code column}, when no metadata places it
     * (see {@link com.github.amber.core.lineage.ColumnEvidence}); null otherwise. Sources whose columns are
     * known (DDL, metadata, CTE and derived outputs) do not have it and take no part; a CTE or derived table
     * with unknown columns makes the answer null. The edges keep their INFERRED confidence.
     */
    private Source byEvidence(List<Source> sources, String column) {
        var evidence = catalog.evidence();
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        List<Source> open = new ArrayList<>();
        List<ObjectName> names = new ArrayList<>();
        for (Source src : sources) {
            if (src.outputs != null) {
                if (src.outputs.stream().noneMatch(o -> "*".equals(o.name))) {
                    continue;                           // known columns, without this one
                }
                open.add(src);
                names.add(null);
            } else if (src.table == null) {
                open.add(src);
                names.add(null);
            } else if (catalog.columns(src.table.key(), src.table.simpleKey()).isEmpty()) {
                open.add(src);
                names.add(src.table);
            }
        }
        var choice = evidence.choose(names, column);
        return choice == null ? null : open.get(choice.index());
    }

    private boolean hasColumn(Source src, String column) {
        if (src.outputs != null) {
            return src.outputs.stream().anyMatch(o -> o.name.equalsIgnoreCase(column));
        }
        return catalog.hasColumn(src.table.key(), src.table.simpleKey(), column);
    }

    private ColumnNode columnOf(Source src, String column) {
        if (src.outputs != null) {
            for (Out o : src.outputs) {
                if (o.name.equalsIgnoreCase(column)) {
                    return src.owner.column(o.name);
                }
            }
        }
        return src.owner.column(column);
    }

    private static boolean matches(Source src, String qualifier) {
        String q = qualifier.toUpperCase(Locale.ROOT).replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
        if (src.alias.equals(q)) {
            return true;
        }
        if (src.table != null) {
            return src.table.key().toUpperCase(Locale.ROOT).equals(q) || src.table.simpleKey().toUpperCase(Locale.ROOT).equals(q);
        }
        return false;
    }

    // ----- variables ----------------------------------------------------------------------------------

    /** A known variable or parameter named {@code name} in the statement's scope, else null. */
    private ColumnNode variable(String name) {
        return variables == null ? null : variables.lookup(varScope, name);
    }

    /** {@code @v} (T-SQL locals, MySQL user variables) and PostgreSQL {@code $n} parameters; else null. */
    private ColumnNode prefixedVariable(String text) {
        String t = text.strip();
        if (t.length() > 1 && t.charAt(0) == '@' && t.charAt(1) != '@'
                && variables.rules().atNames() != VariableRules.AtNames.NONE) {
            return variables.lookup(varScope, t);
        }
        if (t.length() > 1 && t.charAt(0) == '$' && t.substring(1).chars().allMatch(Character::isDigit)) {
            return variables.positional(varScope, Integer.parseInt(t.substring(1)));
        }
        return null;
    }

    private static ColumnNode rowsOf(ColumnNode n) {
        return new ColumnNode(n.ownerKind(), n.owner(), n.ownerKey(), ColumnNode.ROWS);
    }

    /** T-SQL {@code SELECT @v = expr}: the item assigns {@code @v} instead of producing a column. */
    private boolean assigningItem(ParserRuleContext item, Scope scope, List<Out> outs) {
        if (variables == null || variables.rules().atNames() == VariableRules.AtNames.NONE || item.getChildCount() < 3
                || !(item.getChild(0) instanceof org.antlr.v4.runtime.tree.TerminalNode target)
                || !target.getText().startsWith("@") || target.getText().startsWith("@@")
                || !isAssignmentOperator(item.getChild(1))) {
            return false;
        }
        List<ColumnNode> refs = new ArrayList<>();
        ParserRuleContext expr = null;
        for (int i = 2; i < item.getChildCount(); i++) {
            if (item.getChild(i) instanceof ParserRuleContext c) {
                refsIn(c, scope, refs);
                expr = expr == null ? c : expr;
            }
        }
        Transform t = expr == null ? Transform.CONSTANT : classify(expr, refs.size());
        if (t == Transform.EXPRESSION && refs.size() == 1 && isPlainColumn(text(expr))
                && textOf(item.getChild(1)).strip().equals("=")) {
            t = Transform.PASS_THROUGH;
        }
        Out o = new Out(target.getText(), t, refs, range(item), Confidence.INFERRED);
        outs.add(o);
        assignedItems.put(o, target.getText());
        return true;
    }

    private boolean isAssignmentOperator(ParseTree node) {
        String t = textOf(node).strip();
        return t.equals("=") || (t.length() == 2 && t.charAt(1) == '=' && "+-*/%&|^:".indexOf(t.charAt(0)) >= 0);
    }

    /**
     * The variables a SELECT assigns, by output position ({@code null} where a position assigns
     * nothing): its {@code INTO v1, v2} clause or its {@code @v = expr} items. Null for a plain query.
     */
    private List<ColumnNode> selectTargets(ParserRuleContext node, QueryOut out) {
        ParserRuleContext into = firstOf(node, variables.rules().intoClauses());
        List<ColumnNode> vars = new ArrayList<>();
        if (into != null) {
            for (String name : VariableScopes.intoNames(text(into))) {
                vars.add(name == null ? null : intoVariable(name));
            }
            return vars.isEmpty() ? null : vars;
        }
        boolean any = false;
        for (Out o : out.outs) {
            String name = assignedItems.get(o);
            vars.add(name == null ? null : variable(name));
            any |= name != null;
        }
        return any ? vars : null;
    }

    /** An INTO / FETCH target: always a variable, declared or not. */
    private ColumnNode intoVariable(String name) {
        String n = VariableScopes.targetName(name, varScope);
        ColumnNode v = variable(n);
        if (v == null) {
            varScope.addTarget(n);
            v = variable(n);
        }
        return v;
    }

    /** Edges from a query's outputs into variables by position (one variable takes every column: a record). */
    private StatementLineage emitVariables(QueryOut out, List<ColumnNode> vars) {
        List<ColumnNode> outputs = new ArrayList<>();
        for (int i = 0; i < out.outs.size(); i++) {
            ColumnNode to = vars.size() == 1 ? vars.get(0) : i < vars.size() ? vars.get(i) : null;
            if (to == null) {
                continue;
            }
            addOutput(outputs, to);
            Out o = out.outs.get(i);
            for (ColumnNode from : o.sources) {
                if (!from.key().equals(to.key())) {
                    edges.add(ColumnEdge.direct(from, to, o.transform, o.confidence, o.range));
                }
            }
        }
        for (ColumnNode v : outputs) {
            ColumnNode rows = rowsOf(v);
            for (Indirect ind : out.indirect) {
                edges.add(ColumnEdge.indirect(ind.from, rows, ind.role, Confidence.INFERRED, ind.range));
            }
        }
        return result(outputs);
    }

    /**
     * Variable writes of a procedural node (an assignment or DECLARE statement, a FETCH, or a container
     * whose assignments and declarations are no statements of their own); child statements are skipped.
     */
    private StatementLineage procedural(ParserRuleContext node) {
        if (variables == null) {
            return null;
        }
        List<ColumnNode> outputs = new ArrayList<>();
        StatementKind kind = ir.kindOf(node);
        procedural(node, kind != null && kind.isContainer(), outputs);
        return outputs.isEmpty() && edges.isEmpty() ? null : result(outputs);
    }

    /** @param container whether {@code n} is in a container, whose nested statement nodes are statements of their own */
    private void procedural(ParserRuleContext n, boolean container, List<ColumnNode> outputs) {
        VariableRules vr = variables.rules();
        String rule = ir.ruleName(n);
        if (vr.declarations().contains(rule)) {
            declaration(n, outputs);
            return;
        }
        if (vr.assignments().contains(rule)) {
            assignment(n, outputs);
            return;
        }
        if (vr.fetches().contains(rule)) {
            fetch(n, outputs);
            return;
        }
        if (vr.returns().contains(rule)) {
            returnValue(n, outputs);
            return;
        }
        for (ParserRuleContext c : childRules(n)) {
            if (!container || ir.kindOf(c) == null) {  // a child statement has its own lineage
                procedural(c, container, outputs);
            }
        }
    }

    /** {@code RETURN expr}: the expression flows into the function's {@code return} variable. */
    private void returnValue(ParserRuleContext n, List<ColumnNode> outputs) {
        ColumnNode to = VariableScopes.returnNode(varScope);
        if (to == null) {
            return;
        }
        boolean after = false;
        for (int i = 0; i < n.getChildCount(); i++) {
            ParseTree c = n.getChild(i);
            if (c instanceof org.antlr.v4.runtime.tree.TerminalNode t) {
                after |= t.getText().equalsIgnoreCase("RETURN");
            } else if (after && c instanceof ParserRuleContext expr) {
                flow(List.of(expr), to, "=", range(n), outputs);
                return;
            }
        }
    }

    /** {@code v type := expr}, {@code DECLARE v INT DEFAULT expr}, {@code DECLARE @v int = expr}. */
    private void declaration(ParserRuleContext n, List<ColumnNode> outputs) {
        List<String> names = VariableScopes.declaredNames(text(n));
        List<ParserRuleContext> value = valueAfterOperator(n);
        if (names.isEmpty() || value == null) {
            return;
        }
        for (String name : names) {
            ColumnNode to = variable(name);
            if (to != null) {
                flow(value, to, "=", range(n), outputs);
            }
        }
    }

    /** The expression after the first {@code :=}, {@code =} or {@code DEFAULT} of a declaration, or null. */
    private List<ParserRuleContext> valueAfterOperator(ParserRuleContext n) {
        for (int i = 0; i < n.getChildCount(); i++) {
            ParseTree c = n.getChild(i);
            if (c instanceof org.antlr.v4.runtime.tree.TerminalNode t) {
                String w = t.getText();
                if (w.equals(":=") || w.equals("=") || w.equalsIgnoreCase("DEFAULT")) {
                    List<ParserRuleContext> after = new ArrayList<>();
                    for (int j = i + 1; j < n.getChildCount(); j++) {
                        if (n.getChild(j) instanceof ParserRuleContext e) {
                            after.add(e);
                        }
                    }
                    return after.isEmpty() ? null : after;
                }
            } else if (c instanceof ParserRuleContext r && !lr.queryRoots().contains(ir.ruleName(r))) {
                List<ParserRuleContext> found = valueAfterOperator(r);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** {@code v := expr}, {@code SET v = expr, w = expr2}, {@code SET @v += expr}. */
    private void assignment(ParserRuleContext n, List<ColumnNode> outputs) {
        for (VariableScopes.Pair p : VariableScopes.pairs(n, this::textOf)) {
            TextRange r = source.range(rangeOf(p.target()).start().offset(), range(p.expression()).end().offset());
            for (String name : VariableScopes.targetNames(textOf(p.target()), varScope)) {
                ColumnNode to = variable(name);
                if (to != null) {
                    flow(List.of(p.expression()), to, p.operator(), r, outputs);
                }
            }
        }
    }

    /** Direct edges from the columns and variables an expression reads into variable {@code to}. */
    private void flow(List<ParserRuleContext> exprs, ColumnNode to, String operator, TextRange r, List<ColumnNode> outputs) {
        flow(exprs, to, operator, r, outputs, new Scope(null));
    }

    /** {@link #flow} with the data sources of the enclosing statement ({@code UPDATE … SET @v = col}). */
    private void flow(List<ParserRuleContext> exprs, ColumnNode to, String operator, TextRange r, List<ColumnNode> outputs,
                      Scope scope) {
        List<ColumnNode> froms = new ArrayList<>();
        for (ParserRuleContext e : exprs) {
            refsIn(e, scope, froms);
        }
        ParserRuleContext expr = exprs.get(0);
        Transform t = froms.size() == 1 && exprs.size() == 1 && isPlainColumn(text(expr)) ? Transform.PASS_THROUGH
                : classify(expr, froms.size());
        if (t == Transform.PASS_THROUGH && !operator.equals("=") && !operator.equals(":=")) {
            t = Transform.EXPRESSION;                 // SET @v += x
        }
        addOutput(outputs, to);
        for (ColumnNode from : froms) {
            if (!from.key().equals(to.key())) {
                edges.add(ColumnEdge.direct(from, to, t, Confidence.INFERRED, r));
            }
        }
        ColumnNode rows = rowsOf(to);
        for (ColumnNode n : nestedIndirect) {
            edges.add(ColumnEdge.indirect(n, rows, IndirectRole.FILTER, Confidence.INFERRED, r));
        }
        nestedIndirect.clear();
    }

    /** {@code FETCH c INTO v1, v2}: the cursor query's columns flow into the variables by position. */
    private void fetch(ParserRuleContext n, List<ColumnNode> outputs) {
        String text = text(n);
        String cursorName = VariableScopes.fetchCursor(text);
        List<String> names = VariableScopes.intoNames(text);
        if (cursorName == null || names.isEmpty()) {
            return;
        }
        List<ColumnNode> vars = new ArrayList<>();
        for (String name : names) {
            vars.add(name == null ? null : intoVariable(name));
        }
        VariableScopes.Cursor cursor = variables.cursor(varScope, cursorName);
        if (cursor == null) {
            vars.stream().filter(java.util.Objects::nonNull).forEach(v -> addOutput(outputs, v));
            return;
        }
        for (int i = 0; i < cursor.columns().size(); i++) {
            ColumnNode to = vars.size() == 1 ? vars.get(0) : i < vars.size() ? vars.get(i) : null;
            if (to == null) {
                continue;
            }
            addOutput(outputs, to);
            ColumnNode from = new ColumnNode(OwnerKind.RESULT, "result", cursor.ownerKey(), cursor.columns().get(i));
            edges.add(ColumnEdge.direct(from, to, Transform.PASS_THROUGH, Confidence.INFERRED, range(n)));
        }
    }

    /** The first descendant (pre-order) whose rule is one of {@code rules}. */
    private ParserRuleContext firstOf(ParserRuleContext node, Set<String> rules) {
        for (ParserRuleContext c : childRules(node)) {
            if (rules.contains(ir.ruleName(c))) {
                return c;
            }
            ParserRuleContext found = firstOf(c, rules);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ----- classification -----------------------------------------------------------------------------

    /** How an expression transforms its inputs, from its tokens (dialect-neutral). */
    private Transform classify(ParserRuleContext node, int refCount) {
        return classifyText(text(node), refCount);
    }

    private Transform classifyText(String text, int refCount) {
        if (refCount == 0) {
            return Transform.CONSTANT;
        }
        SqlScanner sc = new SqlScanner(text, ScannerOptions.forDialect(ir.dialect()));
        List<ScanToken> tokens = new ArrayList<>();
        ScanToken t;
        while ((t = sc.next()) != null) {
            tokens.add(t);
        }
        boolean window = false;
        boolean aggregate = false;
        boolean caseExpr = false;
        boolean cast = false;
        for (int i = 0; i < tokens.size(); i++) {
            ScanToken tok = tokens.get(i);
            boolean call = i + 1 < tokens.size() && tokens.get(i + 1).isSymbol('(');
            if (tok.isWord("OVER")) {
                window = true;
            } else if (call && tok.isWordIn(AGGREGATES)) {
                aggregate = true;
            } else if (tok.isWord("CASE") || (call && (tok.isWord("DECODE") || tok.isWord("IIF")))) {
                caseExpr = true;
            } else if (call && tok.isWordIn(CASTS)) {
                cast = true;
            } else if (tok.isSymbol(':') && i + 1 < tokens.size() && tokens.get(i + 1).isSymbol(':')) {
                cast = true;
            }
        }
        if (window) {
            return Transform.WINDOW;
        }
        if (aggregate) {
            return Transform.AGGREGATE;
        }
        if (caseExpr) {
            return Transform.CASE;
        }
        if (cast) {
            return Transform.CAST;
        }
        return Transform.EXPRESSION;
    }

    private boolean isPlainColumn(String expr) {
        String e = expr.strip();
        return ObjectName.parse(ir.dialect(), e) != null
                && e.replaceAll("[\\s\"`\\[\\]]", "").matches("[\\p{L}\\p{N}_$#@&.]+");
    }

    // ----- tree helpers ---------------------------------------------------------------------------------

    /** The statement's main query: the first query not inside a CTE definition. */
    private ParserRuleContext firstQuery(ParserRuleContext node) {
        if (lr.queryRoots().contains(ir.ruleName(node))) {
            return node;                              // the statement is the query (SQLite/H2 select_stmt)
        }
        return firstQueryBelow(node);
    }

    private ParserRuleContext firstQueryBelow(ParserRuleContext node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof ParserRuleContext c) {
                String rule = ir.ruleName(c);
                if (rule.equals(lr.cteDefinition())) {
                    continue;
                }
                if (lr.queryRoots().contains(rule) || lr.queryBlocks().contains(rule)) {
                    return c;
                }
                ParserRuleContext found = firstQueryBelow(c);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private List<ParserRuleContext> findAll(ParserRuleContext node, String rule) {
        return findAll(node, rule, Set.of());
    }

    /** Descendants with {@code rule}, not descending into matches or into {@code stopAt} rules. */
    private List<ParserRuleContext> findAll(ParserRuleContext node, String rule, Set<String> stopAt) {
        List<ParserRuleContext> out = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof ParserRuleContext c) {
                String r = ir.ruleName(c);
                if (r.equals(rule)) {
                    out.add(c);
                } else if (!stopAt.contains(r)) {
                    out.addAll(findAll(c, rule, stopAt));
                }
            }
        }
        return out;
    }

    private List<ParserRuleContext> findAllAny(ParserRuleContext node, Set<String> rules, Set<String> stopAt) {
        List<ParserRuleContext> out = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof ParserRuleContext c) {
                String r = ir.ruleName(c);
                if (rules.contains(r)) {
                    out.add(c);
                } else if (!stopAt.contains(r)) {
                    out.addAll(findAllAny(c, rules, stopAt));
                }
            }
        }
        return out;
    }

    private ParserRuleContext first(ParserRuleContext node, String rule) {
        List<ParserRuleContext> all = findAll(node, rule, lr.queryBlocks());
        return all.isEmpty() ? null : all.get(0);
    }

    private List<ParserRuleContext> childRules(ParserRuleContext node) {
        List<ParserRuleContext> out = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof ParserRuleContext c) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean isInside(ParseTree node, ParserRuleContext container) {
        for (ParseTree p = node; p != null; p = p.getParent()) {
            if (p == container) {
                return true;
            }
        }
        return false;
    }

    private List<String> insertColumns(ParserRuleContext node) {
        if (lr.insertColumnsParent() == null) {
            return List.of();
        }
        ParserRuleContext holder = ir.ruleName(node).equals(lr.insertColumnsParent()) ? node
                : first(node, lr.insertColumnsParent());
        return holder == null ? List.of() : columnNames(holder);
    }

    private List<String> columnNames(ParserRuleContext holder) {
        List<String> names = new ArrayList<>();
        if (lr.columnNameRule() != null) {
            for (ParserRuleContext c : findAll(holder, lr.columnNameRule(), lr.queryBlocks())) {
                names.add(lastPart(text(c)));
            }
        }
        return names;
    }

    private ObjectRef target(Statement st, Access access) {
        for (ObjectRef r : st.refs()) {
            if (r.access() == access) {
                return r;
            }
        }
        return null;
    }

    /** Child positions of nodes with many children (long FROM or select lists), built on first use. */
    private final java.util.Map<ParserRuleContext, java.util.Map<ParseTree, Integer>> childIndex =
            new java.util.IdentityHashMap<>();

    private int indexIn(ParserRuleContext parent, ParseTree child) {
        if (parent.children.size() <= 32) {
            return parent.children.indexOf(child);
        }
        return childIndex.computeIfAbsent(parent, p -> {
            java.util.Map<ParseTree, Integer> m = new java.util.IdentityHashMap<>();
            for (int i = 0; i < p.children.size(); i++) {
                m.put(p.children.get(i), i);
            }
            return m;
        }).getOrDefault(child, -1);
    }

    /**
     * Alias next to a table or subquery: an alias-rule node among the following siblings (or the parent's).
     * The search ends at a comma: an alias never follows one, and scanning on made a FROM list of n tables
     * cost n² (16,000 tables took seconds).
     */
    private String aliasNear(ParseTree node) {
        ParseTree level = node;
        for (int depth = 0; depth < 3 && level.getParent() instanceof ParserRuleContext parent; depth++) {
            int idx = indexIn(parent, level);
            for (int i = idx + 1; i < parent.getChildCount(); i++) {
                if (parent.getChild(i) instanceof org.antlr.v4.runtime.tree.TerminalNode comma
                        && ",".equals(comma.getText())) {
                    return null;
                }
                if (parent.getChild(i) instanceof ParserRuleContext c) {
                    ParserRuleContext alias = findAlias(c, Math.max(ir.aliasDepth(), 1));
                    if (alias != null) {
                        return aliasName(text(alias));
                    }
                    if (lr.queryRoots().contains(ir.ruleName(c)) || ir.isNameRule(ir.ruleName(c))) {
                        return null;
                    }
                }
            }
            level = parent;
        }
        return null;
    }

    private ParserRuleContext findAlias(ParserRuleContext ctx, int depth) {
        if (ir.isAliasRule(ir.ruleName(ctx))) {
            return ctx;
        }
        if (depth == 0) {
            return null;
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i) instanceof ParserRuleContext c) {
                ParserRuleContext found = findAlias(c, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** {@code AS x}, {@code x}, {@code "x"} → {@code x}. */
    private static String aliasName(String aliasText) {
        String t = aliasText.strip();
        if (t.regionMatches(true, 0, "AS", 0, 2) && t.length() > 2 && Character.isWhitespace(t.charAt(2))) {
            t = t.substring(3).strip();
        }
        if (t.length() >= 2 && (t.startsWith("\"") || t.startsWith("[") || t.startsWith("`") || t.startsWith("'"))) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String lastPart(String name) {
        String t = name.strip();
        int dot = t.lastIndexOf('.');
        t = dot >= 0 ? t.substring(dot + 1) : t;
        return aliasName(t);
    }

    private String firstWord(ParserRuleContext node) {
        String t = text(node).strip();
        if (!t.isEmpty() && "\"`[".indexOf(t.charAt(0)) >= 0) {
            char close = t.charAt(0) == '[' ? ']' : t.charAt(0);       // a quoted name may contain spaces
            int end = t.indexOf(close, 1);
            if (end > 0) {
                return t.substring(1, end);
            }
        }
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i)) && t.charAt(i) != '(') {
            i++;
        }
        return aliasName(t.substring(0, i));
    }

    private String qualifiedText(ParserRuleContext ctx) {
        ParserRuleContext parent = ctx.getParent();
        if (parent != null) {
            int idx = indexIn(parent, ctx);
            if (idx >= 2 && ".".equals(parent.getChild(idx - 1).getText())
                    && parent.getChild(idx - 2) instanceof ParserRuleContext schema) {
                return text(schema) + "." + text(ctx);
            }
        }
        return text(ctx);
    }

    private int offset(ParserRuleContext ctx) {
        return base + Math.max(0, ctx.getStart().getStartIndex());
    }

    private TextRange range(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        int from = start == null || start.getStartIndex() < 0 ? 0 : start.getStartIndex();
        int to = stop == null || stop.getStopIndex() < from ? from : stop.getStopIndex() + 1;
        int a = Math.min(base + from, source.length());
        int b = Math.min(base + to, source.length());
        return source.range(a, Math.max(a, b));
    }

    private String text(ParserRuleContext ctx) {
        return source.slice(range(ctx));
    }

    private TextRange rangeOf(ParseTree node) {
        if (node instanceof org.antlr.v4.runtime.tree.TerminalNode t) {
            int a = Math.min(base + Math.max(0, t.getSymbol().getStartIndex()), source.length());
            int b = Math.min(base + t.getSymbol().getStopIndex() + 1, source.length());
            return source.range(a, Math.max(a, b));
        }
        return range((ParserRuleContext) node);
    }

    private String textOf(ParseTree node) {
        return source.slice(rangeOf(node));
    }

    /** Text of {@code ctx} without the trailing {@code exclude} subtree (e.g. an item without its alias). */
    private String text(ParserRuleContext ctx, ParserRuleContext exclude) {
        TextRange r = range(ctx);
        TextRange x = range(exclude);
        return source.text().substring(r.start().offset(), Math.max(r.start().offset(), x.start().offset()));
    }
}
