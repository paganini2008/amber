package com.github.amber.server;

import com.github.amber.core.Dialect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the {@link Fuzzer} over a set of files, several mutants each, in parallel, and fails with the
 * list of problems. Problems in the rules/rewrite packages are printed, not failed.
 *
 * <p>System properties: {@code amber.fuzz.seed} (base seed, default {@value #DEFAULT_SEED}),
 * {@code amber.fuzz.mutants} (mutants per file, overrides the caller's count) and {@code amber.fuzz.repro}
 * ({@code <file name>#<seed>}: run only that mutant, as printed in a failure) and {@code amber.fuzz.stride}
 * (mutate every n-th file only; 1 = all).
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
final class FuzzCampaign {

    static final long DEFAULT_SEED = 20261108L;

    private FuzzCampaign() {
    }

    static Robust.Recorder run(String title, List<Fuzzer.Input> inputs, int mutantsPerFile) throws Exception {
        return run(title, inputs, mutantsPerFile, 1);
    }

    /** As {@link #run(String, List, int)}, mutating only every {@code defaultStride}-th file (which ones depends on the seed). */
    static Robust.Recorder run(String title, List<Fuzzer.Input> inputs, int mutantsPerFile, int defaultStride)
            throws Exception {
        long base = Long.getLong("amber.fuzz.seed", DEFAULT_SEED);
        int mutants = Integer.getInteger("amber.fuzz.mutants", mutantsPerFile);
        String repro = System.getProperty("amber.fuzz.repro");
        Robust.Recorder rec = new Robust.Recorder();
        rec.deferTiming(true);                       // budgets are judged again without contention, below
        Fuzzer fuzzer = new Fuzzer(rec);
        warmUp(fuzzer, inputs);
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        Map<String, Runnable> rerun = new ConcurrentHashMap<>();
        try {
            List<Future<?>> jobs = new ArrayList<>();
            int stride = Math.max(1, Integer.getInteger("amber.fuzz.stride", defaultStride));
            for (int f = 0; f < inputs.size(); f++) {
                Fuzzer.Input in = inputs.get(f);
                if (f % stride != (int) (base % stride) && repro == null) {
                    continue;
                }
                for (int k = 0; k < mutants; k++) {
                    long seed = Fuzzer.seed(base, in.name(), k);
                    if (repro != null && !repro.equals(in.name() + "#" + seed)) {
                        continue;
                    }
                    Mutator.Mutant m = Mutator.mutate(in.text(), seed);
                    String subject = in.name() + "#" + seed + " [" + m.recipe() + "]";
                    rerun.put(subject, () -> new Fuzzer(rec).run(subject, in.dialect(), in.text(), m.text(), seed));
                    jobs.add(pool.submit(() -> {
                        trace("start " + subject);
                        long s0 = System.nanoTime();
                        fuzzer.run(subject, in.dialect(), in.text(), m.text(), seed);
                        trace("end " + (System.nanoTime() - s0) / 1_000_000 + " ms, heap "
                                + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1 << 20)
                                + " MB " + subject);
                    }));
                }
            }
            for (Future<?> f : jobs) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        // a mutant over a time budget under parallel load is run again alone; only then its timing counts
        rec.deferTiming(false);
        for (String subject : rec.slowCandidates()) {
            trace("rerun alone " + subject);
            rec.timingOnly(true);
            try {
                rerun.get(subject).run();
            } finally {
                rec.timingOnly(false);
            }
        }
        System.out.printf("%s: %d files x %d mutants, %d calls in %d ms, slowest call %s%n", title, inputs.size(),
                mutants, rec.calls(), (System.nanoTime() - t0) / 1_000_000, rec.slowest());
        System.gc();
        System.out.printf("%s: heap in use after the campaign %d MB%n", title,
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
        rec.foreign().forEach(p -> System.out.println("  REPORTED " + p));
        rec.own().forEach(p -> System.out.println("  PROBLEM " + p));
        return rec;
    }

    /** With {@code -Damber.fuzz.trace=FILE}, every mutant's start and end is appended to FILE (to find a crash). */
    static synchronized void trace(String line) {
        String file = System.getProperty("amber.fuzz.trace");
        if (file != null) {
            try {
                Files.writeString(Path.of(file), line + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static void assertClean(Robust.Recorder rec) {
        List<Robust.Problem> own = rec.own();
        assertTrue(own.isEmpty(), own.size() + " problems, first: " + (own.isEmpty() ? "" : own.get(0)));
    }

    /** One pass over the unmutated files, so JIT and ANTLR's DFA caches do not count against the budgets. */
    private static void warmUp(Fuzzer fuzzer, List<Fuzzer.Input> inputs) {
        Set<Dialect> done = EnumSet.noneOf(Dialect.class);
        for (Fuzzer.Input in : inputs) {
            if (done.add(in.dialect())) {
                Robust.call("analyze", Robust.params(in.dialect(), in.text(), true), Duration.ofMinutes(2));
                Robust.call("format", Robust.params(in.dialect(), in.text(), true), Duration.ofMinutes(2));
            }
        }
    }
}
