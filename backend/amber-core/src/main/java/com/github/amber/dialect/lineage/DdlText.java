package com.github.amber.dialect.lineage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dialect-neutral readers for column definitions and table constraints in CREATE TABLE text.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class DdlText {

    private static final Pattern TYPE_END = Pattern.compile("(?i)\\s+(NOT\\s+NULL|NULL|DEFAULT|PRIMARY|CONSTRAINT|REFERENCES|CHECK|UNIQUE|GENERATED|IDENTITY|AUTO_INCREMENT|COLLATE|COMMENT|AS\\s*\\()");
    private static final Pattern TABLE_PK = Pattern.compile("(?i)PRIMARY\\s+KEY\\s*(?:CLUSTERED|NONCLUSTERED)?\\s*\\(([^)]*)\\)");
    private static final Pattern TABLE_UK = Pattern.compile("(?i)UNIQUE\\s*(?:KEY|INDEX)?\\s*(?:\\w+\\s*)?\\(([^)]*)\\)");

    // ----- foreign keys (inline REFERENCES, table-level and ALTER TABLE … FOREIGN KEY) -------------------
    private static final String NAME_PART = "(?:\"[^\"]+\"|\\[[^\\]]+\\]|`[^`]+`|[\\w$#@]+)";
    private static final String NAME = NAME_PART + "(?:\\s*\\.\\s*" + NAME_PART + ")*";
    private static final Pattern TABLE_FK = Pattern.compile("(?i)(?:\\bCONSTRAINT\\s+(" + NAME_PART + ")\\s+)?"
            + "\\bFOREIGN\\s+KEY\\s*(?:" + NAME_PART + "\\s*)?\\(([^)]*)\\)\\s*REFERENCES\\s+(" + NAME + ")\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern INLINE_FK = Pattern.compile("(?i)(?:\\bCONSTRAINT\\s+(" + NAME_PART + ")\\s+)?"
            + "(?:\\bFOREIGN\\s+KEY\\s+)?\\bREFERENCES\\s+(" + NAME + ")\\s*(?:\\(([^)]*)\\))?");

    private DdlText() {
    }

    /**
     * Table-level foreign keys in CREATE TABLE or ALTER TABLE text:
     * {@code [CONSTRAINT n] FOREIGN KEY [n] (a, b) REFERENCES t [(x, y)]}.
     */
    static List<com.github.amber.core.meta.TableMeta.ForeignKeyMeta> tableForeignKeys(String text,
                                                                                    com.github.amber.core.Dialect d) {
        List<com.github.amber.core.meta.TableMeta.ForeignKeyMeta> out = new ArrayList<>();
        Matcher m = TABLE_FK.matcher(text);
        while (m.find()) {
            var fk = foreignKey(d, m.group(1), columns(m.group(2)), m.group(3), m.group(4));
            if (fk != null && !fk.columns().isEmpty()) {
                out.add(fk);
            }
        }
        return out;
    }

    /** A column definition's own {@code REFERENCES t [(x)]} (also T-SQL {@code FOREIGN KEY REFERENCES}), or null. */
    static com.github.amber.core.meta.TableMeta.ForeignKeyMeta inlineForeignKey(String definition, String column,
                                                                              com.github.amber.core.Dialect d) {
        if (TABLE_FK.matcher(definition).find()) {
            return null;                              // a table constraint read as a column definition
        }
        Matcher m = INLINE_FK.matcher(definition);
        return m.find() ? foreignKey(d, m.group(1), List.of(column), m.group(2), m.group(3)) : null;
    }

    private static com.github.amber.core.meta.TableMeta.ForeignKeyMeta foreignKey(com.github.amber.core.Dialect d,
                                                                                String constraint, List<String> cols,
                                                                                String refName, String refCols) {
        var name = com.github.amber.core.ir.ObjectName.parse(d, refName);
        if (name == null) {
            return null;
        }
        List<String> parts = name.parts().stream().map(com.github.amber.core.ir.ObjectName.Part::text).toList();
        String table = parts.get(parts.size() - 1);
        String schema = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
        String catalog = parts.size() >= 3 ? parts.get(parts.size() - 3) : null;
        String cname = constraint == null ? null : constraint.replaceAll("^[\"`\\[]|[\"`\\]]$", "");
        return new com.github.amber.core.meta.TableMeta.ForeignKeyMeta(cname, cols, catalog, schema, table,
                refCols == null ? List.of() : columns(refCols));
    }

    /** The data type of a column definition, e.g. {@code NUMBER(18,2)}. */
    static String type(String definition, String nameText) {
        String rest = definition.strip();
        if (rest.regionMatches(true, 0, nameText.strip(), 0, nameText.strip().length())) {
            rest = rest.substring(nameText.strip().length()).strip();
        }
        Matcher m = TYPE_END.matcher(" " + rest);
        String type = m.find() ? (" " + rest).substring(0, m.start()).strip() : rest;
        return type.isEmpty() ? null : type.replaceAll("\\s+", " ");
    }

    static boolean notNull(String definition) {
        return definition.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").contains("NOT NULL")
                || inlinePrimaryKey(definition);
    }

    static boolean inlinePrimaryKey(String definition) {
        return definition.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").contains("PRIMARY KEY")
                && !TABLE_PK.matcher(definition).find();
    }

    static List<String> tablePrimaryKey(String createText) {
        Matcher m = TABLE_PK.matcher(createText);
        return m.find() ? columns(m.group(1)) : List.of();
    }

    static List<List<String>> uniqueKeys(String createText) {
        List<List<String>> out = new ArrayList<>();
        Matcher m = TABLE_UK.matcher(createText);
        while (m.find()) {
            out.add(columns(m.group(1)));
        }
        return out;
    }

    private static List<String> columns(String list) {
        List<String> out = new ArrayList<>();
        for (String c : list.split(",")) {
            String name = c.strip().split("\\s+")[0].replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }
}
