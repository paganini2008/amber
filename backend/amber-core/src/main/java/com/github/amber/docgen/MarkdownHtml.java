package com.github.amber.docgen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the Markdown subset {@link HandoverDoc} writes (headings, paragraphs, tables, ordered and
 * unordered nested lists, code fences, emphasis, inline code) as one self-contained, readable HTML page.
 * It is not a general Markdown renderer.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class MarkdownHtml {

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w])_([^_]+)_(?![\\w])");
    private static final Pattern LIST = Pattern.compile("^(\\s*)(\\d+\\.|-) (.*)$");

    private MarkdownHtml() {
    }

    static String page(String title, String markdown) {
        StringBuilder body = new StringBuilder();
        List<String> lines = markdown.lines().toList();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.startsWith("```")) {
                String lang = line.substring(3).strip();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.size() && !lines.get(i).startsWith("```")) {
                    code.append(lines.get(i++)).append('\n');
                }
                i++;
                body.append("<pre class=\"code").append(lang.isEmpty() ? "" : " " + escape(lang)).append("\"><code>")
                    .append(escape(code.toString())).append("</code></pre>\n");
            } else if (line.startsWith("#")) {
                int level = (int) line.chars().takeWhile(c -> c == '#').count();
                body.append("<h").append(level).append('>').append(inline(line.substring(level).strip()))
                    .append("</h").append(level).append(">\n");
                i++;
            } else if (line.startsWith("|")) {
                List<String> rows = new ArrayList<>();
                while (i < lines.size() && lines.get(i).startsWith("|")) {
                    rows.add(lines.get(i++));
                }
                table(rows, body);
            } else if (LIST.matcher(line).matches()) {
                List<String> items = new ArrayList<>();
                while (i < lines.size() && LIST.matcher(lines.get(i)).matches()) {
                    items.add(lines.get(i++));
                }
                list(items, 0, items.size(), body);
            } else if (line.isBlank()) {
                i++;
            } else {
                body.append("<p>").append(inline(line)).append("</p>\n");
                i++;
            }
        }
        return """
                <!doctype html>
                <html lang="%s"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title>
                <style>
                :root{--ink:#17232a;--muted:#52616a;--paper:#faf7f0;--line:#dfd5c2;--chrome:#0f2a33;--amber:#8a4b00}
                @media (prefers-color-scheme:dark){:root{--ink:#e6eeec;--muted:#93aaad;--paper:#10272f;--line:#21404a;--chrome:#071820;--amber:#ffc25e}}
                body{margin:0;background:var(--paper);color:var(--ink);font:15px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif}
                main{max-width:960px;margin:0 auto;padding:32px 20px 64px}
                h1{font-size:28px;line-height:1.25;margin:0 0 8px;border-bottom:3px solid var(--amber);padding-bottom:8px}
                h2{font-size:20px;margin:36px 0 12px;color:var(--chrome)} @media (prefers-color-scheme:dark){h2{color:var(--amber)}}
                h3{font-size:16px;margin:24px 0 8px}
                p,li{max-width:75ch} em{color:var(--muted)} code{font:13px/1.4 ui-monospace,Menlo,Consolas,monospace}
                table{border-collapse:collapse;width:100%%;margin:8px 0 16px;font-size:14px;display:block;overflow-x:auto}
                th,td{border:1px solid var(--line);padding:6px 10px;text-align:left;vertical-align:top}
                pre{background:rgba(0,0,0,.04);border:1px solid var(--line);border-radius:6px;padding:12px;overflow-x:auto}
                </style></head><body><main>
                %s</main></body></html>
                """.formatted(com.github.amber.core.i18n.Messages.current().tag(), escape(title), body);   // lang of the request
    }

    private static void table(List<String> rows, StringBuilder out) {
        out.append("<table>\n");
        boolean header = rows.size() > 1 && rows.get(1).matches("\\|[-| :]+\\|?");
        for (int r = 0; r < rows.size(); r++) {
            if (header && r == 1) {
                continue;
            }
            String tag = header && r == 0 ? "th" : "td";
            out.append("<tr>");
            for (String cell : cells(rows.get(r))) {
                out.append('<').append(tag).append('>').append(inline(cell)).append("</").append(tag).append('>');
            }
            out.append("</tr>\n");
        }
        out.append("</table>\n");
    }

    /** Cells of a table row; {@code \|} is a literal bar. */
    private static List<String> cells(String row) {
        String s = row.strip();
        s = s.substring(1, s.endsWith("|") && !s.endsWith("\\|") ? s.length() - 1 : s.length());
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().strip());
        return out;
    }

    /** Nested list from items[from, to) by indentation. */
    private static void list(List<String> items, int from, int to, StringBuilder out) {
        Matcher first = LIST.matcher(items.get(from));
        first.matches();
        int indent = first.group(1).length();
        out.append(first.group(2).equals("-") ? "<ul>\n" : "<ol>\n");
        int i = from;
        while (i < to) {
            Matcher m = LIST.matcher(items.get(i));
            m.matches();
            out.append("<li>").append(inline(m.group(3)));
            int j = i + 1;
            while (j < to && indentOf(items.get(j)) > indent) {
                j++;
            }
            if (j > i + 1) {
                out.append('\n');
                list(items, i + 1, j, out);
            }
            out.append("</li>\n");
            i = j;
        }
        out.append(first.group(2).equals("-") ? "</ul>\n" : "</ol>\n");
    }

    private static int indentOf(String line) {
        Matcher m = LIST.matcher(line);
        return m.matches() ? m.group(1).length() : 0;
    }

    private static String inline(String text) {
        String s = escape(text.replace("\\|", "|"));
        s = CODE.matcher(s).replaceAll("<code>$1</code>");
        s = BOLD.matcher(s).replaceAll("<strong>$1</strong>");
        s = ITALIC.matcher(s).replaceAll("<em>$1</em>");
        return s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
