package com.github.amber.format;

import com.github.amber.core.Dialect;
import com.github.amber.core.source.SourceText;
import com.github.amber.core.source.TextRange;
import com.github.amber.dialect.DialectFrontends;
import com.github.amber.dialect.split.ScriptSplitter;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.UnitKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Re-indents SQL scripts so that legacy code can be read again.
 *
 * <p>The script is cut into units with the dialect's {@link ScriptSplitter}. SQL units are laid out
 * by the {@link LayoutEngine}; client commands (SQL*Plus {@code SET}/{@code PROMPT}/{@code @file},
 * sqlcmd {@code :r}, psql {@code \set}, MySQL {@code DELIMITER} …), inline data, units that do not
 * tokenize (an unterminated string or comment) and Oracle wrapped or Java sources are copied as
 * written. Between units only blank lines are normalized: runs of blank lines collapse to one and
 * trailing spaces are dropped; terminators such as {@code /} and {@code GO} stay on their own lines.
 *
 * <p><b>Safety.</b> The formatter only changes whitespace and, when asked, the case of reserved
 * words. Every formatted unit is verified by {@link SafetyCheck} (identical token and comment
 * sequence); the whole result is then split again and must yield the same units. A unit that fails
 * either check is kept exactly as written and reported in {@link FormatResult#warnings()} and
 * {@link FormatResult#skipped()}. PostgreSQL dollar-quoted bodies ({@code $$ … $$}) are strings and
 * are therefore left untouched.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class Formatter {

    private static final String BOM = "\uFEFF";

    private Formatter() {
    }

    /** Formats a whole script with {@link FormatOptions#DEFAULTS}. */
    public static FormatResult format(Dialect dialect, String text) {
        return format(dialect, text, FormatOptions.DEFAULTS);
    }

    /** Formats a whole script. */
    public static FormatResult format(Dialect dialect, String text, FormatOptions options) {
        return run(dialect, text, options, 0, text == null ? 0 : text.length(), true);
    }

    /**
     * Formats only the statements that overlap {@code [start, end)} (or contain {@code start} when the
     * range is empty) and returns the whole text; everything else, including the whitespace between
     * statements, is left as it is. A formatted statement keeps the indentation of its first line.
     */
    public static FormatResult formatRange(Dialect dialect, String text, int start, int end, FormatOptions options) {
        Objects.requireNonNull(text, "text");
        if (start < 0 || end < start || end > text.length()) {
            throw new IllegalArgumentException("Range [" + start + ", " + end + ") outside [0, " + text.length() + "]");
        }
        return run(dialect, text, options, start, end, false);
    }

    // ----- implementation ------------------------------------------------------------------

    private static final class Piece {
        final StatementUnit unit;
        final int start;
        final int end;
        final String original;
        String formatted;
        int outStart;

        Piece(StatementUnit unit, String text) {
            this.unit = unit;
            this.start = unit.range().start().offset();
            this.end = unit.range().end().offset();
            this.original = text.substring(start, end);
        }

        String output() {
            return formatted != null ? formatted : original;
        }
    }

    private static FormatResult run(Dialect dialect, String text, FormatOptions options, int rangeStart, int rangeEnd,
                                    boolean whole) {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(text, "text");
        if (text.startsWith(BOM)) {
            // A byte order mark is not SQL: format what follows it and put it back in front.
            FormatResult inner = run(dialect, text.substring(1), options, Math.max(0, rangeStart - 1),
                    Math.max(0, rangeEnd - 1), whole);
            SourceText original = SourceText.of(text);
            List<TextRange> skipped = inner.skipped().stream()
                    .map(r -> original.range(r.start().offset() + 1, r.end().offset() + 1))
                    .toList();
            return new FormatResult(BOM + inner.text(), inner.changed(), inner.warnings(), skipped);
        }
        FormatOptions opts = options == null ? FormatOptions.DEFAULTS : options;
        SourceText source = SourceText.of(text);
        ScriptSplitter splitter = DialectFrontends.get(dialect).splitter();
        List<StatementUnit> units = splitter.split(source);
        String lineSeparator = lineSeparator(text);

        List<String> warnings = new ArrayList<>();
        List<TextRange> skipped = new ArrayList<>();
        List<Piece> pieces = new ArrayList<>(units.size());
        for (StatementUnit u : units) {
            Piece p = new Piece(u, text);
            pieces.add(p);
            boolean selected = whole || overlaps(p, rangeStart, rangeEnd);
            if (!selected || u.kind() != UnitKind.SQL) {
                continue;
            }
            int lineStart = lineStart(text, p.start);
            boolean startsLine = text.substring(lineStart, p.start).isBlank();
            String prefix = !whole && startsLine ? text.substring(lineStart, p.start) : "";
            // assemble() puts a line break before a unit that directly follows a formatted one on the same line
            int startColumn = whole && (startsLine || breakInserted(text, pieces)) ? 0 : p.start - lineStart;
            try {
                String reason = formatUnit(dialect, p, opts, lineSeparator, prefix, startColumn);
                if (reason != null) {
                    warnings.add("Line " + u.range().start().line() + ": statement kept as written (" + reason + ")");
                    skipped.add(u.range());
                }
            } catch (RuntimeException | StackOverflowError e) {
                p.formatted = null;
                warnings.add("Line " + u.range().start().line() + ": statement kept as written (internal error: "
                        + e.getClass().getSimpleName() + ")");
                skipped.add(u.range());
            }
        }

        String out = assemble(dialect, text, pieces, whole, lineSeparator);
        for (int attempt = 0; attempt <= pieces.size(); attempt++) {
            int bad = firstMismatch(splitter, out, pieces);
            if (bad < 0) {
                break;
            }
            Piece culprit = null;
            for (int j = Math.min(bad, pieces.size() - 1); j >= 0 && culprit == null; j--) {
                if (pieces.get(j).formatted != null && !pieces.get(j).formatted.equals(pieces.get(j).original)) {
                    culprit = pieces.get(j);
                }
            }
            if (culprit == null) {
                warnings.add("Script kept as written: it does not split into the same statements after formatting");
                out = text;
                break;
            }
            culprit.formatted = null;
            warnings.add("Line " + culprit.unit.range().start().line()
                    + ": statement kept as written (it would no longer split as one statement)");
            skipped.add(culprit.unit.range());
            out = assemble(dialect, text, pieces, whole, lineSeparator);
        }
        return new FormatResult(out, !out.equals(text), warnings, skipped);
    }

    /** Formats one SQL unit into {@code p.formatted}; returns a reason when it must stay as written. */
    private static String formatUnit(Dialect dialect, Piece p, FormatOptions opts, String lineSeparator, String prefix,
                                     int startColumn) {
        List<FormatToken> tokens = FormatLexer.lex(p.original, dialect, p.unit.terminator());
        if (FormatLexer.hasUnterminated(tokens)) {
            return "unterminated string, quoted name or comment";
        }
        if (isOpaque(dialect, tokens)) {
            return null;
        }
        String formatted = new LayoutEngine(tokens, dialect, opts, lineSeparator, prefix, startColumn).layout();
        String reason = SafetyCheck.compare(dialect, p.original, formatted, tokens,
                FormatLexer.lex(formatted, dialect, p.unit.terminator()));
        if (reason != null) {
            return "safety check: " + reason;
        }
        p.formatted = formatted;
        return null;
    }

    /** Oracle wrapped units and Java sources: their text is not SQL and its line structure matters. */
    private static boolean isOpaque(Dialect dialect, List<FormatToken> tokens) {
        if (dialect != Dialect.ORACLE) {
            return false;
        }
        int words = 0;
        boolean create = false;
        for (FormatToken t : tokens) {
            if (t.isComment()) {
                continue;
            }
            if (t.kind != FormatToken.Kind.WORD || ++words > 14) {
                return false;
            }
            if (words == 1) {
                create = t.upper.equals("CREATE");
            }
            if (create && (t.upper.equals("JAVA") || t.upper.equals("WRAPPED"))) {
                return true;
            }
        }
        return false;
    }

    private static String assemble(Dialect dialect, String text, List<Piece> pieces, boolean whole,
                                   String lineSeparator) {
        StringBuilder out = new StringBuilder(text.length() + text.length() / 4);
        int pos = 0;
        for (int j = 0; j < pieces.size(); j++) {
            Piece p = pieces.get(j);
            String gap = text.substring(pos, p.start);
            if (whole) {
                boolean prevFormatted = j > 0 && pieces.get(j - 1).formatted != null;
                boolean needBreak = j > 0 && prevFormatted && p.formatted != null;
                out.append(normalizeGap(dialect, gap, p.formatted != null, needBreak, false, lineSeparator));
            } else {
                out.append(gap);
            }
            p.outStart = out.length();
            out.append(p.output());
            pos = p.end;
        }
        String tail = text.substring(pos);
        out.append(whole ? normalizeGap(dialect, tail, false, false, true, lineSeparator) : tail);
        return out.toString();
    }

    /**
     * Normalizes the text between two units: at most one blank line in a row, no trailing spaces,
     * no indentation before a formatted unit. Comments and other text in the gap are kept as written.
     */
    static String normalizeGap(Dialect dialect, String gap, boolean beforeFormatted, boolean needBreak, boolean atEnd,
                               String lineSeparator) {
        List<FormatToken> tokens = FormatLexer.lex(gap, dialect, null);
        if (FormatLexer.hasUnterminated(tokens)) {
            return gap;
        }
        StringBuilder out = new StringBuilder(gap.length());
        int pos = 0;
        for (FormatToken t : tokens) {
            out.append(whitespace(gap.substring(pos, t.start), false, false, false, lineSeparator));
            out.append(t.text);
            pos = t.end;
        }
        String last = gap.substring(pos);
        if (tokens.isEmpty() && needBreak && countBreaks(last) == 0) {
            return lineSeparator;
        }
        out.append(whitespace(last, true, beforeFormatted, atEnd, lineSeparator));
        return out.toString();
    }

    private static String whitespace(String ws, boolean last, boolean stripIndent, boolean atEnd, String lineSeparator) {
        int breaks = countBreaks(ws);
        if (breaks == 0) {
            return atEnd ? "" : ws;
        }
        int lastBreak = Math.max(ws.lastIndexOf('\n'), ws.lastIndexOf('\r'));
        String indent = ws.substring(lastBreak + 1);
        String sep = lineSeparator.repeat(atEnd ? 1 : Math.min(breaks, 2));
        return sep + (atEnd || (last && stripIndent) ? "" : indent);
    }

    private static int countBreaks(String s) {
        int breaks = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\n' || (c == '\r' && (k + 1 >= s.length() || s.charAt(k + 1) != '\n'))) {
                breaks++;
            }
        }
        return breaks;
    }

    /** Index of the first unit that differs after re-splitting {@code out}, or -1 when all match. */
    private static int firstMismatch(ScriptSplitter splitter, String out, List<Piece> pieces) {
        List<StatementUnit> after = splitter.split(SourceText.of(out));
        int count = Math.min(after.size(), pieces.size());
        for (int j = 0; j < count; j++) {
            StatementUnit u = after.get(j);
            Piece p = pieces.get(j);
            int s = u.range().start().offset();
            int e = u.range().end().offset();
            if (u.kind() != p.unit.kind() || s != p.outStart || !out.substring(s, e).equals(p.output())) {
                return j;
            }
        }
        return after.size() == pieces.size() ? -1 : count;
    }

    /**
     * Whether {@link #assemble} will start the last piece on a new line although it follows the previous
     * one on the same line: the previous piece is formatted and only blanks lie between them.
     */
    private static boolean breakInserted(String text, List<Piece> pieces) {
        if (pieces.size() < 2) {
            return false;
        }
        Piece prev = pieces.get(pieces.size() - 2);
        Piece cur = pieces.get(pieces.size() - 1);
        return prev.formatted != null && text.substring(prev.end, cur.start).isBlank();
    }

    private static boolean overlaps(Piece p, int start, int end) {
        if (start == end) {
            return p.start <= start && start <= p.end;
        }
        return p.start < end && p.end > start;
    }

    private static int lineStart(String text, int offset) {
        int k = offset;
        while (k > 0 && text.charAt(k - 1) != '\n' && text.charAt(k - 1) != '\r') {
            k--;
        }
        return k;
    }

    private static String lineSeparator(String text) {
        int lf = text.indexOf('\n');
        if (lf > 0 && text.charAt(lf - 1) == '\r') {
            return "\r\n";
        }
        if (lf < 0 && text.indexOf('\r') >= 0) {
            return "\r";
        }
        return "\n";
    }
}
