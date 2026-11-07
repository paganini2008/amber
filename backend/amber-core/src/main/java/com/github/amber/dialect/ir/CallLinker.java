package com.github.amber.dialect.ir;

import com.github.amber.core.Dialect;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.Confidence;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.core.ir.StatementKind;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.split.ScanToken;
import com.github.amber.dialect.split.ScannerOptions;
import com.github.amber.dialect.split.SqlScanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows calls into procedures and functions the script defines: a call statement (or a function call
 * inside a query) gets the data objects the routine's body reads and writes, as refs marked
 * {@link ObjectRef#via()} at the call's range. Lifecycles, data flow, references, diff and documents then
 * show that "step 26 writes dw.daily through dw.p_load". Nested calls are followed transitively; objects the
 * routine creates for itself (local temp tables) stay inside it. Routines defined outside the script are
 * not known and keep only their EXECUTE ref.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class CallLinker {

    private static final Set<ObjectKind> DATA = Set.of(ObjectKind.TABLE, ObjectKind.TEMP_TABLE, ObjectKind.TABLE_VARIABLE,
            ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW, ObjectKind.SEQUENCE, ObjectKind.SYNONYM);
    private static final Set<StatementKind> DEFINITIONS = Set.of(StatementKind.CREATE_PROCEDURE,
            StatementKind.CREATE_FUNCTION, StatementKind.ROUTINE);

    private final Dialect dialect;
    private final SourceText source;
    /** Routine key (upper-case, qualified as written) → its definition. */
    private final Map<String, Statement> routines = new LinkedHashMap<>();
    private final Map<Statement, List<ObjectRef>> effects = new LinkedHashMap<>();

    private CallLinker(Dialect dialect, SourceText source) {
        this.dialect = dialect;
        this.source = source;
    }

    /** Adds the via-refs to every call of a routine defined in {@code statements}. */
    public static void link(Dialect dialect, SourceText source, List<Statement> statements) {
        CallLinker l = new CallLinker(dialect, source);
        statements.forEach(s -> s.forEach(st -> {
            if (DEFINITIONS.contains(st.kind()) && st.name() != null) {
                l.routines.putIfAbsent(st.name().key(), st);
            }
        }));
        if (l.routines.isEmpty()) {
            return;
        }
        List<Statement> all = new ArrayList<>();
        statements.forEach(s -> s.forEach(all::add));
        for (Statement st : all) {
            l.linkCalls(st);
        }
    }

    private void linkCalls(Statement st) {
        List<ObjectRef> calls = new ArrayList<>();
        for (ObjectRef r : st.refs()) {
            if (r.access() == Access.EXECUTE && r.via() == null) {
                calls.add(r);
            }
        }
        if (st.children().isEmpty()) {
            calls.addAll(functionCallsIn(st, calls));
        }
        Set<String> seen = new HashSet<>();
        for (ObjectRef call : calls) {
            Statement routine = find(call.name());
            if (routine == null || isInside(st, routine) && st != routine) {
                continue;                                 // recursion: the body is already the routine itself
            }
            if (st.refs().stream().noneMatch(r -> r == call)) {
                st.addRef(call);                          // a function call found in the statement text
            }
            for (ObjectRef e : effects(routine, null)) {
                if (seen.add(e.name().key() + "|" + e.access() + "|" + call.range().start().offset())) {
                    st.addRef(new ObjectRef(e.name(), e.kind(), e.access(), call.range(), Confidence.INFERRED,
                            routine.name()));
                }
            }
        }
    }

    /** The routine a call names: the same qualified name, or the only routine with that simple name. */
    private Statement find(ObjectName called) {
        Statement exact = routines.get(called.key());
        if (exact != null) {
            return exact;
        }
        Statement match = null;
        for (Statement r : routines.values()) {
            if (r.name().sameObject(called) || r.name().simpleKey().equals(called.simpleKey())
                    && (called.parts().size() == 1 || r.name().parts().size() == 1)) {
                if (match != null && match != r) {
                    return null;                          // ambiguous: two routines with that name
                }
                match = r;
            }
        }
        return match;
    }

    /**
     * Data objects a routine reads and writes, including through every routine it reaches by nested calls
     * (cycle-safe: the reachable set is computed first); each routine's own temporaries are left out.
     */
    private List<ObjectRef> effects(Statement routine, Set<Statement> unused) {
        List<ObjectRef> cached = effects.get(routine);
        if (cached != null) {
            return cached;
        }
        List<ObjectRef> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Statement r : reachable(routine)) {
            for (ObjectRef e : ownEffects(r)) {
                if (keys.add(e.name().key() + "|" + e.access())) {
                    out.add(e);
                }
            }
        }
        effects.put(routine, out);
        return out;
    }

    /** The routine and every routine of the script it calls, directly or indirectly. */
    private List<Statement> reachable(Statement routine) {
        List<Statement> order = new ArrayList<>();
        java.util.ArrayDeque<Statement> queue = new java.util.ArrayDeque<>(List.of(routine));
        Set<Statement> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Statement r = queue.poll();
            if (!seen.add(r)) {
                continue;
            }
            order.add(r);
            r.forEach(st -> {
                for (ObjectRef ref : st.refs()) {
                    if (ref.access() == Access.EXECUTE && ref.via() == null) {
                        Statement inner = find(ref.name());
                        if (inner != null && !seen.contains(inner)) {
                            queue.add(inner);
                        }
                    }
                }
            });
        }
        return order;
    }

    private final Map<Statement, List<ObjectRef>> own = new LinkedHashMap<>();

    /** Objects a routine's own statements read and write, without the temporaries it creates itself. */
    private List<ObjectRef> ownEffects(Statement routine) {
        return own.computeIfAbsent(routine, r -> {
            List<ObjectRef> direct = new ArrayList<>();
            Set<String> created = new HashSet<>();
            r.forEach(st -> {
                for (ObjectRef ref : st.refs()) {
                    if (DATA.contains(ref.kind()) && ref.via() == null) {
                        direct.add(ref);
                        if (ref.access() == Access.CREATE) {
                            created.add(ref.name().key());
                        }
                    }
                }
            });
            direct.removeIf(ref -> created.contains(ref.name().key()));
            return direct;
        });
    }

    /** {@code name(…)} in a leaf statement's text that names a routine of the script (function calls in queries). */
    private List<ObjectRef> functionCallsIn(Statement st, List<ObjectRef> known) {
        if (DEFINITIONS.contains(st.kind())) {
            return List.of();
        }
        List<ObjectRef> out = new ArrayList<>();
        String text = source.slice(st.range());
        int base = st.range().start().offset();
        List<ScanToken> t = new ArrayList<>();
        SqlScanner sc = new SqlScanner(text, ScannerOptions.forDialect(dialect));
        for (ScanToken tok = sc.next(); tok != null; tok = sc.next()) {
            t.add(tok);
        }
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).type() != ScanToken.Type.WORD || i > 0 && t.get(i - 1).isSymbol('.')) {
                continue;
            }
            int j = i;
            StringBuilder name = new StringBuilder(t.get(i).text());
            while (j + 2 < t.size() && t.get(j + 1).isSymbol('.') && t.get(j + 2).type() == ScanToken.Type.WORD) {
                name.append('.').append(t.get(j + 2).text());
                j += 2;
            }
            if (j + 1 >= t.size() || !t.get(j + 1).isSymbol('(')) {
                continue;
            }
            ObjectName n = ObjectName.parse(dialect, name.toString());
            if (n == null || find(n) == null) {
                continue;
            }
            TextRange r = source.range(base + t.get(i).start(), base + t.get(j).end());
            boolean already = known.stream().anyMatch(k -> k.range().start().offset() == r.start().offset());
            if (!already) {
                out.add(new ObjectRef(n, ObjectKind.FUNCTION, Access.EXECUTE, r, Confidence.INFERRED));
            }
            i = j;
        }
        return out;
    }

    private static boolean isInside(Statement st, Statement container) {
        return container.range().start().offset() <= st.range().start().offset()
                && st.range().end().offset() <= container.range().end().offset();
    }
}
