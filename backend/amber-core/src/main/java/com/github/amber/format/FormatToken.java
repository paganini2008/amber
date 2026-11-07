package com.github.amber.format;

import java.util.Locale;

/**
 * A token of the comment-preserving {@link FormatLexer}. Offsets are relative to the lexed text.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class FormatToken {

    enum Kind {
        WORD,
        QUOTED,
        STRING,
        NUMBER,
        SYMBOL,
        LINE_COMMENT,
        BLOCK_COMMENT,
        /** A client delimiter that ends the unit, such as {@code $$} after MySQL {@code DELIMITER $$}. */
        TERMINATOR,
        /** A string, quoted identifier or comment that runs to the end of the text. */
        UNTERMINATED
    }

    final Kind kind;
    final String text;
    final int start;
    final int end;
    /** Line breaks in the whitespace right before this token. */
    final int newlinesBefore;
    /** True when any whitespace separates this token from the previous one. */
    final boolean spaceBefore;
    /** Upper-case text for words, {@code null} for other kinds. */
    final String upper;

    FormatToken(Kind kind, String text, int start, int end, int newlinesBefore, boolean spaceBefore) {
        this.kind = kind;
        this.text = text;
        this.start = start;
        this.end = end;
        this.newlinesBefore = newlinesBefore;
        this.spaceBefore = spaceBefore;
        this.upper = kind == Kind.WORD ? text.toUpperCase(Locale.ROOT) : null;
    }

    boolean isComment() {
        return kind == Kind.LINE_COMMENT || kind == Kind.BLOCK_COMMENT;
    }

    boolean isSymbol(String s) {
        return kind == Kind.SYMBOL && text.equals(s);
    }

    boolean isMultiLine() {
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    @Override
    public String toString() {
        return kind + "(" + text + ")";
    }
}
