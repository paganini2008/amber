package com.github.amber.format;

/**
 * Layout settings of the {@link Formatter}.
 *
 * @param indentSize   spaces per indentation level (default 4)
 * @param keywordCase  how reserved words are cased (default {@link KeywordCase#UPPER})
 * @param maxLineWidth preferred maximum line width; lists and parenthesized groups that would exceed it
 *                     are laid out over several lines (default 100)
 * @param commaStyle   where commas go when a list is laid out one item per line (default
 *                     {@link CommaStyle#TRAILING})
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record FormatOptions(int indentSize, KeywordCase keywordCase, int maxLineWidth, CommaStyle commaStyle) {

    /** Four-space indent, upper-case keywords, 100 columns, trailing commas. */
    public static final FormatOptions DEFAULTS = new FormatOptions(4, KeywordCase.UPPER, 100, CommaStyle.TRAILING);

    /** Casing applied to reserved words; identifiers, strings and comments are never changed. */
    public enum KeywordCase {
        UPPER,
        LOWER,
        PRESERVE
    }

    /** Position of the comma in lists laid out one item per line. */
    public enum CommaStyle {
        /** {@code a,} / {@code b} */
        TRAILING,
        /** {@code a} / {@code , b} */
        LEADING
    }

    public FormatOptions {
        if (indentSize < 0 || indentSize > 16) {
            throw new IllegalArgumentException("indentSize must be between 0 and 16: " + indentSize);
        }
        if (maxLineWidth < 20) {
            throw new IllegalArgumentException("maxLineWidth must be at least 20: " + maxLineWidth);
        }
        if (keywordCase == null) {
            keywordCase = KeywordCase.UPPER;
        }
        if (commaStyle == null) {
            commaStyle = CommaStyle.TRAILING;
        }
    }

    public FormatOptions withIndentSize(int size) {
        return new FormatOptions(size, keywordCase, maxLineWidth, commaStyle);
    }

    public FormatOptions withKeywordCase(KeywordCase kc) {
        return new FormatOptions(indentSize, kc, maxLineWidth, commaStyle);
    }

    public FormatOptions withMaxLineWidth(int width) {
        return new FormatOptions(indentSize, keywordCase, width, commaStyle);
    }

    public FormatOptions withCommaStyle(CommaStyle style) {
        return new FormatOptions(indentSize, keywordCase, maxLineWidth, style);
    }
}
