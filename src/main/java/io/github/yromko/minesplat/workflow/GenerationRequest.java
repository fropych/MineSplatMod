package io.github.yromko.minesplat.workflow;

import io.github.yromko.minesplat.config.PaletteProfile;
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
        Set<String> blacklistedBlocks
) {
    public GenerationRequest {
        blacklistedBlocks = Set.copyOf(blacklistedBlocks);
    }
}
