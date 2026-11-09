package com.github.amber.er;

import com.github.amber.core.ir.ObjectName;
import com.github.amber.core.source.TextRange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The entities of a model by name. Names are first noted, then {@link #freeze() frozen}: an unqualified
 * name ({@code orders}) joins the one qualified name with the same last part ({@code sales.orders}) when
 * there is exactly one; with several it stays an entity of its own.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class Entities {

    /** What is known about one entity while the model is built. */
    static final class Info {
        String key;
        String display;
        String kind = "TABLE";
        boolean created;
        TextRange definition;
        boolean declaredInScript;       // created or altered by the script: its DDL foreign keys belong to the model
        final List<ObjectName> names = new ArrayList<>();
        int order;
    }

    private final Map<String, Info> byRawKey = new LinkedHashMap<>();
    private final Map<String, Set<String>> qualifiedBySimple = new HashMap<>();
    private Map<String, Info> byKey;
    private boolean frozen;

    /** Notes a mention; returns the raw entry (merged into its canonical entity on {@link #freeze()}). */
    Info note(ObjectName name, String kind) {
        if (frozen) {
            throw new IllegalStateException("frozen");
        }
        Info i = byRawKey.get(name.key());
        if (i == null) {
            i = new Info();
            i.key = name.key();
            i.display = name.toString();
            i.order = byRawKey.size();
            byRawKey.put(name.key(), i);
            if (name.parts().size() > 1) {
                qualifiedBySimple.computeIfAbsent(name.simpleKey(), k -> new LinkedHashSet<>()).add(name.key());
            }
        }
        if (i.names.isEmpty() || i.names.size() < 4 && !i.names.contains(name)) {
            i.names.add(name);
        }
        i.kind = strongest(i.kind, kind);
        return i;
    }

    /** Merges unqualified mentions into their single qualified twin and fixes the entity keys. */
    void freeze() {
        byKey = new LinkedHashMap<>();
        Map<String, Info> canonical = new HashMap<>();
        List<Info> all = new ArrayList<>(byRawKey.values());
        for (Info i : all) {
            String target = canonicalKey(i.key);
            Info into = canonical.get(target);
            if (into == null) {
                into = byRawKey.get(target);
                if (into == null) {
                    into = i;
                }
                canonical.put(target, into);
            }
            if (into != i) {
                into.kind = strongest(into.kind, i.kind);
                into.created |= i.created;
                into.declaredInScript |= i.declaredInScript;
                if (into.definition == null) {
                    into.definition = i.definition;
                }
                into.order = Math.min(into.order, i.order);
                into.names.addAll(i.names);
            }
        }
        canonical.values().stream().distinct().sorted(Comparator.comparingInt(x -> x.order))
                .forEach(i -> byKey.put(i.key, i));
        frozen = true;
    }

    private String canonicalKey(String rawKey) {
        if (rawKey.indexOf('.') >= 0) {
            return rawKey;
        }
        Set<String> q = qualifiedBySimple.get(rawKey);
        return q != null && q.size() == 1 ? q.iterator().next() : rawKey;
    }

    /** The entity of a name after {@link #freeze()}, or null when it is none. */
    Info get(ObjectName name) {
        return name == null ? null : getKey(name.key());
    }

    /** The entity of a normalized key (as {@link ObjectName#key()}) after {@link #freeze()}, or null. */
    Info getKey(String key) {
        Info i = byKey.get(canonicalKey(key));
        if (i == null && key.indexOf('.') >= 0) {
            i = byKey.get(key.substring(key.lastIndexOf('.') + 1));    // sales.orders noted only as orders
        }
        return i;
    }

    List<Info> all() {
        return new ArrayList<>(byKey.values());
    }

    boolean isFrozen() {
        return frozen;
    }

    /** A temp table or view stays that kind even where it is mentioned as a plain table. */
    static String strongest(String a, String b) {
        if (b == null || b.equals(a)) {
            return a;
        }
        if (a == null || a.equals("TABLE")) {
            return b;
        }
        return a;
    }
}
