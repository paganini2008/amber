package com.github.amber.core.source;

import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.diagnostic.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Characters that look like whitespace (or nothing) but are not ASCII whitespace: no-break and
 * ideographic spaces, zero-width characters, stray byte-order marks, DOS end-of-file markers and
 * other control characters. They creep into legacy scripts through copy and paste from documents,
 * web pages and old editors. Grammars reject them, so before parsing each is replaced by a plain
 * space: the text keeps its length and every offset stays exact. Unicode line separators (U+0085, U+2028,
 * U+2029) become spaces too: editors and databases do not end lines there, so line numbers stay those of
 * the editor. Each kind found is reported as a warning, because the database may reject them too.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class InvisibleCharacters {

    public record Result(String text, List<Diagnostic> warnings) {
    }

    private InvisibleCharacters() {
    }

    public static Result normalize(SourceText source) {
        String text = source.text();
        char[] out = null;
        Map<String, int[]> found = new LinkedHashMap<>();          // kind -> {count, first offset}
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String kind = kindOf(c, i);
            if (kind == null) {
                continue;
            }
            if (out == null) {
                out = text.toCharArray();
            }
            out[i] = ' ';
            int[] stat = found.computeIfAbsent(kind, k -> new int[] {0, -1});
            stat[0]++;
            if (stat[1] < 0) {
                stat[1] = i;
            }
        }
        if (out == null) {
            return new Result(text, List.of());
        }
        List<Diagnostic> warnings = new ArrayList<>();
        found.forEach((kind, stat) -> warnings.add(new Diagnostic(Severity.WARNING, "invisible-character",
                stat[0] + " " + kind + (stat[0] == 1 ? "" : "s") + " treated as whitespace; the database may reject "
                        + (stat[0] == 1 ? "it" : "them"),
                source.range(stat[1], stat[1] + 1), null, List.of())));
        return new Result(new String(out), warnings);
    }

    private static String kindOf(char c, int index) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return null;
        }
        if (c == '﻿') {
            return index == 0 ? null : "byte-order mark";
        }
        if (c < 0x20 || c == 0x7F) {
            return c == 0x1A ? "end-of-file marker (Ctrl-Z)" : c == 0x0C ? "form feed" : "control character";
        }
        if (c == ' ' || c == ' ' || c == ' ') {
            return "no-break space";
        }
        if (c == '　') {
            return "ideographic (full-width) space";
        }
        if (c == ' ' || (c >= ' ' && c <= ' ') || c == ' ') {
            return "Unicode space";
        }
        if ((c >= '​' && c <= '‍') || c == '⁠') {
            return "zero-width character";
        }
        if (c == ' ' || c == ' ' || c == '\u0085') {
            return "Unicode line separator";
        }
        return null;
    }
}
