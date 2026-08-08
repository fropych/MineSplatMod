package io.github.yromko.minesplat.cnb;

public record CnbPlacementProgress(
        int placedHostBlocks,
        int totalHostBlocks,
        String message
) {
}
