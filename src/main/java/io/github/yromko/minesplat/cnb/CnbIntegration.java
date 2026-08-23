package io.github.yromko.minesplat.cnb;

import io.github.yromko.minesplat.palette.PaletteEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface CnbIntegration {
    boolean available();

    String unavailableReason();

    int bitsPerBlockSide();

    CompletableFuture<List<PaletteEntry>> filterEligible(List<PaletteEntry> candidates);

    CompletableFuture<CnbPlacementResult> place(
            MinecraftClient client,
            CnbBlueprint blueprint,
            CnbPackedModel packed,
            int quarterTurns,
            BlockPos origin,
            Consumer<CnbPlacementProgress> progress
    );

    void cancelPlacement();
}
