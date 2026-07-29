package io.github.yromko.minesplat.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiModelsTest {
    @ParameterizedTest
    @ValueSource(strings = {"succeeded", "failed", "cancelled", "expired"})
    void recognizesEveryTerminalJobStatus(String status) {
        assertTrue(job(status).terminal());
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "running"})
    void keepsQueuedAndRunningJobsNonTerminal(String status) {
        assertFalse(job(status).terminal());
    }

    private static ApiModels.Job job(String status) {
        return new ApiModels.Job(
                "job-1", "generation", status, null, "input-1", Map.of(), Map.of());
    }
}
