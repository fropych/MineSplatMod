package io.github.yromko.minesplat.cnb;

import net.minecraft.util.math.BlockPos;

public record CnbPlacementSnapshot(
        CnbPlacementState state,
        String message,
        String blueprintName,
        int rotationDegrees,
        int width,
        int height,
        int depth,
        int placedHostBlocks,
        int totalHostBlocks,
        BlockPos origin,
        boolean valid
) {
    public static CnbPlacementSnapshot inactive() {
        return new CnbPlacementSnapshot(
                CnbPlacementState.INACTIVE,
                "",
                "",
                0,
                0, 0, 0,
                0, 0,
                BlockPos.ORIGIN,
                false);
    }
}
