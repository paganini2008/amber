package com.github.amber.er;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Naming conventions of keys in legacy schemas, used only when no key metadata says which side of a
 * join is unique: {@code customer_id}, {@code customerid}, {@code customer_no}, {@code customer_code}
 * refer to a table named {@code customer}, {@code customers} or {@code tbl_customer}, whose own key is
 * {@code id} or {@code customer_id}.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class Naming {

    /** Key suffixes, longest first so {@code _id} wins over {@code id}. */
    private static final List<String> SUFFIXES = List.of("_code", "_key", "_num", "_nbr", "_id", "_no", "_cd",
            "id", "no");
    private static final List<String> PREFIXES = List.of("tbl_", "tb_", "t_");

    private Naming() {
    }

    /** Lower-case name without quotes, schema or a temp-table marker. */
    static String plain(String name) {
        String n = name == null ? "" : name.strip();
        int dot = n.lastIndexOf('.');
        if (dot >= 0) {
            n = n.substring(dot + 1);
        }
        n = n.replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT);
        while (n.startsWith("#") || n.startsWith("@")) {
            n = n.substring(1);
        }
        return n;
    }

    /** Forms of a table name a column may refer to: as written, singular, plural, without a tbl_ prefix. */
    static Set<String> stems(String table) {
        Set<String> out = new LinkedHashSet<>();
        String t = plain(table);
        if (t.isEmpty()) {
            return out;
        }
        add(out, t);
        for (String p : PREFIXES) {
            if (t.startsWith(p) && t.length() - p.length() >= 3) {
                add(out, t.substring(p.length()));
            }
        }
        return out;
    }

    private static void add(Set<String> out, String t) {
        out.add(t);
        out.add(singular(t));
    }

    static String singular(String t) {
        if (t.endsWith("ies") && t.length() > 4) {
            return t.substring(0, t.length() - 3) + "y";
        }
        if ((t.endsWith("sses") || t.endsWith("xes") || t.endsWith("ches") || t.endsWith("shes")) && t.length() > 4) {
            return t.substring(0, t.length() - 2);
        }
        if (t.endsWith("s") && !t.endsWith("ss") && t.length() > 3) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** The table stem a key column refers to ({@code customer_id} → {@code customer}), or null. */
    static String base(String column) {
        String c = plain(column);
        for (String s : SUFFIXES) {
            if (c.endsWith(s) && c.length() - s.length() >= 3) {
                String b = c.substring(0, c.length() - s.length());
                while (b.endsWith("_")) {
                    b = b.substring(0, b.length() - 1);
                }
                return b.length() >= 2 ? b : null;
            }
        }
        return null;
    }

    /** True when {@code column} looks like {@code table}'s own key: {@code id}, {@code customer_id} in {@code customers}. */
    static boolean ownKey(String column, String table) {
        String c = plain(column);
        if (c.equals("id")) {
            return true;
        }
        String b = base(c);
        return b != null && (stems(table).contains(b) || stems(table).contains(singular(b)));
    }

    /** True when {@code column} names {@code table} as the thing it refers to ({@code customer_id} → {@code customers}). */
    static boolean refersTo(String column, String table) {
        String b = base(column);
        return b != null && (stems(table).contains(b) || stems(table).contains(singular(b)));
    }
}
