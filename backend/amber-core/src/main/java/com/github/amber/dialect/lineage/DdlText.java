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

    private DdlText() {
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
