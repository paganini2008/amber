package com.github.amber.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic mutations of SQL scripts for fuzzing: the same text and seed always give the same
 * mutant, so a failure is reproduced from the file name and the seed alone. A mutant applies one to
 * three edits: truncation (also right inside strings, comments, dollar quotes and q-quotes), token
 * deletion / duplication / swap, unbalanced delimiters and block keywords, splitter commands of every
 * dialect, control and invisible characters, a byte-order mark in the middle, line-ending changes, very
 * long lines and snippets of other dialects.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class Mutator {

    private static final Pattern TOKEN = Pattern.compile("\\S+");
    /** Places where a cut leaves something open. */
    private static final Pattern OPENERS = Pattern.compile("'|\"|/\\*|--|\\$\\$|\\$[a-z_]+\\$|[qQ]'.|\\[|`|\\(|BEGIN|begin");

    private static final String[] FRAGMENTS = {
            "'", "\"", "/*", "*/", "$$", "$body$", "q'[", "q'{x", "N'", "E'\\", "`", "[", "]", "(", ")", "((", "))",
            "BEGIN", "END", "END;", "END IF;", "END LOOP;", "BEGIN TRY", "END CATCH", "CASE", "--", "#", ";", ";;",
            "\nGO\n", "\ngo 5\n", "\n/\n", "\n.\n", "\nDELIMITER $$\n", "\nDELIMITER ;\n", "\nDELIMITER //\n", "$$\n",
            "\n\\set x 1\n", "\n\\i other.sql\n", "\n@other.sql\n", "\n:r other.sql\n", "\n!!dir\n", "\nSET DEFINE OFF\n",
            "\nPROMPT hello\n", "\nEXIT\n", "\nWHENEVER SQLERROR EXIT\n", "/*!40101 SET x=1 */", "&var", ":bind", "@v",
            "@@ROWCOUNT", "?", "$1", ":=", "=>", "||", "::", "->>", "\\", "{", "}", "<>", "!=", "--+hint", "/*+ PARALLEL */",
    };

    private static final String[] KEYWORDS = {
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE", "USING", "WITH",
            "RECURSIVE", "UNION", "ALL", "INTERSECT", "EXCEPT", "MINUS", "JOIN", "LEFT", "ON", "GROUP", "BY", "HAVING",
            "ORDER", "LIMIT", "TOP", "FETCH", "CREATE", "OR REPLACE", "TABLE", "TEMPORARY", "VIEW", "PROCEDURE",
            "FUNCTION", "PACKAGE", "BODY", "TRIGGER", "DECLARE", "CURSOR", "FOR", "LOOP", "WHILE", "IF", "THEN", "ELSE",
            "ELSIF", "RETURN", "RETURNS", "LANGUAGE", "AS", "IS", "EXEC", "EXECUTE", "IMMEDIATE", "CALL", "DROP", "ALTER",
            "TRUNCATE", "GRANT", "COMMIT", "ROLLBACK", "EXCEPTION", "WHEN", "OTHERS", "RAISE", "OVER", "PARTITION",
            "CONNECT", "PRIOR", "START", "PIVOT", "LATERAL", "NULL", "NOT", "EXISTS", "IN", "BETWEEN", "LIKE", "DISTINCT",
    };

    private static final String[] FOREIGN_SNIPPETS = {
            "SELECT TOP 5 @x = [a b], #t.c FROM #t WITH (NOLOCK) OPTION (RECOMPILE)",
            "CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql;",
            "SELECT `a`.`b` FROM `db`.`t` LIMIT 1, 2 INTO @v",
            "v := q'{it's}' || :new.c; EXECUTE IMMEDIATE 'x' INTO v; END;",
            "SELECT a::text, b->>'k' FROM t WHERE c ~* 'x' RETURNING *",
            "BEGIN TRY EXEC dbo.p @a = 1 OUTPUT END TRY BEGIN CATCH THROW; END CATCH",
            "DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET done = 1;",
            "SELECT * FROM t CONNECT BY PRIOR id = pid START WITH id = 1",
            "MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.a = s.a WHEN NOT MATCHED THEN INSERT VALUES (s.a);",
            "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r) SELECT * FROM r",
            "INSERT INTO t SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT 1) x) y) z",
            "SELECT x FROM TABLE(f(1)) PIVOT (SUM(a) FOR b IN (1, 2))",
    };

    private static final String[] INVISIBLE = {"​", "﻿", " ", "⁠", "‎", "　", " ", " ", "\u0085"};
    private static final String[] CONTROL = {"\u0000", "\u0001", "\u0007", "\u0008", "\u000B", "\u000C", "\u001A", "\u001B", "\u007F", "�", "￿"};
    /** Unusual but valid text: emoji (surrogate pairs), right-to-left, combining marks, CJK, a lone surrogate. */
    private static final String[] UNICODE = {"😀", "אב", "é", "中文", "\uD800", "\uDC00x", "𝐀"};

    private Mutator() {
    }

    /** A mutant and what was done to get it. */
    record Mutant(String text, String recipe) {
    }

    static Mutant mutate(String text, long seed) {
        Random rnd = new Random(seed);
        String t = text;
        StringBuilder recipe = new StringBuilder();
        int edits = 1 + rnd.nextInt(3);
        for (int i = 0; i < edits; i++) {
            int kind = rnd.nextInt(16);
            String before = t;
            t = apply(kind, t, rnd, recipe);
            if (t.length() > 4_000_000) {
                t = before;                                   // keep mutants of mutants bounded
            }
        }
        return new Mutant(t, recipe.toString().trim());
    }

    private static String apply(int kind, String t, Random rnd, StringBuilder recipe) {
        int at = t.isEmpty() ? 0 : rnd.nextInt(t.length() + 1);
        switch (kind) {
            case 0 -> {
                recipe.append(" truncate@").append(at);
                return t.substring(0, at);
            }
            case 1 -> {
                List<Integer> opens = new ArrayList<>();
                Matcher m = OPENERS.matcher(t);
                while (m.find()) {
                    opens.add(m.end());
                }
                if (opens.isEmpty()) {
                    recipe.append(" truncate@").append(at);
                    return t.substring(0, at);
                }
                int cut = Math.min(t.length(), opens.get(rnd.nextInt(opens.size())) + rnd.nextInt(3));
                recipe.append(" truncateInside@").append(cut);
                return t.substring(0, cut);
            }
            case 2, 3, 4 -> {
                List<int[]> tokens = tokens(t);
                if (tokens.size() < 2) {
                    return insert(t, at, "'", recipe);
                }
                int[] a = tokens.get(rnd.nextInt(tokens.size()));
                int[] b = tokens.get(rnd.nextInt(tokens.size()));
                if (kind == 2) {
                    recipe.append(" deleteToken@").append(a[0]);
                    return t.substring(0, a[0]) + t.substring(a[1]);
                }
                if (kind == 3) {
                    recipe.append(" duplicateToken@").append(a[0]);
                    return t.substring(0, a[1]) + " " + t.substring(a[0], a[1]) + t.substring(a[1]);
                }
                if (a[0] > b[0]) {
                    int[] x = a;
                    a = b;
                    b = x;
                }
                if (a[1] > b[0]) {
                    return t;
                }
                recipe.append(" swapTokens@").append(a[0]).append(',').append(b[0]);
                return t.substring(0, a[0]) + t.substring(b[0], b[1]) + t.substring(a[1], b[0])
                        + t.substring(a[0], a[1]) + t.substring(b[1]);
            }
            case 5, 6 -> {
                return insert(t, at, FRAGMENTS[rnd.nextInt(FRAGMENTS.length)], recipe);
            }
            case 7 -> {
                return insert(t, at, " " + KEYWORDS[rnd.nextInt(KEYWORDS.length)] + " ", recipe);
            }
            case 8 -> {
                return insert(t, at, CONTROL[rnd.nextInt(CONTROL.length)], recipe);
            }
            case 9 -> {
                return insert(t, at, INVISIBLE[rnd.nextInt(INVISIBLE.length)], recipe);
            }
            case 10 -> {
                return insert(t, at, UNICODE[rnd.nextInt(UNICODE.length)], recipe);
            }
            case 11 -> {
                String nl = rnd.nextBoolean() ? "\r\n" : "\r";
                recipe.append(" lineEnds=").append(nl.equals("\r") ? "CR" : "CRLF");
                return t.replace("\r\n", "\n").replace("\n", nl);
            }
            case 12 -> {
                int n = 20_000 + rnd.nextInt(40_000);
                String piece = switch (rnd.nextInt(4)) {
                    case 0 -> "x".repeat(n);
                    case 1 -> "'" + "y".repeat(n) + "'";
                    case 2 -> "a, ".repeat(n / 3);
                    default -> "-- " + "z".repeat(n);
                };
                recipe.append(" longLine(").append(n).append(")@").append(at);
                return t.substring(0, at) + piece + t.substring(at);
            }
            case 13 -> {
                recipe.append(" joinLines");
                return t.replace("\r\n", " ").replace('\n', ' ');
            }
            case 14 -> {
                return insert(t, at, "\n" + FOREIGN_SNIPPETS[rnd.nextInt(FOREIGN_SNIPPETS.length)] + "\n", recipe);
            }
            default -> {
                List<int[]> tokens = tokens(t);
                if (tokens.isEmpty()) {
                    return insert(t, at, "(", recipe);
                }
                int[] a = tokens.get(rnd.nextInt(tokens.size()));
                int depth = 1 + rnd.nextInt(60);
                recipe.append(" parens(").append(depth).append(")@").append(a[0]);
                return t.substring(0, a[0]) + "(".repeat(depth) + t.substring(a[0], a[1])
                        + ")".repeat(rnd.nextInt(depth + 1)) + t.substring(a[1]);
            }
        }
    }

    private static String insert(String t, int at, String s, StringBuilder recipe) {
        recipe.append(" insert(").append(escape(s)).append(")@").append(at);
        return t.substring(0, at) + s + t.substring(at);
    }

    private static List<int[]> tokens(String t) {
        List<int[]> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(t);
        while (m.find()) {
            out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7E) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
