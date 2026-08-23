package io.github.yromko.minesplat.cnb;

import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.palette.MatchedVoxels;
import net.minecraft.SharedConstants;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class CnbBlueprintExporter {
    private final CnbBlueprintStore store;
    private final Supplier<String> author;
    private final Supplier<Integer> dataVersion;
    private final Executor ioExecutor;

    public CnbBlueprintExporter(
            CnbBlueprintStore store,
            Supplier<String> author,
            Executor ioExecutor
    ) {
        this(
                store,
                author,
                () -> SharedConstants.getGameVersion().dataVersion().id(),
                ioExecutor);
    }

    public CnbBlueprintExporter(
            CnbBlueprintStore store,
            Supplier<String> author,
            Supplier<Integer> dataVersion,
            Executor ioExecutor
    ) {
        this.store = store;
        this.author = author;
        this.dataVersion = dataVersion;
        this.ioExecutor = ioExecutor;
    }

    public CompletableFuture<Path> save(
            String requestedName,
            int resolution,
            PaletteProfile profile,
            MatchedVoxels voxels
    ) {
        CnbBlueprint blueprint = CnbBlueprint.fromMatched(
                requestedName,
                author.get(),
                Instant.now().toEpochMilli(),
                dataVersion.get(),
                resolution,
                profile,
                voxels);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.writeUnique(blueprint);
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot save MineSplat blueprint", exception);
            }
        }, ioExecutor);
    }
}
