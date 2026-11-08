package com.github.amber.dialect.lineage;

import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.ColumnNode.OwnerKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.ir.IrRules;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The variables and parameters visible to the statements of one parse tree, so column lineage can
 * follow values through them. Variables belong to the enclosing routine (procedure, function, trigger,
 * package body), anonymous block or batch; a name is looked up from the innermost scope outwards.
 *
 * <p>A variable is a lineage node of kind {@link OwnerKind#VARIABLE} ({@link OwnerKind#PARAMETER} for
 * routine parameters): its {@code owner} is the scope's display name ({@code dw.p_load},
 * {@code block@L25}, {@code batch@L1}, {@code session}), its {@code column} the variable name, and its
 * {@code ownerKey} {@code var:<scope key>:<NAME>} is unique to the variable, so every variable has its
 * own {@link ColumnNode#ROWS} node for the filters of the query that filled it.
 *
 * <p>A function's result is the variable {@code return} of the function's scope ({@link #returnNode}):
 * {@code RETURN expr} writes it, and a call of the function in another statement reads it. The record
 * of a cursor FOR loop ({@code FOR r IN (SELECT …) LOOP}, {@code FOR r IN c LOOP}) is no variable: its
 * fields are the output columns of the loop's query ({@link #record}).
 *
 * <p>Built per parse tree by {@code TreeIrBuilder}, which registers every container statement before
 * its children are analyzed; PL/pgSQL bodies, whose statements are parsed one by one, use one
 * {@linkplain #fixed fixed scope} filled by the body parser.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class VariableScopes {

    /** Container kinds whose variables and parameters form a scope of their own. */
    private static final Set<StatementKind> ROUTINES = EnumSet.of(StatementKind.CREATE_PROCEDURE,
            StatementKind.CREATE_FUNCTION, StatementKind.ROUTINE, StatementKind.CREATE_TRIGGER,
            StatementKind.CREATE_PACKAGE_BODY, StatementKind.CREATE_TYPE_BODY);

    private static final String NAME = "[\\p{L}_@#$\"`\\[][\\p{L}\\p{N}_@#$\"`\\]]*";
    private static final Pattern PARAMETER = Pattern.compile(
            "(?is)\\s*(?:(?:IN\\s+OUT|INOUT|IN|OUT|VARIADIC)\\s+)?(" + NAME + ")");
    private static final Pattern DECLARED = Pattern.compile(
            "(?is)\\s*(?:DECLARE\\s+)?((?:" + NAME + "\\s*,\\s*)*" + NAME + ")\\s*(?:AS\\s+)?(\\w*)");
    private static final Pattern HANDLER = Pattern.compile("(?is)\\s*(?:DECLARE\\s+)?(?:CONTINUE|EXIT|UNDO)\\s+HANDLER\\b.*");
    private static final Set<String> NOT_VARIABLES = Set.of("CURSOR", "CONDITION", "TABLE", "SCROLL", "INSENSITIVE",
            "SENSITIVE", "ASENSITIVE", "NO", "EXCEPTION");
    private static final Pattern TARGET = Pattern.compile("(?s)(:?)(" + NAME + "(?:\\s*\\.\\s*" + NAME + ")*)");
    private static final Pattern FETCH = Pattern.compile("(?is)\\s*FETCH\\b(.*?)\\bINTO\\b.*");
    private static final Pattern CURSOR_NAME = Pattern.compile(
            "(?is)\\s*(?:DECLARE\\s+)?(?:CURSOR\\s+|OPEN\\s+)?(" + NAME + ")");
    private static final Pattern LOOP_QUERY_RECORD = Pattern.compile("(?is)\\s*(" + NAME + ")\\s+IN\\b.*");
    private static final Pattern LOOP_CURSOR_RECORD = Pattern.compile(
            "(?is)\\s*(" + NAME + ")\\s+IN\\s+(" + NAME + "(?:\\s*\\.\\s*" + NAME + ")?)\\s*(?:\\(.*)?");
    /** Name of the function-result variable (no SQL dialect allows {@code return} as a variable name). */
    public static final String RETURN = "return";
    private static final Set<String> ASSIGNMENT_OPERATORS = Set.of(":=", "=", "+=", "-=", "*=", "/=", "%=", "&=",
            "|=", "^=");

    /** One scope: a routine, an anonymous block, a batch or the session. */
    public static final class Scope {
        private final Scope parent;
        private final String key;
        private final String display;
        private final boolean routine;
        private final Map<String, String> parameters = new LinkedHashMap<>();
        private final Map<String, String> declared = new LinkedHashMap<>();
        private final Map<String, String> targets = new LinkedHashMap<>();
        private final Map<String, Cursor> cursors = new LinkedHashMap<>();
        private final Map<String, LoopRecord> records = new LinkedHashMap<>();

        /**
         * @param key     stable key of the scope within the script (e.g. {@code DW.P_LOAD@120})
         * @param display how the scope is shown ({@code dw.p_load}, {@code block@L25})
         * @param routine whether the scope is a routine (T-SQL {@code @} names default to the outermost routine)
         */
        public Scope(Scope parent, String key, String display, boolean routine) {
            this.parent = parent;
            this.key = key;
            this.display = display;
            this.routine = routine;
        }

        public String key() {
            return key;
        }

        public String display() {
            return display;
        }

        public void addParameter(String name) {
            parameters.putIfAbsent(norm(name), clean(name));
        }

        public void addDeclared(String name) {
            declared.putIfAbsent(norm(name), clean(name));
        }

        /** A name assigned by {@code INTO}, {@code FETCH} or an assignment, declared or not. */
        public void addTarget(String name) {
            targets.putIfAbsent(norm(name), clean(name));
        }

        public void addCursor(String name, Cursor cursor) {
            cursors.put(norm(name), cursor);
        }

        /** The record of a cursor FOR loop: fields are the columns of {@code record}'s query or cursor. */
        public void addRecord(String name, LoopRecord record) {
            records.put(norm(name), record);
        }

        /** Parameter names in declaration order (for PostgreSQL {@code $1}, {@code $2}). */
        public List<String> parameterNames() {
            return List.copyOf(parameters.values());
        }

        boolean declares(String upper) {
            return parameters.containsKey(upper) || declared.containsKey(upper);
        }

        /** The lineage node of a variable of this scope. */
        public ColumnNode node(String name) {
            String upper = norm(name);
            String spelling = parameters.getOrDefault(upper, declared.getOrDefault(upper,
                    targets.getOrDefault(upper, clean(name))));
            OwnerKind kind = parameters.containsKey(upper) ? OwnerKind.PARAMETER : OwnerKind.VARIABLE;
            return new ColumnNode(kind, display, "var:" + key + ":" + upper, spelling);
        }
    }

    /**
     * Output columns of a cursor's query, for {@code FETCH … INTO}.
     *
     * @param ownerKey key of the cursor statement's result owner
     */
    public record Cursor(String ownerKey, List<String> columns) {
    }

    /**
     * The record of a cursor FOR loop.
     *
     * @param ownerKey key of the loop query's result owner ({@code result@<offset>}), or {@code null}
     * @param cursor   the cursor the loop opens ({@code FOR r IN c LOOP}), or {@code null}
     */
    public record LoopRecord(String ownerKey, String cursor) {
    }

    private record Entry(Statement statement, Statement parent) {
    }

    /** A {@code target op expression} pair of an assignment. */
    record Pair(ParseTree target, ParserRuleContext expression, String operator) {
    }

    private final IrRules ir;
    private final VariableRules rules;
    private final SourceText source;
    private final int base;
    private final Scope fixed;
    private final Map<ParseTree, Entry> containers = new IdentityHashMap<>();
    private final Map<ParseTree, Scope> scopes = new IdentityHashMap<>();
    private Scope root;
    private Scope session;

    private VariableScopes(IrRules ir, VariableRules rules, SourceText source, int base, Scope fixed) {
        this.ir = ir;
        this.rules = rules;
        this.source = source;
        this.base = base;
        this.fixed = fixed;
    }

    /** Scopes of one parse tree; {@code base} is the tree's offset in {@code source}. */
    public static VariableScopes tree(IrRules ir, VariableRules rules, SourceText source, int base) {
        return new VariableScopes(ir, rules, source, base, null);
    }

    /** One scope for every statement (a PL/pgSQL body whose statements are parsed one by one). */
    public static VariableScopes fixed(Scope scope) {
        return new VariableScopes(null, VariableRules.NONE, null, 0, scope);
    }

    public VariableRules rules() {
        return rules;
    }

    /** Called for every container statement before its children are analyzed. */
    public void register(ParserRuleContext node, Statement statement, Statement parent) {
        if (fixed == null) {
            containers.put(node, new Entry(statement, parent));
        }
    }

    /** The innermost scope around {@code node} (a statement node, or a container's own node). */
    public Scope scopeOf(ParseTree node) {
        if (fixed != null) {
            return fixed;
        }
        ParseTree top = node;
        for (ParseTree p = node; p != null; p = p.getParent()) {
            Entry e = containers.get(p);
            if (e != null && isScope(e.statement().kind(), e.parent() == null ? null : e.parent().kind())) {
                return scope((ParserRuleContext) p, e);
            }
            top = p;
        }
        return root(top);
    }

    private boolean isScope(StatementKind kind, StatementKind parentKind) {
        if (ROUTINES.contains(kind)) {
            return true;
        }
        // A routine's own BEGIN … END is part of the routine's scope.
        return kind == StatementKind.BLOCK && rules.blockScopes() && (parentKind == null || !ROUTINES.contains(parentKind));
    }

    private Scope scope(ParserRuleContext node, Entry e) {
        Scope s = scopes.get(node);
        if (s == null) {
            Scope parent = node.getParent() == null ? root(node) : scopeOf(node.getParent());
            Statement st = e.statement();
            String display = st.name() != null ? st.name().toString()
                    : (st.kind() == StatementKind.BLOCK ? "block" : st.kind().name().toLowerCase(Locale.ROOT))
                    + "@L" + st.range().start().line();
            s = new Scope(parent, display.toUpperCase(Locale.ROOT) + "@" + st.range().start().offset(), display,
                    ROUTINES.contains(st.kind()));
            scopes.put(node, s);
            scan(node, s, st.kind());
        }
        return s;
    }

    private Scope root(ParseTree top) {
        if (root == null) {
            root = new Scope(null, "BATCH@" + base, "batch@L" + source.positionAt(Math.min(base, source.length())).line(),
                    false);
            if (top instanceof ParserRuleContext ctx) {
                scan(ctx, root, null);
            }
        }
        return root;
    }

    private Scope session() {
        if (session == null) {
            session = new Scope(null, "SESSION", "session", false);
        }
        return session;
    }

    // ----- lookup ---------------------------------------------------------------------------------

    /**
     * The variable or parameter {@code name} seen from {@code scope}, or {@code null} when it is not a
     * known variable. T-SQL {@code @name} is always a variable (of the routine or batch); MySQL
     * {@code @name} a session variable; {@code @@name} a system function.
     */
    public ColumnNode lookup(Scope scope, String name) {
        if (name == null || name.isBlank() || name.startsWith("@@")) {
            return null;
        }
        String upper = norm(name);
        if (name.startsWith("@")) {
            return switch (rules.atNames()) {
                case SESSION -> session().node(name);
                case ROUTINE -> {
                    Scope d = declaring(scope, upper);
                    yield (d != null ? d : outermost(scope)).node(name);
                }
                case NONE -> null;
            };
        }
        Scope d = declaring(scope, upper);
        if (d == null) {
            for (Scope s = scope; s != null && d == null; s = s.parent) {
                d = s.targets.containsKey(upper) ? s : null;
            }
        }
        return d == null ? null : d.node(name);
    }

    /** PostgreSQL {@code $n}: the n-th parameter of the routine, or {@code null}. */
    public ColumnNode positional(Scope scope, int n) {
        for (Scope s = scope; s != null; s = s.parent) {
            List<String> names = s.parameterNames();
            if (!names.isEmpty()) {
                return n >= 1 && n <= names.size() ? s.node(names.get(n - 1)) : null;
            }
        }
        return null;
    }

    /** The cursor {@code name} declared in {@code scope} or around it. */
    public Cursor cursor(Scope scope, String name) {
        String upper = norm(name);
        for (Scope s = scope; s != null; s = s.parent) {
            Cursor c = s.cursors.get(upper);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /**
     * Field {@code column} of the cursor FOR-loop record {@code name}: the output column of the loop's
     * query ({@code RESULT} node), or {@code null} when {@code name} is no loop record seen from {@code scope}.
     */
    public ColumnNode record(Scope scope, String name, String column) {
        String upper = norm(name);
        for (Scope s = scope; s != null; s = s.parent) {
            LoopRecord r = s.records.get(upper);
            if (r == null) {
                continue;
            }
            String key = r.ownerKey();
            String spelling = clean(column);
            if (key == null) {
                Cursor c = r.cursor() == null ? null : cursor(scope, r.cursor());
                if (c == null) {
                    return null;
                }
                key = c.ownerKey();
                for (String col : c.columns()) {
                    if (col.equalsIgnoreCase(spelling)) {
                        spelling = col;
                    }
                }
            }
            return new ColumnNode(OwnerKind.RESULT, "result", key, spelling);
        }
        return null;
    }

    /** The {@code return} variable of the routine around {@code scope}, or {@code null} outside routines. */
    public static ColumnNode returnNode(Scope scope) {
        for (Scope s = scope; s != null; s = s.parent) {
            if (s.routine) {
                return s.node(RETURN);
            }
        }
        return null;
    }

    /** Key of the scope of a routine defined by statement {@code routine} (as built here and by PL/pgSQL). */
    public static String routineKey(Statement routine) {
        return routine.name().toString().toUpperCase(Locale.ROOT) + "@" + routine.range().start().offset();
    }

    /** Node of variable or parameter {@code name} of the scope {@code key} shown as {@code display}. */
    public static ColumnNode variableNode(OwnerKind kind, String display, String key, String name) {
        return new ColumnNode(kind, display, "var:" + key + ":" + norm(name), clean(name));
    }

    private static Scope declaring(Scope scope, String upper) {
        for (Scope s = scope; s != null; s = s.parent) {
            if (s.declares(upper)) {
                return s;
            }
        }
        return null;
    }

    private static Scope outermost(Scope scope) {
        Scope routine = null;
        Scope last = scope;
        for (Scope s = scope; s != null; s = s.parent) {
            if (s.routine) {
                routine = s;
            }
            last = s;
        }
        return routine != null ? routine : last;
    }

    // ----- scanning a scope -------------------------------------------------------------------------

    /** Collects the parameters, declarations and assigned names of a scope, not entering nested scopes. */
    private void scan(ParserRuleContext node, Scope s, StatementKind kind) {
        List<ParserRuleContext> assignments = new ArrayList<>();
        List<ParserRuleContext> intos = new ArrayList<>();
        collect(node, kind, s, assignments, intos);
        Function<ParseTree, String> text = this::text;
        for (ParserRuleContext a : assignments) {
            for (Pair p : pairs(a, text)) {
                for (String t : targetNames(text.apply(p.target()), s)) {
                    if (rules.assignedAreVariables() || t.startsWith("@")) {
                        s.addTarget(t);
                    }
                }
            }
        }
        for (ParserRuleContext i : intos) {
            for (String t : intoNames(text(i))) {
                if (t != null) {
                    s.addTarget(targetName(t, s));
                }
            }
        }
    }

    private void collect(ParserRuleContext node, StatementKind containerKind, Scope s, List<ParserRuleContext> assignments,
                         List<ParserRuleContext> intos) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!(node.getChild(i) instanceof ParserRuleContext c)) {
                continue;
            }
            StatementKind kind = ir.kindOf(c);
            StatementKind inner = containerKind;
            if (kind != null && kind.isContainer()) {
                if (isScope(kind, containerKind)) {
                    continue;                             // a nested scope collects its own names
                }
                inner = kind;
            }
            String rule = ir.ruleName(c);
            if (rules.parameters().contains(rule)) {
                String name = parameterName(text(c));
                if (name != null) {
                    s.addParameter(name);
                }
            } else if (rules.declarations().contains(rule)) {
                declaredNames(text(c)).forEach(s::addDeclared);
            } else if (rules.assignments().contains(rule)) {
                assignments.add(c);
            } else if (rules.intoClauses().contains(rule) || rules.fetches().contains(rule)) {
                intos.add(c);
            } else if (rules.loopRecords().contains(rule)) {
                loopRecord(c, s);
                collect(c, inner, s, assignments, intos);
            } else {
                collect(c, inner, s, assignments, intos);
            }
        }
    }

    /** {@code r IN (SELECT …)} or {@code r IN c [(args)]} of a cursor FOR loop (not {@code i IN 1..n}). */
    private void loopRecord(ParserRuleContext header, Scope s) {
        String t = text(header);
        ParserRuleContext query = firstSelect(header);
        if (query != null) {
            Matcher m = LOOP_QUERY_RECORD.matcher(t);
            if (m.matches()) {
                int from = query.getStart() == null ? 0 : Math.max(0, query.getStart().getStartIndex());
                s.addRecord(m.group(1), new LoopRecord("result@" + Math.min(base + from, source.length()), null));
            }
            return;
        }
        Matcher m = LOOP_CURSOR_RECORD.matcher(t);
        if (!t.contains("..") && m.matches()) {
            s.addRecord(m.group(1), new LoopRecord(null, m.group(2).replaceAll("\\s+", "")));
        }
    }

    private ParserRuleContext firstSelect(ParserRuleContext node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof ParserRuleContext c) {
                if (ir.kindOf(c) == StatementKind.SELECT) {
                    return c;
                }
                ParserRuleContext found = firstSelect(c);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ----- text helpers (also used by LineageExtractor) ------------------------------------------------

    /** The name of a parameter declaration ({@code p IN DATE}, {@code IN p INT}, {@code @p date}). */
    public static String parameterName(String text) {
        Matcher m = PARAMETER.matcher(text);
        return m.lookingAt() ? m.group(1) : null;
    }

    /** The names a declaration declares; none for cursors, conditions, handlers and table variables. */
    public static List<String> declaredNames(String text) {
        if (HANDLER.matcher(text).matches()) {
            return List.of();
        }
        Matcher m = DECLARED.matcher(text);
        if (!m.lookingAt() || NOT_VARIABLES.contains(m.group(2).toUpperCase(Locale.ROOT))) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String n : m.group(1).split(",")) {
            names.add(n.strip());
        }
        return names;
    }

    /** {@code … INTO [STRICT] a, b, :c}: the target names in order ({@code null} for bind variables). */
    public static List<String> intoNames(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        int into = wordAtDepth0(upper, "INTO");
        if (into < 0 || upper.contains("OUTFILE") || upper.contains("DUMPFILE")) {
            return List.of();
        }
        String rest = text.substring(into + 4).strip();
        if (rest.regionMatches(true, 0, "STRICT", 0, 6) && rest.length() > 6 && Character.isWhitespace(rest.charAt(6))) {
            rest = rest.substring(6).strip();
        }
        List<String> names = new ArrayList<>();
        for (String part : splitDepth0(rest)) {
            Matcher m = TARGET.matcher(part.strip());
            if (m.lookingAt()) {
                names.add(m.group(1).isEmpty() ? m.group(2).replaceAll("\\s+", "") : null);
            }
        }
        return names;
    }

    /** The cursor name of {@code FETCH [NEXT FROM] c INTO …}, or {@code null}. */
    public static String fetchCursor(String text) {
        Matcher m = FETCH.matcher(text);
        if (!m.matches()) {
            return null;
        }
        String[] words = m.group(1).strip().split("\\s+");
        return words.length == 0 || words[words.length - 1].isEmpty() ? null : words[words.length - 1];
    }

    /** The cursor name of {@code CURSOR c IS …}, {@code DECLARE c CURSOR FOR …} or {@code OPEN c FOR …}. */
    public static String cursorName(String text) {
        Matcher m = CURSOR_NAME.matcher(text);
        return m.lookingAt() ? m.group(1) : null;
    }

    /** Variable names of an assignment target: {@code v}, {@code r.f} (record r), {@code t(i)}, {@code (a, b)}. */
    public static List<String> targetNames(String text, Scope scope) {
        String t = text.strip();
        if (t.startsWith("(") && t.endsWith(")")) {
            List<String> out = new ArrayList<>();
            for (String part : splitDepth0(t.substring(1, t.length() - 1))) {
                if (!part.isBlank()) {
                    out.add(targetName(part, scope));
                }
            }
            return out;
        }
        return List.of(targetName(t, scope));
    }

    /** {@code t(i)} → t; {@code r.field} → r when r is a variable of the scope, else the whole name. */
    public static String targetName(String text, Scope scope) {
        String t = text.strip();
        int cut = t.length();
        for (char c : new char[] {'(', '['}) {
            int i = t.indexOf(c, 1);
            if (i > 0) {
                cut = Math.min(cut, i);
            }
        }
        t = t.substring(0, cut).replaceAll("\\s+", "");
        int dot = t.indexOf('.');
        if (dot > 0 && scope != null) {
            String first = t.substring(0, dot);
            if (declaring(scope, norm(first)) != null) {
                return first;
            }
        }
        return t;
    }

    /**
     * {@code target op expression} pairs of an assignment node: a child that is an assignment operator
     * between a target and an expression. Children that are not part of a pair are searched too
     * ({@code SET a = 1, b = 2} nests the later pairs).
     */
    static List<Pair> pairs(ParserRuleContext node, Function<ParseTree, String> text) {
        List<Pair> out = new ArrayList<>();
        pairs(node, text, out, 0);
        return out;
    }

    private static void pairs(ParserRuleContext node, Function<ParseTree, String> text, List<Pair> out, int depth) {
        Set<ParseTree> used = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 1; i + 1 < node.getChildCount(); i++) {
            ParseTree op = node.getChild(i);
            String opText = op instanceof TerminalNode t ? t.getText() : text.apply(op).strip();
            if (ASSIGNMENT_OPERATORS.contains(opText) && node.getChild(i + 1) instanceof ParserRuleContext expr
                    && !used.contains(node.getChild(i - 1))) {
                out.add(new Pair(node.getChild(i - 1), expr, opText));
                used.add(node.getChild(i - 1));
                used.add(op);
                used.add(expr);
            }
        }
        if (depth >= 4) {
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i) instanceof ParserRuleContext c && !used.contains(c)) {
                pairs(c, text, out, depth + 1);
            }
        }
    }

    /**
     * Turns the lineage of a query into an assignment of variables: edges into the query result
     * ({@code resultKey}) go to the variables by output position instead (one variable takes every
     * column), and the result's filters go to each variable's rows. Ranges are re-based on {@code source}.
     */
    public static com.github.amber.core.lineage.StatementLineage assign(com.github.amber.core.lineage.StatementLineage lineage,
                                                                         String resultKey, List<ColumnNode> vars,
                                                                         SourceText source) {
        Map<String, ColumnNode> byOutput = new LinkedHashMap<>();
        List<ColumnNode> outputs = new ArrayList<>();
        List<ColumnNode> outs = lineage.outputs();
        for (int i = 0; i < outs.size(); i++) {
            ColumnNode to = vars.size() == 1 ? vars.get(0) : i < vars.size() ? vars.get(i) : null;
            if (to != null && outs.get(i).ownerKey().equals(resultKey)) {
                byOutput.putIfAbsent(outs.get(i).key(), to);
                if (outputs.stream().noneMatch(o -> o.key().equals(to.key()))) {
                    outputs.add(to);
                }
            }
        }
        List<com.github.amber.core.lineage.ColumnEdge> edges = new ArrayList<>();
        for (var e : lineage.edges()) {
            var r = e.range() == null ? null : source.range(e.range().start().offset(), e.range().end().offset());
            if (!e.to().ownerKey().equals(resultKey)) {
                edges.add(new com.github.amber.core.lineage.ColumnEdge(e.from(), e.to(), e.transform(), e.role(),
                        e.confidence(), r));
            } else if (e.to().isRows()) {
                for (ColumnNode v : outputs) {
                    edges.add(new com.github.amber.core.lineage.ColumnEdge(e.from(),
                            new ColumnNode(v.ownerKind(), v.owner(), v.ownerKey(), ColumnNode.ROWS), null, e.role(),
                            e.confidence(), r));
                }
            } else {
                ColumnNode v = byOutput.get(e.to().key());
                if (v != null && !v.key().equals(e.from().key())) {
                    edges.add(new com.github.amber.core.lineage.ColumnEdge(e.from(), v, e.transform(), null,
                            e.confidence(), r));
                }
            }
        }
        return new com.github.amber.core.lineage.StatementLineage(outputs, edges);
    }

    /** Upper-case variable key: quotes and brackets removed. */
    static String norm(String name) {
        return clean(name).toUpperCase(Locale.ROOT);
    }

    private static String clean(String name) {
        return name.strip().replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
    }

    private static int wordAtDepth0(String upper, String word) {
        int depth = 0;
        for (int i = 0; i + word.length() <= upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith(word, i)
                    && (i == 0 || !Character.isLetterOrDigit(upper.charAt(i - 1)) && upper.charAt(i - 1) != '_')
                    && (i + word.length() == upper.length() || !Character.isLetterOrDigit(upper.charAt(i + word.length()))
                    && upper.charAt(i + word.length()) != '_')) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitDepth0(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(text.substring(from));
        return parts;
    }

    private String text(ParseTree node) {
        if (node instanceof TerminalNode t) {
            return t.getText();
        }
        ParserRuleContext ctx = (ParserRuleContext) node;
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        int from = start == null || start.getStartIndex() < 0 ? 0 : start.getStartIndex();
        int to = stop == null || stop.getStopIndex() < from ? from : stop.getStopIndex() + 1;
        int a = Math.min(base + from, source.length());
        int b = Math.min(base + to, source.length());
        return source.text().substring(a, Math.max(a, b));
    }
}
