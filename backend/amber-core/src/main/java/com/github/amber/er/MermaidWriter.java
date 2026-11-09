package com.github.amber.er;

import com.github.amber.core.i18n.Messages;
import com.github.amber.er.ErModel.Cardinality;
import com.github.amber.er.ErModel.Certainty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes an {@link ErModel} as Mermaid {@code erDiagram} text. Entity and attribute names are reduced to
 * the characters Mermaid accepts ({@code sales.orders} → {@code sales_orders}, made unique), labels and
 * comments are quoted with inner double quotes replaced. Crow's foot per cardinality, the many side
 * first: MANY_TO_ONE {@code }o--||}, ONE_TO_ONE {@code |o--||}, ONE_TO_MANY {@code ||--o{}, MANY_TO_MANY
 * {@code }o--o{}; UNKNOWN is drawn {@code }o..o{} with a "?" in its label. Declared keys are solid lines,
 * inferred ones dashed. Each entity lists at most {@value #MAX_ATTRIBUTES} attributes (keys and joined
 * columns first) and a last "… n more" line.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class MermaidWriter {

    static final int MAX_ATTRIBUTES = 12;

    private MermaidWriter() {
    }

    static String write(List<ErModel.Entity> entities, List<ErModel.Relationship> relationships) {
        Map<String, String> ids = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (ErModel.Entity e : entities) {
            ids.put(e.key(), unique(identifier(e.name()), used));
        }
        StringBuilder sb = new StringBuilder("erDiagram\n");
        for (ErModel.Entity e : entities) {
            String id = ids.get(e.key());
            if (e.columns().isEmpty()) {
                sb.append("    ").append(id).append(" {\n        unknown columns_unknown\n    }\n");
                continue;
            }
            sb.append("    ").append(id).append(" {\n");
            List<ErModel.Column> cols = ordered(e.columns());
            Set<String> names = new HashSet<>();
            int shown = Math.min(cols.size(), MAX_ATTRIBUTES);
            for (int i = 0; i < shown; i++) {
                ErModel.Column c = cols.get(i);
                sb.append("        ").append(type(c.type())).append(' ').append(unique(identifier(c.name()), names));
                List<String> keys = new ArrayList<>();
                if (c.pk()) {
                    keys.add("PK");
                }
                if (c.fk()) {
                    keys.add("FK");
                }
                if (c.unique() && !c.pk()) {
                    keys.add("UK");
                }
                if (!keys.isEmpty()) {
                    sb.append(' ').append(String.join(", ", keys));
                }
                if (!identifier(c.name()).equals(c.name())) {
                    sb.append(" \"").append(quote(c.name())).append('"');
                }
                sb.append('\n');
            }
            if (cols.size() > shown) {
                sb.append("        more ").append(unique("more_columns", names)).append(" \"")
                        .append(quote(Messages.t("er.mermaid.more", cols.size() - shown)))
                        .append("\"\n");
            }
            sb.append("    }\n");
        }
        for (ErModel.Relationship r : relationships) {
            String from = ids.get(r.from().entity());
            String to = ids.get(r.to().entity());
            if (from == null || to == null) {
                continue;
            }
            boolean solid = r.confidence() == Certainty.CERTAIN;
            sb.append("    ").append(from).append(' ').append(notation(r.cardinality(), solid)).append(' ').append(to)
                    .append(" : \"").append(quote(label(r))).append("\"\n");
        }
        return sb.toString();
    }

    static String notation(Cardinality c, boolean solid) {
        String line = solid ? "--" : "..";
        return switch (c) {
            case MANY_TO_ONE -> "}o" + line + "||";
            case ONE_TO_ONE -> "|o" + line + "||";
            case ONE_TO_MANY -> "||" + line + "o{";
            case MANY_TO_MANY -> "}o" + line + "o{";
            case UNKNOWN -> "}o..o{";
        };
    }

    private static String label(ErModel.Relationship r) {
        String cols = String.join(", ", r.from().columns());
        if (!r.to().columns().isEmpty() && !r.to().columns().equals(r.from().columns())) {
            cols += " = " + String.join(", ", r.to().columns());
        }
        String suffix = switch (r.evidence()) {
            case DATA_FLOW -> " " + Messages.t("er.mermaid.dataFlow");
            case NAME_MATCH -> " " + Messages.t("er.mermaid.nameMatch");
            default -> r.cardinality() == Cardinality.UNKNOWN ? " (?)" : "";
        };
        return (cols + suffix).strip();
    }

    /** Keys, then foreign keys, then joined columns, then the rest; each group in table order. */
    private static List<ErModel.Column> ordered(List<ErModel.Column> cols) {
        List<ErModel.Column> out = new ArrayList<>();
        for (int pass = 0; pass < 4; pass++) {
            for (ErModel.Column c : cols) {
                int group = c.pk() ? 0 : c.fk() ? 1 : c.joined() ? 2 : 3;
                if (group == pass) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Letters, digits, underscores and hyphens only, not starting with a digit or hyphen. */
    static String identifier(String name) {
        String s = name == null ? "" : name.strip().replaceAll("[\"`\\[\\]]", "")
                .replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0)) && s.charAt(0) != '_') {
            s = "_" + s;
        }
        return s;
    }

    private static String type(String type) {
        if (type == null || type.isBlank()) {
            return "unknown";
        }
        String t = type.strip().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+$", "");
        return t.isEmpty() || !Character.isLetter(t.charAt(0)) ? "t_" + t : t;
    }

    private static String unique(String id, Set<String> used) {
        String out = id;
        for (int n = 2; !used.add(out.toLowerCase(Locale.ROOT)); n++) {
            out = id + "_" + n;
        }
        return out;
    }

    private static String quote(String s) {
        return s.replace('"', '\'').replace("\r", " ").replace("\n", " ");
    }
}
