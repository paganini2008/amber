package com.github.amber.core.source;

/**
 * A position in a source text. {@code offset} is a 0-based char index; {@code line} and
 * {@code column} are 1-based, matching what editors show.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public record TextPosition(int offset, int line, int column) {
}
