package com.github.amber.core.ir;

import com.github.amber.core.Dialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A possibly qualified object name such as {@code sales.dbo.orders}, with per-part quoting kept so
 * that names can be compared the way the database compares them.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class ObjectName {

    /** One part of a qualified name. */
    public record Part(String text, boolean quoted) {
    }

    private final List<Part> parts;
    private final String key;
    private final String display;

    private ObjectName(List<Part> parts, Dialect dialect) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Empty object name");
        }
        this.parts = List.copyOf(parts);
        this.display = parts.stream().map(p -> p.quoted() ? '"' + p.text() + '"' : p.text())
                .collect(Collectors.joining("."));
        this.key = parts.stream().map(p -> fold(p, dialect)).collect(Collectors.joining("."));
    }

    public static ObjectName of(Dialect dialect, List<Part> parts) {
        return new ObjectName(parts, dialect);
    }

    /**
     * Parses the leading name in {@code text}: parts separated by {@code .}, each bare or quoted
     * with {@code "…"}, {@code […]} or {@code `…`}. Parsing stops at the first character that cannot
     * continue the name (whitespace, {@code @dblink}, {@code (}, …). Returns {@code null} when no
     * name is found.
     */
    public static ObjectName parse(Dialect dialect, String text) {
        List<Part> parts = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            char c = text.charAt(i);
            char close = switch (c) {
                case '"' -> '"';
                case '[' -> ']';
                case '`' -> '`';
                default -> 0;
            };
            StringBuilder part = new StringBuilder();
            boolean quoted = close != 0;
            if (quoted) {
                i++;
                while (i < n) {
                    char d = text.charAt(i++);
                    if (d == close) {
                        if (i < n && text.charAt(i) == close) {
                            part.append(close);
                            i++;
                            continue;
                        }
                        break;
                    }
                    part.append(d);
                }
            } else {
                while (i < n && isNameChar(text.charAt(i))) {
                    part.append(text.charAt(i++));
                }
                if (part.isEmpty()) {
                    break;
                }
            }
            parts.add(new Part(part.toString(), quoted));
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i < n && text.charAt(i) == '.') {
                i++;
            } else {
                break;
            }
        }
        return parts.isEmpty() ? null : new ObjectName(parts, dialect);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@' || c == '&';
    }

    /**
     * Names are matched case-insensitively in every dialect, quoted or not: legacy scripts mix
     * {@code ORDERS}, {@code orders} and {@code "Orders"} for the same table far more often than a
     * database holds two tables differing only in case.
     */
    private static String fold(Part p, Dialect dialect) {
        return p.text().toUpperCase(Locale.ROOT);
    }

    public List<Part> parts() {
        return parts;
    }

    /** The last part: the object's own name without schema or database. */
    public String simpleName() {
        return parts.getLast().text();
    }

    /** Normalized comparison key, e.g. {@code SALES.ORDERS} for Oracle. */
    public String key() {
        return key;
    }

    /** Key of the last part only. */
    public String simpleKey() {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    /**
     * True when both names denote the same object: equal keys, or equal simple names when at least
     * one side is unqualified (a script often mixes {@code orders} and {@code sales.orders}).
     */
    public boolean sameObject(ObjectName other) {
        if (key.equals(other.key)) {
            return true;
        }
        boolean eitherUnqualified = parts.size() == 1 || other.parts.size() == 1;
        return eitherUnqualified && simpleKey().equals(other.simpleKey());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ObjectName other && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public String toString() {
        return display;
    }
}
