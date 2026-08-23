package io.github.yromko.minesplat.cnb;

import io.github.yromko.minesplat.palette.PaletteEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class UnavailableCnbIntegration implements CnbIntegration {
    private final String reason;

    public UnavailableCnbIntegration(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public int bitsPerBlockSide() {
        return 16;
    }

    @Override
    public CompletableFuture<List<PaletteEntry>> filterEligible(
            List<PaletteEntry> candidates
    ) {
        return CompletableFuture.failedFuture(new IllegalStateException(reason));
    }

    @Override
    public CompletableFuture<CnbPlacementResult> place(
            MinecraftClient client,
            CnbBlueprint blueprint,
            CnbPackedModel packed,
            int quarterTurns,
            BlockPos origin,
            Consumer<CnbPlacementProgress> progress
    ) {
        return CompletableFuture.failedFuture(new IllegalStateException(reason));
    }

    @Override
    public void cancelPlacement() {
    }
}
