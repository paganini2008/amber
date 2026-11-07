package com.github.amber.core.analysis;

import com.github.amber.core.ir.ObjectKind;
import com.github.amber.core.ir.ObjectRef;
import com.github.amber.core.ir.Script;
import com.github.amber.core.ir.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the object lifecycle matrix of a script: one row per object, one event per statement that
 * touches it. CTEs are internal to their statement and routines are not data objects, so both are
 * left out.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Lifecycles {

    private static final Set<ObjectKind> NOT_DATA = Set.of(ObjectKind.CTE, ObjectKind.ROUTINE, ObjectKind.PROCEDURE,
            ObjectKind.FUNCTION, ObjectKind.PACKAGE, ObjectKind.TRIGGER, ObjectKind.TYPE, ObjectKind.INDEX,
            ObjectKind.SEQUENCE, ObjectKind.SCHEMA, ObjectKind.SYNONYM);

    private Lifecycles() {
    }

    /** Statements in source order; the index in this list is the {@code step} of an event. */
    public static List<Statement> steps(Script script) {
        return script.flatten();
    }

    public static List<ObjectLifecycle> of(Script script) {
        List<Statement> steps = steps(script);
        Map<String, List<ObjectLifecycle.Event>> events = new LinkedHashMap<>();
        Map<String, ObjectRef> first = new LinkedHashMap<>();
        for (int step = 0; step < steps.size(); step++) {
            Statement st = steps.get(step);
            for (ObjectRef ref : st.refs()) {
                if (NOT_DATA.contains(ref.kind())) {
                    continue;
                }
                String key = keyFor(first, ref);
                first.putIfAbsent(key, ref);
                events.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new ObjectLifecycle.Event(step, st, ref.access(), ref.range(), ref.via()));
            }
        }
        List<ObjectLifecycle> result = new ArrayList<>();
        events.forEach((key, list) -> {
            ObjectRef ref = first.get(key);
            result.add(new ObjectLifecycle(ref.name(), strongestKind(steps, ref), list));
        });
        return result;
    }

    /** Same object under a qualified and an unqualified name shares one row. */
    private static String keyFor(Map<String, ObjectRef> known, ObjectRef ref) {
        for (Map.Entry<String, ObjectRef> e : known.entrySet()) {
            if (e.getValue().name().sameObject(ref.name())) {
                return e.getKey();
            }
        }
        return ref.name().key();
    }

    /** A temp table or view stays that kind even where it is referenced without that knowledge. */
    private static ObjectKind strongestKind(List<Statement> steps, ObjectRef ref) {
        for (Statement st : steps) {
            for (ObjectRef r : st.refs()) {
                if (r.name().sameObject(ref.name()) && r.kind() != ObjectKind.TABLE) {
                    return r.kind();
                }
            }
        }
        return ref.kind();
    }
}
