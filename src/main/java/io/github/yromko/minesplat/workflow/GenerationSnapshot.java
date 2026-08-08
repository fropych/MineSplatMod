package io.github.yromko.minesplat.workflow;

import io.github.yromko.minesplat.config.OutputMode;

import java.nio.file.Path;
import java.util.Map;

public record GenerationSnapshot(
        GenerationState state,
        String message,
        String error,
        String device,
        String jobId,
        boolean queued,
        boolean pollingPaused,
        int resolution,
        int width,
        int height,
        int depth,
        int blockCount,
        Map<String, Integer> materials,
        Path outputFile,
        OutputMode outputMode
) {
    public GenerationSnapshot {
        materials = Map.copyOf(materials);
    }

    public static GenerationSnapshot idle() {
        return new GenerationSnapshot(
                GenerationState.IDLE, "Ready", null, null, null,
                false, false, 64, 0, 0, 0, 0, Map.of(), null,
                OutputMode.LITEMATICA);
    }

    public boolean canCancel() {
        return state.active();
    }
}
