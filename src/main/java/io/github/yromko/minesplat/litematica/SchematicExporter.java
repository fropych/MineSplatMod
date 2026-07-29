package io.github.yromko.minesplat.litematica;

import io.github.yromko.minesplat.palette.MatchedVoxels;
import io.github.yromko.minesplat.workflow.GenerationState;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@FunctionalInterface
public interface SchematicExporter {
    CompletableFuture<Path> saveAndPlace(
            String requestedName,
            int resolution,
            MatchedVoxels voxels,
            Consumer<GenerationState> stateConsumer
    );
}
