package com.github.amber.dialect.postgresql;

import com.github.amber.core.Dialect;
import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.diagnostic.Severity;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.UnitParse;
import com.github.amber.dialect.UnitStatus;
import com.github.amber.dialect.ir.FallbackScanner;
import com.github.amber.dialect.ir.TreeIrBuilder;
import com.github.amber.dialect.lineage.VariableScopes;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.StatementLineage;
import com.github.amber.core.lineage.Transform;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.UnitKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the body of a PL/pgSQL function, procedure or {@code DO} block (or of a {@code LANGUAGE sql}
 * function) into IR statements. The grammar keeps such bodies as strings, so this class does what
 * PostgreSQL's own PL/pgSQL parser does: it recognizes the procedural structure itself and hands
 * every embedded SQL statement to the PostgreSQL grammar.
 *
 * <p>Before parsing an embedded statement, two same-length rewrites keep it valid SQL while every
 * offset stays exact: {@code PERFORM} becomes {@code SELECT }, and the {@code INTO targets} of
 * {@code SELECT … INTO var} / {@code RETURNING … INTO var} are blanked (in PL/pgSQL they assign
 * variables; in SQL they would create a table).
 *
 * <p>Variables follow the routine: its parameters ({@code $1} too) and the {@code DECLARE}d names form
 * one {@link VariableScopes.Scope}, which every embedded statement resolves names against. {@code SELECT
 * … INTO [STRICT] v}, {@code v := expr} (parsed as {@code SELECT expr}), declaration defaults and
 * {@code FETCH c INTO v} become lineage edges into the variables.
 *
 * <p>A body statement that does not parse is reported as a warning and kept as a {@link
 * Confidence#PARTIAL} statement; it never fails the script.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class PlPgSqlParser {

    private static final Set<String> SQL_STARTERS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH",
            "CREATE", "DROP", "ALTER", "TRUNCATE", "VALUES", "TABLE", "LOCK", "ANALYZE", "VACUUM", "REFRESH", "GRANT",
            "REVOKE", "COPY", "SET", "RESET", "DISCARD", "NOTIFY", "LISTEN", "UNLISTEN", "CALL", "COMMIT", "ROLLBACK",
            "SAVEPOINT", "RELEASE", "COMMENT", "REINDEX", "CLUSTER", "IMPORT", "SECURITY", "PREPARE", "DEALLOCATE");

    private static final Set<String> SELECT_INTO_STOPS = Set.of("FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT",
            "OFFSET", "FETCH", "UNION", "EXCEPT", "INTERSECT", "WINDOW", "FOR");

    private final PostgreSqlFrontend frontend;
    private final char[] shadow;
    private final SourceText original;
    private final int end;
    private final int start;
    private final com.github.amber.core.lineage.Catalog catalog;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private SourceText shadowSource;
    private SqlScanner sc;
    /** The routine's variables and parameters, shared by every statement of the body. */
    private VariableScopes.Scope scope;
    private VariableScopes scopes;

    /**
     * @param source    the script (or a shadow of it) that contains the body
     * @param bodyStart offset of the first char inside the quotes
     * @param bodyEnd   offset just after the last char inside the quotes
     */
    PlPgSqlParser(PostgreSqlFrontend frontend, SourceText source, int bodyStart, int bodyEnd,
                  com.github.amber.core.lineage.Catalog catalog) {
        this.frontend = frontend;
        this.catalog = catalog;
        this.original = source;
        this.shadow = source.text().toCharArray();
        this.end = bodyEnd;
        this.start = bodyStart;
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /**
     * The scope of the routine (or {@code DO} block) whose body is parsed: named after it, with its
     * parameters ({@code func_arg} nodes of {@code node}) in order. Called before the body is parsed.
     */
    void routine(Statement routine, org.antlr.v4.runtime.ParserRuleContext node) {
        String display = routine.name() != null ? routine.name().toString() : "block@L" + routine.range().start().line();
        scope = new VariableScopes.Scope(null, display.toUpperCase(Locale.ROOT) + "@" + routine.range().start().offset(),
                display, routine.kind() != StatementKind.BLOCK);
        scopes = VariableScopes.fixed(scope);
        addParameters(node);
    }

    private void addParameters(org.antlr.v4.runtime.ParserRuleContext node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof org.antlr.v4.runtime.ParserRuleContext c) {
                String rule = PostgreSqlIrRules.INSTANCE.ruleName(c);
                if (rule.equals("func_arg")) {
                    org.antlr.v4.runtime.ParserRuleContext name = descendant(c, "param_name");
                    scope.addParameter(name != null ? name.getText() : "$" + (scope.parameterNames().size() + 1));
                } else if (!rule.equals("func_as") && !rule.equals("func_return")) {
                    addParameters(c);
                }
            }
        }
    }

    private static org.antlr.v4.runtime.ParserRuleContext descendant(org.antlr.v4.runtime.ParserRuleContext node, String rule) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof org.antlr.v4.runtime.ParserRuleContext c) {
                if (PostgreSqlIrRules.INSTANCE.ruleName(c).equals(rule)) {
                    return c;
                }
                org.antlr.v4.runtime.ParserRuleContext found = descendant(c, rule);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Parses a PL/pgSQL body (one block, possibly labelled) into children of {@code container}. */
    void parsePlpgsql(Statement container) {
        prepareShadow();
        ScanToken t = peek();
        if (t == null) {
            return;
        }
        Statement block = new Statement(StatementKind.BLOCK, range(t.start(), end), Confidence.INFERRED);
        block.setSummary("Body");
        container.addChild(block);
        parseBlock(block);
    }

    /** Parses a {@code LANGUAGE sql} body: a list of SQL statements. */
    void parseSqlBody(Statement container) {
        prepareShadow();
        while (peek() != null) {
            sqlStatement(container, next(), false);
        }
    }

    // ----- structure ----------------------------------------------------------------------------

    private void parseBlock(Statement block) {
        skipLabel();
        if (peekWord("DECLARE")) {
            next();
            declarations(block);
        }
        if (!peekWord("BEGIN")) {
            return;
        }
        next();
        statements(block, Set.of("END", "EXCEPTION"));
        if (peekWord("EXCEPTION")) {
            next();
            while (peekWord("WHEN")) {
                ScanToken when = next();
                skipUntilWord("THEN");
                Statement handler = new Statement(StatementKind.EXCEPTION_HANDLER, range(when.start(), when.end()),
                        Confidence.INFERRED);
                block.addChild(handler);
                statements(handler, Set.of("WHEN", "END"));
            }
        }
        if (peekWord("END")) {
            next();
            skipToSemicolon();
        }
    }

    private void declarations(Statement block) {
        List<ColumnNode> outputs = new ArrayList<>();
        List<ColumnEdge> edges = new ArrayList<>();
        while (peek() != null && !peekWord("BEGIN")) {
            ScanToken first = next();
            int stmtEnd = statementEnd();
            String upper = text(first.start(), stmtEnd).toUpperCase(Locale.ROOT);
            int queryAt = cursorQueryStart(first.start(), stmtEnd);
            if (upper.contains(" CURSOR") && queryAt >= 0) {
                Statement cursor = new Statement(StatementKind.CURSOR, range(first.start(), stmtEnd), Confidence.INFERRED);
                block.addChild(cursor);
                cursorLineage(cursor, first.text(), embedRefsInto(cursor, queryAt, trimSemicolon(stmtEnd)), queryAt);
            } else if (first.type() == ScanToken.Type.WORD || first.type() == ScanToken.Type.QUOTED_IDENTIFIER) {
                scope.addDeclared(first.text());
                int valueAt = declarationValue(first.end(), stmtEnd);
                if (valueAt >= 0) {
                    StatementLineage l = expressionLineage(targets(List.of(first.text())), valueAt,
                            trimSemicolon(stmtEnd));
                    if (l != null) {
                        outputs.addAll(l.outputs());
                        edges.addAll(l.edges());
                    }
                }
            }
            sc.reset(stmtEnd);
        }
        if (!outputs.isEmpty()) {
            block.setLineage(new StatementLineage(outputs, edges));
        }
    }

    /** Start of the default value after {@code :=}, {@code =} or {@code DEFAULT} in a declaration, or -1. */
    private int declarationValue(int from, int to) {
        SqlScanner s = new SqlScanner(shadowSource.text(), ScannerOptions.POSTGRESQL);
        s.reset(from);
        ScanToken t;
        int depth = 0;
        while ((t = s.next()) != null && t.start() < to) {
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth--;
            } else if (depth == 0 && (t.isWord("DEFAULT") || t.isSymbol('='))) {
                return t.end();
            } else if (depth == 0 && t.isSymbol(':') && s.peek() != null && s.peek().isSymbol('=')) {
                return s.next().end();
            }
        }
        return -1;
    }

    private void statements(Statement container, Set<String> stops) {
        ScanToken t;
        while ((t = peek()) != null && !t.isWordIn(stops)) {
            if (t.isWord("ELSIF") || t.isWord("ELSEIF") || t.isWord("ELSE")) {
                return;
            }
            statement(container);
        }
    }

    private void statement(Statement container) {
        ScanToken first = peek();
        if (first.isSymbol('<')) {
            skipLabel();
            first = peek();
            if (first == null) {
                return;
            }
        }
        String word = first.type() == ScanToken.Type.WORD ? first.upper() : "";
        switch (word) {
            case "DECLARE", "BEGIN" -> {
                Statement block = new Statement(StatementKind.BLOCK, range(first.start(), first.end()), Confidence.INFERRED);
                container.addChild(block);
                parseBlock(block);
            }
            case "IF" -> ifStatement(container, next());
            case "CASE" -> caseStatement(container, next());
            case "LOOP", "WHILE", "FOR", "FOREACH" -> loop(container, next());
            case "RETURN" -> returnStatement(container, next());
            case "PERFORM" -> {
                ScanToken perform = next();
                "SELECT ".getChars(0, 7, shadow, perform.start());     // same length as PERFORM
                refreshShadow();
                sqlStatement(container, perform, false);
            }
            case "EXECUTE" -> simple(container, next(), StatementKind.DYNAMIC_SQL);
            case "OPEN" -> openCursor(container, next());
            case "FETCH" -> fetch(container, next());
            case "MOVE", "CLOSE" -> simple(container, next(), StatementKind.CURSOR);
            case "EXIT", "CONTINUE", "RAISE", "ASSERT", "GET", "NULL" -> simple(container, next(), StatementKind.OTHER);
            default -> {
                ScanToken head = next();
                if (head.isWordIn(SQL_STARTERS)) {
                    sqlStatement(container, head, true);
                } else if (isAssignment(head)) {
                    assignment(container, head);
                } else {
                    simple(container, head, StatementKind.OTHER);
                }
            }
        }
    }

    private void ifStatement(Statement container, ScanToken ifToken) {
        Statement st = new Statement(StatementKind.IF, range(ifToken.start(), ifToken.end()), Confidence.INFERRED);
        container.addChild(st);
        skipUntilWord("THEN");
        statements(st, Set.of("END"));
        while (peekWord("ELSIF") || peekWord("ELSEIF") || peekWord("ELSE")) {
            ScanToken branch = next();
            if (!branch.isWord("ELSE")) {
                skipUntilWord("THEN");
            }
            statements(st, Set.of("END"));
        }
        endConstruct();
    }

    private void caseStatement(Statement container, ScanToken caseToken) {
        Statement st = new Statement(StatementKind.CASE, range(caseToken.start(), caseToken.end()), Confidence.INFERRED);
        container.addChild(st);
        skipUntilWordAhead("WHEN");
        while (peekWord("WHEN") || peekWord("ELSE")) {
            ScanToken branch = next();
            if (branch.isWord("WHEN")) {
                skipUntilWord("THEN");
            }
            statements(st, Set.of("WHEN", "END"));
        }
        endConstruct();
    }

    private void loop(Statement container, ScanToken head) {
        Statement st = new Statement(StatementKind.LOOP, range(head.start(), head.end()), Confidence.INFERRED);
        container.addChild(st);
        if (!head.isWord("LOOP")) {
            int loopAt = findWordAtDepth0("LOOP");
            if (head.isWord("FOR")) {
                forHeader(st, loopAt);
            }
            sc.reset(loopAt);
            next();                                   // LOOP
        }
        statements(st, Set.of("END"));
        endConstruct();
    }

    /** {@code FOR r IN <query> LOOP}: the query's objects are read by the loop. */
    private void forHeader(Statement loop, int loopAt) {
        ScanToken t;
        while ((t = next()) != null && t.start() < loopAt) {
            if (t.isWord("IN")) {
                ScanToken q = peek();
                if (q != null && q.start() < loopAt) {
                    if (q.isWord("EXECUTE")) {
                        Statement dyn = new Statement(StatementKind.DYNAMIC_SQL, range(q.start(), loopAt), Confidence.UNRESOLVED);
                        loop.addChild(dyn);
                    } else if (q.isWordIn(Set.of("SELECT", "WITH", "VALUES", "TABLE"))) {
                        embedRefsInto(loop, q.start(), loopAt);
                    }
                }
                return;
            }
        }
    }

    private void returnStatement(Statement container, ScanToken ret) {
        if (peekWord("QUERY")) {
            next();
            ScanToken q = peek();
            if (q != null && q.isWord("EXECUTE")) {
                simple(container, ret, StatementKind.DYNAMIC_SQL);
            } else if (q != null) {
                int stmtEnd = statementEnd();
                Statement st = new Statement(StatementKind.SELECT, range(ret.start(), stmtEnd), Confidence.INFERRED);
                container.addChild(st);
                embedRefsInto(st, q.start(), trimSemicolon(stmtEnd));
                st.setSummary("Return query" + (Summary.reads(st).isEmpty() ? "" : " from " + Summary.reads(st)));
                sc.reset(stmtEnd);
            }
            return;
        }
        simple(container, ret, StatementKind.RETURN);
    }

    private void openCursor(Statement container, ScanToken open) {
        int stmtEnd = statementEnd();
        Statement st = new Statement(StatementKind.CURSOR, range(open.start(), stmtEnd), Confidence.INFERRED);
        container.addChild(st);
        int queryAt = cursorQueryStart(open.start(), stmtEnd);
        if (queryAt >= 0) {
            ScanToken name = peek();
            List<Statement> parsed = embedRefsInto(st, queryAt, trimSemicolon(stmtEnd));
            if (name != null && name.start() < queryAt) {
                cursorLineage(st, name.text(), parsed, queryAt);
            }
        }
        sc.reset(stmtEnd);
    }

    /** The cursor's query lineage becomes the cursor statement's; its columns are remembered for FETCH. */
    private void cursorLineage(Statement cursor, String name, List<Statement> parsed, int queryAt) {
        for (Statement q : parsed) {
            if (q.lineage() != null) {
                cursor.setLineage(q.lineage());
                List<String> columns = q.lineage().outputs().stream().map(ColumnNode::column).toList();
                scope.addCursor(name, new VariableScopes.Cursor("result@" + q.range().start().offset(), columns));
                return;
            }
        }
    }

    /** {@code FETCH [NEXT FROM] c INTO v1, v2}: the cursor's columns flow into the variables by position. */
    private void fetch(Statement container, ScanToken head) {
        int stmtEnd = statementEnd();
        Statement st = new Statement(StatementKind.CURSOR, range(head.start(), stmtEnd), Confidence.INFERRED);
        container.addChild(st);
        String text = text(head.start(), trimSemicolon(stmtEnd));
        String cursorName = VariableScopes.fetchCursor(text);
        VariableScopes.Cursor cursor = cursorName == null ? null : scopes.cursor(scope, cursorName);
        List<ColumnNode> vars = targets(VariableScopes.intoNames(text));
        if (cursor != null && !vars.isEmpty()) {
            List<ColumnNode> outputs = new ArrayList<>();
            List<ColumnEdge> edges = new ArrayList<>();
            for (int i = 0; i < cursor.columns().size(); i++) {
                ColumnNode to = vars.size() == 1 ? vars.getFirst() : i < vars.size() ? vars.get(i) : null;
                if (to == null) {
                    continue;
                }
                if (!outputs.contains(to)) {
                    outputs.add(to);
                }
                edges.add(ColumnEdge.direct(new ColumnNode(ColumnNode.OwnerKind.RESULT, "result", cursor.ownerKey(),
                        cursor.columns().get(i)), to, Transform.PASS_THROUGH, Confidence.INFERRED, st.range()));
            }
            st.setLineage(new StatementLineage(outputs, edges));
        }
        sc.reset(stmtEnd);
    }

    /** {@code target := expr} / {@code target = expr}: evaluated by PostgreSQL as {@code SELECT expr}. */
    private void assignment(Statement container, ScanToken head) {
        int stmtEnd = statementEnd();
        Statement st = new Statement(StatementKind.ASSIGNMENT, range(head.start(), stmtEnd), Confidence.INFERRED);
        container.addChild(st);
        int valueAt = declarationValue(head.start(), stmtEnd);
        if (valueAt >= 0) {
            String target = text(head.start(), valueAt).replaceAll("(:=|=)\\s*$", "").strip();
            List<ColumnNode> vars = targets(VariableScopes.targetNames(target, scope));
            if (!vars.isEmpty()) {
                st.setLineage(expressionLineage(vars, valueAt, trimSemicolon(stmtEnd)));
            }
        }
        sc.reset(stmtEnd);
    }

    /** Variable nodes for target names; INTO and assignment targets are variables even when undeclared. */
    private List<ColumnNode> targets(List<String> names) {
        List<ColumnNode> vars = new ArrayList<>();
        for (String n : names) {
            if (n == null) {
                vars.add(null);
                continue;
            }
            String name = VariableScopes.targetName(n, scope);
            ColumnNode v = scopes.lookup(scope, name);
            if (v == null) {
                scope.addTarget(name);
                v = scopes.lookup(scope, name);
            }
            vars.add(v);
        }
        return vars;
    }

    /**
     * Lineage of {@code expr} in [from, to) assigned to {@code vars}: the expression is parsed as
     * {@code SELECT expr} in a scratch copy of the body (same offsets), and its result becomes the variables.
     */
    private StatementLineage expressionLineage(List<ColumnNode> vars, int from, int to) {
        int start = from - 7;
        if (start < 0 || to <= from || vars.isEmpty() || vars.stream().anyMatch(java.util.Objects::isNull)) {
            return null;
        }
        char[] scratch = shadow.clone();
        "SELECT ".getChars(0, 7, scratch, start);
        SourceText scratchSource = new SourceText(original.name(), new String(scratch));
        for (Statement st : parseSql(scratchSource, start, to, false)) {
            if (st.kind() == StatementKind.SELECT && st.lineage() != null) {
                return VariableScopes.assign(st.lineage(), "result@" + st.range().start().offset(), vars, original);
            }
        }
        return null;
    }

    private void simple(Statement container, ScanToken first, StatementKind kind) {
        int stmtEnd = statementEnd();
        Statement st = new Statement(kind, range(first.start(), stmtEnd),
                kind == StatementKind.DYNAMIC_SQL ? Confidence.UNRESOLVED : Confidence.INFERRED);
        if (kind == StatementKind.OTHER) {
            st.setSummary(capitalize(first.text()));
        }
        container.addChild(st);
        sc.reset(stmtEnd);
    }

    /** An embedded SQL statement, parsed by the PostgreSQL grammar. */
    private void sqlStatement(Statement container, ScanToken first, boolean maskInto) {
        int stmtEnd = statementEnd();
        String into = maskInto ? maskInto(first.start(), stmtEnd) : null;
        List<Statement> parsed = parseSql(first.start(), stmtEnd);
        for (Statement st : parsed) {
            container.addChild(st);
        }
        if (into != null && !parsed.isEmpty() && parsed.getFirst().kind() == StatementKind.SELECT
                && parsed.getFirst().lineage() != null) {
            List<ColumnNode> vars = targets(VariableScopes.intoNames(into));
            Statement select = parsed.getFirst();
            if (!vars.isEmpty()) {
                select.setLineage(VariableScopes.assign(select.lineage(), "result@" + select.range().start().offset(),
                        vars, original));
            }
        }
        sc.reset(stmtEnd);
    }

    // ----- embedding SQL --------------------------------------------------------------------------

    /** Parses [from, to) as SQL, adds the objects it touches to {@code target}'s own refs, returns the statements. */
    private List<Statement> embedRefsInto(Statement target, int from, int to) {
        List<Statement> parsed = parseSql(from, to);
        for (Statement st : parsed) {
            st.forEach(s -> s.refs().forEach(target::addRef));
            st.ctes().forEach(target::addCte);
        }
        return parsed;
    }

    private List<Statement> parseSql(int from, int to) {
        return parseSql(shadowSource, from, to, true);
    }

    /** @param report whether a statement that does not parse is reported and scanned (not for scratch parses) */
    private List<Statement> parseSql(SourceText text, int from, int to, boolean report) {
        int stop = to;
        while (stop > from && Character.isWhitespace(text.text().charAt(stop - 1))) {
            stop--;
        }
        if (stop <= from) {
            return List.of();
        }
        StatementUnit unit = new StatementUnit(-1, UnitKind.SQL, text.range(from, stop));
        UnitParse parsed = frontend.parseUnit(text, unit);
        if (parsed.status() == UnitStatus.PARSED) {
            TreeIrBuilder builder = new TreeIrBuilder(PostgreSqlIrRules.INSTANCE, text, from, catalog).withVariables(scopes);
            List<Statement> built = builder.build(parsed.tree());
            if (report) {
                diagnostics.addAll(builder.diagnostics());
            }
            return built;
        }
        if (!report) {
            return List.of();
        }
        Diagnostic first = parsed.diagnostics().isEmpty() ? null : parsed.diagnostics().getFirst();
        diagnostics.add(new Diagnostic(Severity.WARNING, "plpgsql",
                "Statement in function body could not be parsed" + (first == null ? "" : ": " + first.message()),
                first == null ? unit.range() : first.range(), first == null ? null : first.offending(),
                first == null ? List.of() : first.expected()));
        FallbackScanner.Result r = FallbackScanner.scan(Dialect.POSTGRESQL, shadowSource, unit.range());
        Statement st = new Statement(r.kind(), unit.range(), Confidence.PARTIAL);
        r.refs().forEach(st::addRef);
        return List.of(st);
    }

    /**
     * Blanks {@code INTO [STRICT] target, …} in {@code SELECT … INTO …} and {@code … RETURNING … INTO …}
     * (but not {@code INSERT INTO} or {@code MERGE INTO}).
     */
    /** @return the blanked {@code INTO …} text of a {@code SELECT … INTO}, or {@code null} */
    private String maskInto(int from, int to) {
        SqlScanner s = new SqlScanner(new String(shadow), ScannerOptions.POSTGRESQL);
        s.reset(from);
        ScanToken first = s.next();
        if (first == null || first.isWord("INSERT") || first.isWord("MERGE")) {
            if (first != null && (first.isWord("INSERT") || first.isWord("MERGE"))) {
                maskReturningInto(s, to);
            }
            return null;
        }
        int depth = 0;
        ScanToken t;
        while ((t = s.next()) != null && t.start() < to) {
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth--;
            } else if (depth == 0 && t.isWord("INTO")) {
                String into = blankTargets(s, t, to);
                refreshShadow();
                return into;
            }
        }
        return null;
    }

    private void maskReturningInto(SqlScanner s, int to) {
        ScanToken t;
        boolean returning = false;
        int depth = 0;
        while ((t = s.next()) != null && t.start() < to) {
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth--;
            } else if (depth == 0 && t.isWord("RETURNING")) {
                returning = true;
            } else if (depth == 0 && returning && t.isWord("INTO")) {
                blankTargets(s, t, to);
                refreshShadow();
                return;
            }
        }
    }

    /** Blanks {@code INTO targets} and returns the text it blanked. */
    private String blankTargets(SqlScanner s, ScanToken into, int to) {
        int blankEnd = into.end();
        ScanToken t;
        while ((t = s.peek()) != null && t.start() < to && !t.isSymbol(';') && !t.isWordIn(SELECT_INTO_STOPS)) {
            s.next();
            blankEnd = t.end();
        }
        String blanked = new String(shadow, into.start(), blankEnd - into.start());
        for (int i = into.start(); i < blankEnd; i++) {
            if (!Character.isWhitespace(shadow[i])) {
                shadow[i] = ' ';
            }
        }
        return blanked;
    }

    // ----- token helpers --------------------------------------------------------------------------

    private void prepareShadow() {
        refreshShadow();
        sc.reset(start);
    }

    private void refreshShadow() {
        int pos = sc == null ? start : sc.position();
        shadowSource = new SourceText(original.name(), new String(shadow));
        sc = new SqlScanner(shadowSource.text(), ScannerOptions.POSTGRESQL);
        sc.reset(pos);
    }

    private ScanToken peek() {
        ScanToken t = sc.peek();
        return t == null || t.start() >= end ? null : t;
    }

    private ScanToken next() {
        ScanToken t = sc.next();
        return t == null || t.start() >= end ? null : t;
    }

    private boolean peekWord(String word) {
        ScanToken t = peek();
        return t != null && t.isWord(word);
    }

    /** Offset just after the {@code ;} ending the current statement (depth 0), or the body end. */
    private int statementEnd() {
        int saved = sc.position();
        int depth = 0;
        ScanToken t;
        int result = end;
        while ((t = sc.next()) != null && t.start() < end) {
            if (t.isSymbol('(') || t.isSymbol('[')) {
                depth++;
            } else if (t.isSymbol(')') || t.isSymbol(']')) {
                depth--;
            } else if (depth <= 0 && t.isSymbol(';')) {
                result = t.end();
                break;
            }
        }
        sc.reset(saved);
        return result;
    }

    private int trimSemicolon(int stmtEnd) {
        return stmtEnd > start && shadow[stmtEnd - 1] == ';' ? stmtEnd - 1 : stmtEnd;
    }

    private int findWordAtDepth0(String word) {
        int saved = sc.position();
        int depth = 0;
        ScanToken t;
        int result = end;
        while ((t = sc.next()) != null && t.start() < end) {
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth--;
            } else if (depth == 0 && t.isWord(word)) {
                result = t.start();
                break;
            }
        }
        sc.reset(saved);
        return result;
    }

    private void skipUntilWord(String word) {
        ScanToken t;
        int depth = 0;
        while ((t = next()) != null) {
            if (t.isSymbol('(')) {
                depth++;
            } else if (t.isSymbol(')')) {
                depth--;
            } else if (depth == 0 && t.isWord(word)) {
                return;
            }
        }
    }

    private void skipUntilWordAhead(String word) {
        ScanToken t;
        while ((t = peek()) != null && !t.isWord(word)) {
            next();
        }
    }

    private void skipToSemicolon() {
        sc.reset(statementEnd());
    }

    /** {@code END IF;}, {@code END LOOP [label];}, {@code END CASE;}. */
    private void endConstruct() {
        if (peekWord("END")) {
            next();
            skipToSemicolon();
        }
    }

    private void skipLabel() {
        ScanToken t = peek();
        if (t != null && t.isSymbol('<')) {
            ScanToken g;
            while ((g = next()) != null && !(g.isSymbol('>') && peek() != null && peek().isSymbol('>'))) {
                // skip << label
            }
            next();
        }
    }

    /** Start of the query in {@code CURSOR … FOR|IS <query>} or {@code OPEN c FOR <query>}, or -1. */
    private int cursorQueryStart(int from, int to) {
        SqlScanner s = new SqlScanner(shadowSource.text(), ScannerOptions.POSTGRESQL);
        s.reset(from);
        ScanToken t;
        while ((t = s.next()) != null && t.start() < to) {
            if (t.isWord("FOR") || t.isWord("IS")) {
                ScanToken q = s.peek();
                if (q != null && q.start() < to && q.isWordIn(Set.of("SELECT", "WITH", "VALUES", "TABLE"))) {
                    return q.start();
                }
            }
        }
        return -1;
    }

    private boolean isAssignment(ScanToken head) {
        int saved = sc.position();
        ScanToken t;
        boolean result = false;
        while ((t = sc.next()) != null && t.start() < end && !t.isSymbol(';')) {
            if (t.isSymbol(':') && sc.peek() != null && sc.peek().isSymbol('=')) {
                result = true;
                break;
            }
            if (t.isSymbol('=')) {
                result = true;
                break;
            }
        }
        sc.reset(saved);
        return result;
    }

    private String text(int from, int to) {
        return new String(shadow, from, Math.max(0, to - from));
    }

    private TextRange range(int from, int to) {
        return original.range(from, Math.max(from, to));
    }

    private static String capitalize(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** Small helper for summaries built here. */
    private static final class Summary {
        static String reads(Statement st) {
            return String.join(", ", st.refs().stream()
                    .filter(r -> r.access() == com.github.amber.core.ir.Access.READ
                            && r.kind() != com.github.amber.core.ir.ObjectKind.CTE)
                    .map(r -> r.name().toString()).distinct().toList());
        }
    }
}
