package com.github.amber.server;

import org.junit.jupiter.api.Test;

/**
 * Mutation fuzzing of the public corpus (all dialects) through every engine method; runs in
 * {@code mvn verify} (failsafe). By default one mutant of every 4th file (the seed picks which); the whole
 * corpus: {@code -Damber.fuzz.stride=1}; deeper runs: {@code -Damber.fuzz.mutants=20 -Damber.fuzz.seed=N}.
 * A failure prints {@code <file>#<seed>}; rerun just that mutant with {@code -Damber.fuzz.repro=<file>#<seed>}.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class MutationFuzzIT {

    @Test
    void corpusSurvivesMutation() throws Exception {
        FuzzCampaign.assertClean(FuzzCampaign.run("corpus", Fuzzer.corpus(), 1, 4));
    }

    /** Unmutated corpus: every analysis, strict and with fallback, has consistent ranges and statement trees. */
    @Test
    void corpusAnalysesAreConsistent() {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (Fuzzer.Input in : Fuzzer.corpus()) {
            for (boolean fallback : new boolean[]{false, true}) {
                Robust.Outcome o = Robust.call("analyze", Robust.params(in.dialect(), in.text(), fallback),
                        java.time.Duration.ofSeconds(60));
                String problem = !o.ok() ? (o.hung() ? "hang" : Robust.describe(o.error()))
                        : java.util.Objects.requireNonNullElse(Robust.checkRanges(o.json(), Robust.Texts.of(in.text())),
                        java.util.Objects.requireNonNullElse(Robust.checkAnalysis(o.json()), ""));
                if (!problem.isEmpty()) {
                    bad.add(in.name() + (fallback ? " (fallback)" : "") + ": " + problem);
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(bad.isEmpty(), bad.size() + " files, first: " + bad);
    }
}
