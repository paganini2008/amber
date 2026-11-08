package com.github.amber.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Fast mutation fuzzing in the default test run: the desktop app's eight sample scripts, several
 * deterministic mutants each, through every engine method in strict and fallback mode (see
 * {@link Fuzzer}). The whole corpus is fuzzed by {@link MutationFuzzIT}.
 *
 * @Author: Fred Feng
 * @Date: 08/11/2026
 * @Version 1.0.0
 */
class MutationFuzzTest {

    @Test
    void samplesSurviveMutation() throws Exception {
        List<Fuzzer.Input> samples = Fuzzer.samples();
        assumeFalse(samples.isEmpty(), "desktop samples not present");
        FuzzCampaign.assertClean(FuzzCampaign.run("samples", samples, 12));
    }
}
