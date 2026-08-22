package io.github.yromko.minesplat.workflow;

import io.github.yromko.minesplat.config.OutputMode;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.config.GenerationPreset;
import io.github.yromko.minesplat.config.VoxelPreset;
import io.github.yromko.minesplat.inference.InferenceTarget;

import java.nio.file.Path;
import java.util.Set;

public record GenerationRequest(
        InferenceTarget target,
        GenerationSource source,
        String schematicName,
        long seed,
        GenerationPreset generationPreset,
        VoxelPreset preset,
        PaletteProfile paletteProfile,
        Set<String> blacklistedBlocks,
        OutputMode outputMode
) {
    public GenerationRequest {
        if (target == null) {
            throw new IllegalArgumentException("Inference target is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("Generation source is required");
        }
        generationPreset = generationPreset == null ? GenerationPreset.BASE : generationPreset;
        blacklistedBlocks = Set.copyOf(blacklistedBlocks);
        outputMode = outputMode == null ? OutputMode.LITEMATICA : outputMode;
    }

    public GenerationRequest(
            InferenceTarget target,
            GenerationSource source,
            String schematicName,
            long seed,
            VoxelPreset preset,
            PaletteProfile paletteProfile,
            Set<String> blacklistedBlocks,
            OutputMode outputMode
    ) {
        this(target, source, schematicName, seed, GenerationPreset.XHIGH, preset,
                paletteProfile, blacklistedBlocks, outputMode);
    }

    public GenerationRequest(
            InferenceTarget target,
            Path image,
            String schematicName,
            long seed,
            GenerationPreset generationPreset,
            VoxelPreset preset,
            PaletteProfile paletteProfile,
            Set<String> blacklistedBlocks,
            OutputMode outputMode
    ) {
        this(target, new GenerationSource.Image(image), schematicName, seed, generationPreset, preset,
                paletteProfile, blacklistedBlocks, outputMode);
    }

    public GenerationRequest(
            InferenceTarget target,
            Path image,
            String schematicName,
            long seed,
            VoxelPreset preset,
            PaletteProfile paletteProfile,
            Set<String> blacklistedBlocks,
            OutputMode outputMode
    ) {
        this(target, image, schematicName, seed, GenerationPreset.XHIGH, preset,
                paletteProfile, blacklistedBlocks, outputMode);
    }
}
