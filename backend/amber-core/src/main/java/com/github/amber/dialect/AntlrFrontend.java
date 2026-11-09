package com.github.amber.dialect;

import com.github.amber.core.diagnostic.Diagnostic;
import com.github.amber.core.diagnostic.Severity;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.StatementUnit;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared parsing driver for ANTLR grammars, using two-stage prediction: a fast SLL pass that bails
 * on the first problem, then a full LL pass with error reporting only when SLL fails.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public abstract class AntlrFrontend<L extends Lexer, P extends Parser> implements DialectFrontend {

    protected abstract L createLexer(CharStream input);

    protected abstract P createParser(CommonTokenStream tokens);

    /** Invokes the grammar's entry rule, e.g. {@code parser.sql_script()}. */
    protected abstract ParserRuleContext entry(P parser);

    /** Hook for dialect-specific parser settings (server version, SQL mode, …). */
    protected void configure(L lexer, P parser) {
    }

    /**
     * Hook to rewrite unit text before lexing, e.g. masking SQL*Plus substitution variables.
     * Must return a string of the <em>same length</em> so offsets stay valid.
     */
    protected String prepare(StatementUnit unit, String unitText) {
        return unitText;
    }

    private static final Pattern VERSIONED = Pattern.compile("/\\*!\\d*");

    /**
     * Blanks the {@code /*!40101} opener and its {@code *\/} of MySQL versioned comments so the grammar
     * sees the SQL inside; the text keeps its length.
     */
    protected static String unwrapVersionedComments(String text) {
        if (!text.contains("/*!")) {
            return text;
        }
        char[] out = text.toCharArray();
        Matcher m = VERSIONED.matcher(text);
        int from = 0;
        while (m.find(from)) {
            int close = text.indexOf("*/", m.end());
            if (close < 0) {
                break;
            }
            Arrays.fill(out, m.start(), m.end(), ' ');
            out[close] = ' ';
            out[close + 1] = ' ';
            from = close + 2;
        }
        return new String(out);
    }

    /** Replaces a trailing client delimiter ({@code $$}, {@code //}, {@code \G}) with {@code ;} padded to the same length. */
    protected static String terminatorToSemicolon(StatementUnit unit, String unitText) {
        String t = unit.terminator();
        if (t == null || !unitText.endsWith(t)) {
            return unitText;
        }
        return unitText.substring(0, unitText.length() - t.length()) + ";" + " ".repeat(t.length() - 1);
    }

    /**
     * Hook to refuse a unit before parsing when its tokens are known to drive this grammar's prediction
     * into exponential time and memory; returns the reason, or null to parse.
     */
    protected String tooComplex(CommonTokenStream tokens) {
        return null;
    }

    /**
     * Most DFA states one grammar (parser and lexer) may keep. ANTLR caches prediction DFAs in static arrays
     * for the life of the engine; after the whole corpus they hold about 91,000 states (~620 MB over all
     * dialects, T-SQL alone 23,000), and they keep growing with new input. Past the limit the cache is
     * dropped and rebuilt lazily, so a long-running engine stays within its heap.
     */
    static volatile int maxDfaStates = 30_000;
    /** The cache size is checked every this many units parsed by one frontend. */
    static volatile int dfaCheckEvery = 256;

    private final AtomicInteger unitsParsed = new AtomicInteger();

    private void limitDfaCache(L lexer, P parser) {
        if (unitsParsed.incrementAndGet() % Math.max(1, dfaCheckEvery) != 0) {
            return;
        }
        if (dfaStates(parser.getInterpreter().decisionToDFA) + dfaStates(lexer.getInterpreter().decisionToDFA)
                > maxDfaStates) {
            parser.getInterpreter().clearDFA();
            lexer.getInterpreter().clearDFA();
        }
    }

    /** DFA states cached for a parser or lexer grammar (shared by all its instances). */
    static long dfaStates(DFA[] dfas) {
        long n = 0;
        for (DFA dfa : dfas) {
            n += dfa.states.size();
        }
        return n;
    }

    /** A unit that could not be parsed at all: one syntax error over the whole unit, no tree. */
    private static UnitParse failed(StatementUnit unit, String reason, PredictionMode mode, long started) {
        // kept as a syntax error: with fallback the rest of the script is still analyzed
        Diagnostic d = new Diagnostic(Severity.ERROR, "syntax", reason, unit.range(),
                null, List.of());
        return new UnitParse(unit, UnitStatus.FAILED, null, mode, List.of(d),
                Duration.ofNanos(System.nanoTime() - started));
    }

    @Override
    public UnitParse parseUnit(SourceText source, StatementUnit unit) {
        long started = System.nanoTime();
        int base = unit.range().start().offset();
        String original = source.slice(unit.range());
        String text = prepare(unit, original);
        if (text.length() != original.length()) {
            throw new IllegalStateException(getClass().getSimpleName() + ".prepare changed the text length");
        }
        SyntaxErrorCollector errors = new SyntaxErrorCollector(source, base, unit.range().end().offset());

        L lexer = createLexer(CharStreams.fromString(text, source.name()));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        String tooComplex = tooComplex(tokens);
        if (tooComplex != null) {
            return failed(unit, tooComplex, PredictionMode.SLL, started);
        }

        P parser = createParser(tokens);
        configure(lexer, parser);
        GuardedPrediction.install(parser);
        parser.removeErrorListeners();
        parser.setErrorHandler(new BailErrorStrategy());
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);

        ParserRuleContext tree;
        PredictionMode mode = PredictionMode.SLL;
        try {
            try {
                tree = entry(parser);
            } catch (ParseCancellationException sllFailed) {
                parser.reset();
                parser.addErrorListener(errors);
                parser.setErrorHandler(new DefaultErrorStrategy());
                parser.getInterpreter().setPredictionMode(PredictionMode.LL);
                mode = PredictionMode.LL;
                tree = entry(parser);
            }
        } catch (GuardedPrediction.TooComplex e) {
            return failed(unit, e.getMessage(), mode, started);
        }
        limitDfaCache(lexer, parser);
        UnitStatus status = errors.diagnostics().isEmpty() ? UnitStatus.PARSED : UnitStatus.FAILED;
        return new UnitParse(unit, status, tree, mode, errors.diagnostics(),
                Duration.ofNanos(System.nanoTime() - started));
    }
}
