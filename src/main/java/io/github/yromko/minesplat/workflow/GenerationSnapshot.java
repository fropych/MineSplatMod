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
        OutputMode outputMode,
        double imageGenerationSeconds,
        double modelGenerationSeconds
) {
    public GenerationSnapshot {
        materials = Map.copyOf(materials);
        imageGenerationSeconds = validDuration(imageGenerationSeconds);
        modelGenerationSeconds = validDuration(modelGenerationSeconds);
    }

    public static GenerationSnapshot idle() {
        return new GenerationSnapshot(
                GenerationState.IDLE, "Ready", null, null, null,
                false, false, 64, 0, 0, 0, 0, Map.of(), null,
                OutputMode.LITEMATICA, -1.0, -1.0);
    }

    public boolean canCancel() {
        return state.active();
    }

    public boolean hasImageGenerationTime() {
        return imageGenerationSeconds >= 0.0;
    }

    public boolean hasModelGenerationTime() {
        return modelGenerationSeconds >= 0.0;
    }

    private static double validDuration(double value) {
        return Double.isFinite(value) && value >= 0.0 ? value : -1.0;
    }
}
