package com.github.amber.dialect.split;

/**
 * A coarse token produced by {@link SqlScanner}. Offsets are 0-based char indexes, end exclusive.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record ScanToken(Type type, int start, int end, String text) {

    public enum Type {
        WORD,
        QUOTED_IDENTIFIER,
        STRING,
        NUMBER,
        SYMBOL,
        /** A string, quoted identifier or comment that runs to the end of input. */
        UNTERMINATED
    }

    public boolean isWord(String word) {
        return type == Type.WORD && text.equalsIgnoreCase(word);
    }

    public boolean isWordIn(java.util.Set<String> upperCaseWords) {
        return type == Type.WORD && upperCaseWords.contains(text.toUpperCase(java.util.Locale.ROOT));
    }

    public boolean isSymbol(char c) {
        return type == Type.SYMBOL && text.length() == 1 && text.charAt(0) == c;
    }

    public String upper() {
        return text.toUpperCase(java.util.Locale.ROOT);
    }
}
