package com.github.amber.dialect;

import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.source.SourceText;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns ANTLR lexer and parser errors into {@link Diagnostic}s with exact source ranges.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class SyntaxErrorCollector extends BaseErrorListener {

    private static final int MAX_EXPECTED = 12;

    private final SourceText source;
    private final int base;
    private final int unitEnd;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /**
     * @param base    offset of the parsed unit in the whole script; ANTLR offsets are relative to the unit
     * @param unitEnd offset just after the unit; end-of-input errors point here, not at the end of the script
     */
    SyntaxErrorCollector(SourceText source, int base, int unitEnd) {
        this.source = source;
        this.base = base;
        this.unitEnd = unitEnd;
    }

    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                            String msg, RecognitionException e) {
        if (recognizer instanceof Lexer lexer) {
            int start = clamp(base + lexer._tokenStartCharIndex);
            int end = clamp(Math.max(start + 1, base + lexer.getInputStream().index()));
            diagnostics.add(Diagnostic.error("lexer", msg, source.range(start, end), source.text().substring(start, end), List.of()));
            return;
        }
        Token token = offendingSymbol instanceof Token t ? t : null;
        String offending = null;
        int start;
        int end;
        if (token == null || token.getType() == Token.EOF || token.getStartIndex() < 0) {
            start = end = unitEnd;
        } else {
            start = clamp(base + token.getStartIndex());
            end = clamp(Math.max(start, base + token.getStopIndex() + 1));
            offending = token.getText();
        }
        diagnostics.add(Diagnostic.error("syntax", msg, source.range(start, end), offending, expected(recognizer, e)));
    }

    private static List<String> expected(Recognizer<?, ?> recognizer, RecognitionException e) {
        if (!(recognizer instanceof Parser parser)) {
            return List.of();
        }
        IntervalSet set;
        try {
            set = e != null && e.getExpectedTokens() != null ? e.getExpectedTokens() : parser.getExpectedTokens();
        } catch (RuntimeException ignored) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (int type : set.toList()) {
            if (names.size() == MAX_EXPECTED) {
                names.add("…");
                break;
            }
            names.add(parser.getVocabulary().getDisplayName(type));
        }
        return names;
    }

    private int clamp(int offset) {
        return Math.max(0, Math.min(offset, source.length()));
    }
}
