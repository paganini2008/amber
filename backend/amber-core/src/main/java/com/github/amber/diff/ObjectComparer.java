package com.github.amber.diff;

import com.github.amber.core.i18n.Messages;
import com.github.amber.core.analysis.ObjectLifecycle;
import com.github.amber.core.ir.Access;
import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Statement;
import com.github.amber.diff.DiffResult.ObjectChange;
import com.github.amber.diff.DiffResult.ObjectChangeKind;
import com.github.amber.diff.DiffResult.Risk;
import com.github.amber.diff.DiffResult.Scope;
import com.github.amber.diff.StatementAligner.P;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Object-level impact: tables, views and temp tables newly read, written, created or dropped, accesses
 * that appear or disappear, lifecycle role changes over the whole script, and the objects each modified
 * statement touches differently.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class ObjectComparer {

    private static final Set<Access> DESTRUCTIVE = EnumSet.of(Access.DELETE, Access.TRUNCATE, Access.DROP);
    private static final Set<Access> WRITES = EnumSet.of(Access.INSERT, Access.UPDATE, Access.MERGE, Access.CREATE,
            Access.ALTER);

    private ObjectComparer() {
    }

    static List<ObjectChange> compare(DiffContext ctx) {
        List<ObjectChange> out = new ArrayList<>();
        scriptLevel(ctx, out);
        for (P p : ctx.modified()) {
            statementLevel(ctx, p, out);
        }
        return out;
    }

    // ----- whole script ----------------------------------------------------------------------------

    private static void scriptLevel(DiffContext ctx, List<ObjectChange> out) {
        List<ObjectLifecycle> afterLeft = new ArrayList<>(ctx.after.lifecycles);
        Map<ObjectLifecycle, ObjectLifecycle> matched = new LinkedHashMap<>();
        List<ObjectLifecycle> removed = new ArrayList<>();
        for (ObjectLifecycle b : ctx.before.lifecycles) {
            ObjectLifecycle a = find(afterLeft, b);
            if (a == null) {
                removed.add(b);
            } else {
                afterLeft.remove(a);
                matched.put(b, a);
            }
        }
        for (ObjectLifecycle b : removed) {
            ObjectLifecycle.Event e = b.events().get(0);
            String msg = Messages.t("diff.object.noLongerUsed", b.name(), accesses(b));
            out.add(change(ctx, ObjectChangeKind.REMOVED, b, null, msg, e, null));
            if (!b.createdInScript() && accessSet(b).stream().anyMatch(Access::writesData)) {
                ctx.reason(Risk.MEDIUM, "TARGET_DROPPED", Messages.t("diff.object.noLongerWrites", b.name()), e.step(), null);
            }
        }
        for (Map.Entry<ObjectLifecycle, ObjectLifecycle> m : matched.entrySet()) {
            compareObject(ctx, m.getKey(), m.getValue(), out);
        }
        for (ObjectLifecycle a : afterLeft) {
            ObjectLifecycle.Event e = a.events().get(0);
            String msg = Messages.t("diff.object.newlyUsed", a.name(), accesses(a));
            out.add(change(ctx, ObjectChangeKind.ADDED, a, null, msg, null, e));
            for (Access access : accessSet(a)) {
                accessRisk(ctx, a, access, firstEvent(a, access));
            }
        }
    }

    private static void compareObject(DiffContext ctx, ObjectLifecycle b, ObjectLifecycle a, List<ObjectChange> out) {
        Set<Access> ab = accessSet(b);
        Set<Access> aa = accessSet(a);
        for (Access x : aa) {
            if (!ab.contains(x)) {
                ObjectLifecycle.Event e = firstEvent(a, x);
                out.add(change(ctx, ObjectChangeKind.ACCESS_ADDED, a, x, verb(x, true, a), null, e));
                accessRisk(ctx, a, x, e);
            }
        }
        for (Access x : ab) {
            if (!aa.contains(x)) {
                ObjectLifecycle.Event e = firstEvent(b, x);
                out.add(change(ctx, ObjectChangeKind.ACCESS_REMOVED, b, x, verb(x, false, b), e, null));
                if (!b.createdInScript() && (x.writesData() || x == Access.DROP)) {
                    ctx.reason(Risk.MEDIUM, "WRITE_REMOVED", Messages.t("diff.object.noLongerAccess", b.name(), x), e.step(), null);
                }
            }
        }
        if (b.role() != a.role()) {
            String msg = Messages.t("diff.object.role", a.name(), b.role(), a.role());
            ObjectChange c = new ObjectChange(Scope.SCRIPT, ObjectChangeKind.ROLE_CHANGED, a.name().toString(),
                    a.name().key(), a.kind().name(), null, b.role().name(), a.role().name(), msg,
                    b.events().get(0).step(), a.events().get(0).step(), b.events().get(0).range(),
                    a.events().get(0).range());
            out.add(c);
            ctx.reason(a.createdInScript() ? Risk.LOW : Risk.MEDIUM, "ROLE_CHANGED", msg,
                    c.beforeStatement(), c.afterStatement());
        }
        if (b.kind() != a.kind()) {
            String msg = Messages.t("diff.object.kind", a.name(), b.kind(), a.kind());
            out.add(new ObjectChange(Scope.SCRIPT, ObjectChangeKind.KIND_CHANGED, a.name().toString(), a.name().key(),
                    a.kind().name(), null, b.role().name(), a.role().name(), msg, b.events().get(0).step(),
                    a.events().get(0).step(), b.events().get(0).range(), a.events().get(0).range()));
            ctx.reason(Risk.MEDIUM, "KIND_CHANGED", msg, b.events().get(0).step(), a.events().get(0).step());
        }
    }

    private static void accessRisk(DiffContext ctx, ObjectLifecycle a, Access x, ObjectLifecycle.Event e) {
        boolean preexisting = !a.createdInScript();
        if (DESTRUCTIVE.contains(x) && preexisting) {
            ctx.reason(Risk.HIGH, "DESTRUCTIVE_ACCESS", Messages.t("diff.object.destructive", a.name(), x), null, e.step());
        } else if (preexisting && (WRITES.contains(x) && x != Access.CREATE)) {
            ctx.reason(Risk.HIGH, "NEW_TARGET", Messages.t("diff.object.newTarget", a.name(), x), null, e.step());
        } else if (preexisting && x == Access.READ) {
            ctx.reason(Risk.MEDIUM, "NEW_SOURCE", Messages.t("diff.object.newSource", a.name()), null, e.step());
        }
    }

    private static ObjectLifecycle find(List<ObjectLifecycle> list, ObjectLifecycle b) {
        for (ObjectLifecycle a : list) {
            if (a.name().key().equals(b.name().key())) {
                return a;
            }
        }
        ObjectLifecycle only = null;
        for (ObjectLifecycle a : list) {
            if (a.name().sameObject(b.name())) {
                if (only != null) {
                    return null;
                }
                only = a;
            }
        }
        return only;
    }

    private static Set<Access> accessSet(ObjectLifecycle l) {
        Set<Access> s = EnumSet.noneOf(Access.class);
        l.events().forEach(e -> s.add(e.access()));
        return s;
    }

    private static String accesses(ObjectLifecycle l) {
        return String.join(", ", accessSet(l).stream().map(Enum::name).toList());
    }

    private static ObjectLifecycle.Event firstEvent(ObjectLifecycle l, Access x) {
        return l.events().stream().filter(e -> e.access() == x).findFirst().orElse(l.events().get(0));
    }

    private static String verb(Access x, boolean added, ObjectLifecycle l) {
        String n = l.name().toString();
        String which = switch (x) {
            case READ, CREATE, DROP -> x.name();
            default -> "other";
        };
        return Messages.t("diff.access." + (added ? "added." : "removed.") + which, n, x);
    }

    private static ObjectChange change(DiffContext ctx, ObjectChangeKind kind, ObjectLifecycle l, Access access,
                                       String msg, ObjectLifecycle.Event before,
                                       ObjectLifecycle.Event after) {
        String role = l.role().name();
        return new ObjectChange(Scope.SCRIPT, kind, l.name().toString(), l.name().key(), l.kind().name(),
                access == null ? null : access.name(), before == null ? null : role, after == null ? null : role,
                msg, before == null ? null : before.step(), after == null ? null : after.step(),
                before == null ? null : before.range(), after == null ? null : after.range());
    }

    // ----- one modified statement ------------------------------------------------------------------

    private static void statementLevel(DiffContext ctx, P p, List<ObjectChange> out) {
        Map<String, ObjectRef> rb = refs(p.a.st);
        Map<String, ObjectRef> ra = refs(p.b.st);
        for (Map.Entry<String, ObjectRef> e : ra.entrySet()) {
            if (!rb.containsKey(e.getKey())) {
                ObjectRef r = e.getValue();
                out.add(new ObjectChange(Scope.STATEMENT, ObjectChangeKind.REF_ADDED, r.name().toString(),
                        r.name().key(), r.kind().name(), r.access().name(), null, null,
                        Messages.t("diff.ref.now." + refKey(r.access()), p.b.label(), r.name(), r.access()), p.a.id, p.b.id, null, r.range()));
            }
        }
        for (Map.Entry<String, ObjectRef> e : rb.entrySet()) {
            if (!ra.containsKey(e.getKey())) {
                ObjectRef r = e.getValue();
                out.add(new ObjectChange(Scope.STATEMENT, ObjectChangeKind.REF_REMOVED, r.name().toString(),
                        r.name().key(), r.kind().name(), r.access().name(), null, null,
                        Messages.t("diff.ref.noLonger." + refKey(r.access()), p.a.label(), r.name(), r.access()), p.a.id, p.b.id, r.range(),
                        null));
            }
        }
        Set<String> wb = writes(rb);
        Set<String> wa = writes(ra);
        if (!wb.equals(wa) && !(wb.isEmpty() && wa.isEmpty())) {
            ctx.reason(Risk.HIGH, "TARGET_CHANGED", Messages.t("diff.ref.targetChanged", p.b.st.kind(), names(wb, rb),
                    names(wa, ra)), p.a.id, p.b.id);
        }
        Set<String> sb = reads(rb);
        Set<String> sa = reads(ra);
        if (!sb.equals(sa)) {
            ctx.reason(Risk.MEDIUM, "SOURCES_CHANGED", Messages.t("diff.ref.sourcesChanged", p.b.label(), names(sa, ra),
                    names(sb, rb)), p.a.id, p.b.id);
        }
    }

    /** Data-object refs of a statement keyed by access and name; CTEs and routines left out. */
    private static Map<String, ObjectRef> refs(Statement st) {
        Set<String> ctes = new HashSet<>();
        st.ctes().forEach(c -> ctes.add(c.toUpperCase(Locale.ROOT)));
        Map<String, ObjectRef> out = new LinkedHashMap<>();
        for (ObjectRef r : st.refs()) {
            if (r.kind() == ObjectKind.CTE || ctes.contains(r.name().key())) {
                continue;
            }
            out.putIfAbsent(r.access() + "|" + r.name().key(), r);
        }
        return out;
    }

    private static Set<String> writes(Map<String, ObjectRef> refs) {
        Set<String> out = new TreeSet<>();
        refs.forEach((k, r) -> {
            if (r.access() != Access.READ && r.access() != Access.EXECUTE) {
                out.add(k);
            }
        });
        return out;
    }

    private static Set<String> reads(Map<String, ObjectRef> refs) {
        Set<String> out = new TreeSet<>();
        refs.forEach((k, r) -> {
            if (r.access() == Access.READ) {
                out.add(k);
            }
        });
        return out;
    }

    private static String names(Set<String> keys, Map<String, ObjectRef> refs) {
        if (keys.isEmpty()) {
            return Messages.t("diff.ref.none");
        }
        return String.join(", ", keys.stream().map(k -> refs.get(k).name().toString()).toList());
    }

    /** Message key suffix of an access in a statement: its own wording, or "other" (the SQL verb + s). */
    private static String refKey(Access a) {
        return switch (a) {
            case READ, CREATE, DROP, EXECUTE -> a.name();
            default -> "other";
        };
    }
}
