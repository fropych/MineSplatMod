package io.github.yromko.minesplat.workflow;

import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.config.OutputMode;
import io.github.yromko.minesplat.config.VoxelPreset;

import java.nio.file.Path;
import java.util.Set;

public record GenerationRequest(
        String serverUrl,
        Path image,
        String schematicName,
        long seed,
        VoxelPreset preset,
        PaletteProfile paletteProfile,
        Set<String> blacklistedBlocks,
        OutputMode outputMode
) {
    public GenerationRequest {
        blacklistedBlocks = Set.copyOf(blacklistedBlocks);
        outputMode = outputMode == null ? OutputMode.LITEMATICA : outputMode;
    }
}
