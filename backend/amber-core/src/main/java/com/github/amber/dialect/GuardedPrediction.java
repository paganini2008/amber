package com.github.amber.dialect;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ParserATNSimulator;

import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * ANTLR prediction with a work limit. Adaptive prediction can need exponential work on some inputs; every
 * closure step of one prediction is counted, and past {@link #MAX_CLOSURE_STEPS} the parse of the unit is
 * abandoned with {@link TooComplex} and the unit reported as a syntax error instead of taking the engine
 * down. The same check notices thread interrupts, so {@code $/cancel} also stops a parse that is busy
 * predicting. (Explosions inside ANTLR's prediction-context merge do not pass through here; grammars
 * known for them refuse such units up front, see {@code AntlrFrontend#tooComplex}.)
 *
 * <p>The limit is far above what real scripts need: the largest prediction in the whole corpus (all
 * dialects) takes {@value #CORPUS_MAX_STEPS} steps.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
public final class GuardedPrediction extends ParserATNSimulator {

    /** Closure steps allowed for a single prediction. */
    static final long MAX_CLOSURE_STEPS = 20_000_000;
    /** Largest number of closure steps of one prediction measured over the corpus (SQL Server), for reference. */
    static final long CORPUS_MAX_STEPS = 1_085_980;

    /** Thrown when one prediction needs more work than {@link #MAX_CLOSURE_STEPS}. */
    public static final class TooComplex extends RuntimeException {
        TooComplex(String message) {
            super(message, null, false, false);
        }
    }

    private long steps;
    private long maxSteps;

    private GuardedPrediction(Parser parser, ParserATNSimulator original) {
        super(parser, original.atn, original.decisionToDFA, original.getSharedContextCache());
        setPredictionMode(original.getPredictionMode());
    }

    /** Replaces the parser's simulator by a guarded one sharing the same DFA cache. */
    public static GuardedPrediction install(Parser parser) {
        GuardedPrediction g = new GuardedPrediction(parser, parser.getInterpreter());
        parser.setInterpreter(g);
        return g;
    }

    /** The most closure steps a single prediction of this parse needed. */
    public long maxSteps() {
        return maxSteps;
    }

    @Override
    public int adaptivePredict(TokenStream input, int decision, ParserRuleContext outerContext) {
        steps = 0;
        try {
            return super.adaptivePredict(input, decision, outerContext);
        } finally {
            maxSteps = Math.max(maxSteps, steps);
        }
    }

    @Override
    protected void closureCheckingStopState(ATNConfig config, ATNConfigSet configs, Set<ATNConfig> closureBusy,
                                            boolean collectPredicates, boolean fullCtx, int depth,
                                            boolean treatEofAsEpsilon) {
        if ((++steps & 0xFFF) == 0) {
            if (steps > MAX_CLOSURE_STEPS) {
                throw new TooComplex("The statement is too complex to parse (prediction needed more than "
                        + MAX_CLOSURE_STEPS + " steps); deeply nested parentheses?");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("parse interrupted");
            }
        }
        super.closureCheckingStopState(config, configs, closureBusy, collectPredicates, fullCtx, depth,
                treatEofAsEpsilon);
    }
}
