package com.github.amber.format;

/**
 * Output buffer of the {@link LayoutEngine}: lines of tokens with an indentation, a line separator and
 * an optional prefix that is put before every line except the first (used when formatting a statement
 * that sits indented inside unformatted text). The writer itself never produces trailing whitespace;
 * token text (such as a line comment ending in spaces) is written exactly.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class LineWriter {

    private final StringBuilder sb = new StringBuilder();
    private final String lineSeparator;
    private final String prefix;
    private boolean any;
    private boolean lineHasContent;
    private boolean blankDone;
    private int column;
    private int lineIndent;

    LineWriter(String lineSeparator, String prefix, int startColumn) {
        this.lineSeparator = lineSeparator;
        this.prefix = prefix;
        this.column = startColumn;
    }

    /** Ends the current line (if it has content) so the next write starts at {@code indent}. */
    void newline(int indent, boolean blankLine) {
        if (!any) {
            return;                                   // stay on the line where the unit starts
        }
        if (lineHasContent) {
            sb.append(lineSeparator);
            lineHasContent = false;
            blankDone = false;
        }
        if (blankLine && !blankDone) {
            sb.append(lineSeparator);
            blankDone = true;
        }
        lineIndent = indent;
        column = indent;
    }

    void write(String text, boolean spaceBefore) {
        if (!lineHasContent && any) {
            sb.append(prefix);
            sb.append(" ".repeat(lineIndent));
            column = lineIndent;
        } else if (spaceBefore && lineHasContent) {
            sb.append(' ');
            column++;
        }
        sb.append(text);
        int lastBreak = Math.max(text.lastIndexOf('\n'), text.lastIndexOf('\r'));
        column = lastBreak >= 0 ? text.length() - lastBreak - 1 : column + text.length();
        lineHasContent = true;
        any = true;
    }

    boolean hasLineContent() {
        return lineHasContent;
    }

    int column() {
        return column;
    }

    int lineIndent() {
        return lineIndent;
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
