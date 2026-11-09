package com.github.amber.dialect;

import com.github.amber.core.config.Env;
import com.github.amber.core.source.InvisibleCharacters;
import com.github.amber.core.source.SourceText;
import com.github.amber.dialect.split.StatementUnit;
import com.github.amber.dialect.split.UnitKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Splits a script with the dialect's splitter and parses the SQL units in parallel.
 *
 * <p>Parser threads get a large stack: ANTLR recursion depth grows with expression nesting, and real
 * scripts contain very long CASE and concatenation chains.
 *
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public final class ScriptParser {

    private static final long PARSER_STACK_BYTES = 256L * 1024 * 1024;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(1, Env.get().integer("AMBER_PARSER_THREADS",
                    Math.max(2, Runtime.getRuntime().availableProcessors()))), new ParserThreadFactory());

    private ScriptParser() {
    }

    public static ScriptParse parse(DialectFrontend frontend, SourceText original, ParseOptions options) {
        long started = System.nanoTime();
        InvisibleCharacters.Result cleaned = InvisibleCharacters.normalize(original);
        SourceText source = cleaned.warnings().isEmpty() ? original : new SourceText(original.name(), cleaned.text());
        List<StatementUnit> units = frontend.splitter().split(source);

        List<Future<UnitParse>> futures = new ArrayList<>(units.size());
        for (StatementUnit unit : units) {
            if (unit.kind() == UnitKind.SQL) {
                futures.add(POOL.submit(() -> frontend.parseUnit(source, unit)));
            } else {
                futures.add(CompletableFuture.completedFuture(UnitParse.skipped(unit)));
            }
        }

        List<UnitParse> results = new ArrayList<>(units.size());
        for (Future<UnitParse> future : futures) {
            UnitParse result = await(future);
            if (result.status() == UnitStatus.FAILED && options.allowFallback()) {
                result = result.withStatus(UnitStatus.DEGRADED);
            }
            results.add(result);
        }
        return new ScriptParse(frontend.dialect(), source, options, results,
                Duration.ofNanos(System.nanoTime() - started), cleaned.warnings());
    }

    private static UnitParse await(Future<UnitParse> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new CompletionException(e);
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause());
        }
    }

    private static final class ParserThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(null, r, "amber-parser-" + counter.incrementAndGet(), PARSER_STACK_BYTES);
            t.setDaemon(true);
            return t;
        }
    }
}
