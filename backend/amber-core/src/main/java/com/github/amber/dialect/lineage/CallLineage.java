package com.github.amber.dialect.lineage;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.lineage.Catalog;
import com.github.amber.core.lineage.ColumnEdge;
import com.github.amber.core.lineage.ColumnNode;
import com.github.amber.core.lineage.ColumnNode.OwnerKind;
import com.github.amber.core.lineage.StatementLineage;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayList;
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
 * Lineage through calls of routines the script defines: argument expressions flow into the routine's
 * {@link OwnerKind#PARAMETER} nodes (by position, or by name for {@code p => x}, {@code p := x} and
 * T-SQL {@code @p = x}), OUT / IN OUT / INOUT / T-SQL OUTPUT parameters flow back into the caller's
 * variable, and a function call inside an expression reads the function's {@code return} variable
 * ({@link VariableScopes#returnNode}).
 *
 * <p>The extractor sees one statement at a time and cannot know whether a called name is a routine of the
 * script, so it writes placeholder nodes (owner = the called name as written, owner key
 * {@code call:in@<offset>}, {@code call:out@<offset>} or {@code call:ret@<offset>}, column = the argument
 * slot {@code #1}, a parameter name, or {@code return}) for every call of a name the script may define
 * ({@link #collectRoutineNames}). Once the whole script is built, {@link #link} replaces them with the
 * routine's nodes, or drops them when the call names no routine of the script (or an ambiguous one), when
 * the slot names no parameter, or when the direction does not fit the parameter's mode (an argument does
 * not flow into an OUT parameter; an IN parameter does not flow back).
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class CallLineage {

    private static final String PREFIX = "call:";
    private static final String IN = PREFIX + "in@";
    private static final String OUT = PREFIX + "out@";
    private static final String RET = PREFIX + "ret@";
    private static final Set<String> DEFINING = Set.of("PROCEDURE", "PROC", "FUNCTION");
    private static final Pattern LEADING_MODE = Pattern.compile("(?is)\\s*(IN\\s+OUT|INOUT|IN|OUT|VARIADIC)\\s+(.*)");
    private static final Pattern OUTPUT_WORD = Pattern.compile("(?is).*\\b(OUTPUT|OUT)\\b.*");

    /** Direction of a parameter. */
    enum Mode {
        IN, OUT, INOUT
    }

    /** One declared parameter of a routine. */
    record Param(String name, Mode mode) {
    }

    private CallLineage() {
    }

    // ----- placeholders (written by LineageExtractor) ----------------------------------------------

    /** The argument in {@code slot} of the call at {@code offset}, as read by the routine. */
    static ColumnNode argument(String callee, int offset, String slot) {
        return new ColumnNode(OwnerKind.PARAMETER, callee, IN + offset, slot);
    }

    /** The argument in {@code slot} of the call at {@code offset}, as written back by the routine. */
    static ColumnNode outArgument(String callee, int offset, String slot) {
        return new ColumnNode(OwnerKind.PARAMETER, callee, OUT + offset, slot);
    }

    /** The value of the function call at {@code offset}. */
    static ColumnNode result(String callee, int offset) {
        return new ColumnNode(OwnerKind.PARAMETER, callee, RET + offset, VariableScopes.RETURN);
    }

    static boolean isPlaceholder(ColumnNode n) {
        return n.ownerKey().startsWith(PREFIX);
    }

    // ----- before the script is built ------------------------------------------------------------

    /** Notes in {@code catalog} the simple names after {@code PROCEDURE}, {@code PROC} and {@code FUNCTION}. */
    public static void collectRoutineNames(Dialect dialect, SourceText source, Catalog catalog) {
        SqlScanner sc = new SqlScanner(source.text(), ScannerOptions.forDialect(dialect));
        ScanToken prev = null;
        ScanToken t;
        while ((t = sc.next()) != null) {
            if (prev != null && prev.type() == ScanToken.Type.WORD && DEFINING.contains(prev.upper()) && isName(t)) {
                String last = t.text();
                while (sc.peek() != null && sc.peek().isSymbol('.')) {
                    sc.next();
                    ScanToken part = sc.peek();
                    if (part == null || !isName(part)) {
                        break;
                    }
                    last = sc.next().text();
                }
                catalog.addRoutine(clean(last));
                prev = null;
                continue;
            }
            prev = t;
        }
    }

    private static boolean isName(ScanToken t) {
        return t.type() == ScanToken.Type.WORD || t.type() == ScanToken.Type.QUOTED_IDENTIFIER;
    }

    // ----- after the script is built -------------------------------------------------------------

    /**
     * Replaces the placeholders in every statement's lineage with the nodes of the routine {@code find}
     * returns for the called name, or drops them.
     */
    public static void link(Dialect dialect, SourceText source, List<Statement> statements,
                            Function<ObjectName, Statement> find) {
        Map<Statement, List<Param>> params = new IdentityHashMap<>();
        statements.forEach(top -> top.forEach(st -> {
            StatementLineage l = st.lineage();
            if (l == null || !hasPlaceholder(l)) {
                return;
            }
            List<ColumnEdge> edges = new ArrayList<>();
            Map<String, ColumnEdge> unique = new LinkedHashMap<>();
            List<ColumnNode> written = new ArrayList<>();      // caller variables an OUT parameter writes
            for (ColumnEdge e : l.edges()) {
                ColumnNode from = map(e.from(), dialect, source, find, params);
                ColumnNode to = map(e.to(), dialect, source, find, params);
                if (from == null || to == null || from.key().equals(to.key())) {
                    continue;
                }
                ColumnEdge mapped = from == e.from() && to == e.to() ? e
                        : new ColumnEdge(from, to, e.transform(), e.role(), e.confidence(), e.range());
                unique.putIfAbsent(from.key() + ">" + to.key() + ">" + e.transform() + ">" + e.role(), mapped);
                if (e.from().ownerKey().startsWith(OUT)) {
                    written.add(to);
                }
            }
            edges.addAll(unique.values());
            Map<String, ColumnNode> outputs = new LinkedHashMap<>();
            for (ColumnNode o : l.outputs()) {
                ColumnNode m = map(o, dialect, source, find, params);
                if (m != null) {
                    outputs.putIfAbsent(m.key(), m);
                }
            }
            written.forEach(w -> outputs.putIfAbsent(w.key(), w));
            st.setLineage(outputs.isEmpty() && edges.isEmpty() ? null
                    : new StatementLineage(new ArrayList<>(outputs.values()), edges));
        }));
    }

    private static boolean hasPlaceholder(StatementLineage l) {
        for (ColumnEdge e : l.edges()) {
            if (isPlaceholder(e.from()) || isPlaceholder(e.to())) {
                return true;
            }
        }
        return l.outputs().stream().anyMatch(CallLineage::isPlaceholder);
    }

    private static ColumnNode map(ColumnNode n, Dialect dialect, SourceText source, Function<ObjectName, Statement> find,
                                  Map<Statement, List<Param>> cache) {
        if (!isPlaceholder(n)) {
            return n;
        }
        ObjectName called = ObjectName.parse(dialect, n.owner());
        Statement routine = called == null ? null : find.apply(called);
        if (routine == null || routine.name() == null) {
            return null;
        }
        String display = routine.name().toString();
        String key = VariableScopes.routineKey(routine);
        if (n.ownerKey().startsWith(RET)) {
            return routine.kind() == StatementKind.CREATE_PROCEDURE ? null
                    : VariableScopes.variableNode(OwnerKind.VARIABLE, display, key, VariableScopes.RETURN);
        }
        List<Param> params = cache.computeIfAbsent(routine, r -> parameters(dialect, source, r));
        Param p = slot(params, n.column());
        if (p == null) {
            return null;
        }
        boolean in = n.ownerKey().startsWith(IN);
        if (in && p.mode() == Mode.OUT || !in && p.mode() == Mode.IN) {
            return null;
        }
        return VariableScopes.variableNode(OwnerKind.PARAMETER, display, key, p.name());
    }

    /** The parameter a slot names: {@code #n} by position, else by name. */
    private static Param slot(List<Param> params, String slot) {
        if (slot.startsWith("#")) {
            int i = Integer.parseInt(slot.substring(1)) - 1;
            return i >= 0 && i < params.size() ? params.get(i) : null;
        }
        String upper = VariableScopes.norm(slot);
        for (Param p : params) {
            if (VariableScopes.norm(p.name()).equals(upper)) {
                return p;
            }
        }
        return null;
    }

    // ----- routine headers -----------------------------------------------------------------------

    /**
     * Parameters of a routine in declaration order, read from its header: the parenthesized list after its
     * name, or (T-SQL) the list up to {@code AS}.
     */
    static List<Param> parameters(Dialect dialect, SourceText source, Statement routine) {
        String text = source.slice(routine.range());
        int start = routine.range().start().offset();
        int i = -1;
        for (ObjectRef r : routine.refs()) {
            if (r.via() == null && r.name().sameObject(routine.name()) && r.range().start().offset() >= start
                    && r.range().end().offset() <= routine.range().end().offset()) {
                i = r.range().end().offset() - start;
                break;
            }
        }
        if (i < 0) {
            Matcher m = Pattern.compile("(?i)" + Pattern.quote(routine.name().simpleName())).matcher(text);
            if (!m.find()) {
                return List.of();
            }
            i = m.end();
        }
        i = skipBlank(text, i);
        String list;
        if (i < text.length() && text.charAt(i) == '(') {
            int close = ArgumentText.closing(text, i);
            if (close < 0) {
                return List.of();
            }
            list = text.substring(i + 1, close);
        } else if (dialect == Dialect.SQLSERVER && i < text.length() && text.charAt(i) == '@') {
            int as = ArgumentText.wordAtDepth0(text, i, text.length(), "AS");
            list = text.substring(i, as < 0 ? text.length() : as);
            int with = ArgumentText.wordAtDepth0(list, 0, list.length(), "WITH");
            list = with < 0 ? list : list.substring(0, with);
        } else {
            return List.of();
        }
        List<Param> out = new ArrayList<>();
        for (int[] span : ArgumentText.split(list, 0, list.length())) {
            String p = list.substring(span[0], span[1]).strip();
            if (p.isEmpty()) {
                continue;
            }
            out.add(param(dialect, p, out.size()));
        }
        return out;
    }

    private static Param param(Dialect dialect, String text, int index) {
        Mode mode = Mode.IN;
        String rest = text;
        Matcher lead = LEADING_MODE.matcher(text);
        if (lead.matches()) {
            mode = mode(lead.group(1));
            rest = lead.group(2);
        }
        String name = VariableScopes.parameterName(text);
        if (dialect == Dialect.POSTGRESQL && rest.strip().replaceAll("\\(.*\\)", "").strip().split("\\s+").length == 1
                && !rest.contains("=") && !rest.toUpperCase(Locale.ROOT).contains("DEFAULT")) {
            name = "$" + (index + 1);                 // unnamed: only a type
        }
        if (name == null) {
            return new Param("$" + (index + 1), mode);
        }
        String afterName = rest.strip().substring(Math.min(rest.strip().length(), name.length()));
        if (name.startsWith("@")) {
            if (OUTPUT_WORD.matcher(afterName).matches()) {
                mode = Mode.INOUT;                    // T-SQL OUTPUT parameters also receive the argument
            }
        } else if (!lead.matches()) {
            Matcher after = Pattern.compile("(?is)\\s*(IN\\s+OUT|OUT|IN)\\s.*").matcher(afterName + " ");
            if (after.matches()) {
                mode = mode(after.group(1));          // PL/SQL: p IN OUT type
            }
        }
        return new Param(name, mode);
    }

    private static Mode mode(String word) {
        String w = word.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (w) {
            case "OUT" -> Mode.OUT;
            case "IN OUT", "INOUT" -> Mode.INOUT;
            default -> Mode.IN;
        };
    }

    private static int skipBlank(String text, int i) {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("--", i)) {
                int nl = text.indexOf('\n', i);
                i = nl < 0 ? text.length() : nl + 1;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static String clean(String name) {
        return name.strip().replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
    }
}
