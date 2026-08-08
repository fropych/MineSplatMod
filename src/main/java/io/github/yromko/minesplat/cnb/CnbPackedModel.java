package io.github.yromko.minesplat.cnb;

import java.util.List;

public record CnbPackedModel(
        int bitsPerBlockSide,
        int width,
        int height,
        int depth,
        List<HostBlock> hostBlocks,
        int bitCount
) {
    public CnbPackedModel {
        hostBlocks = List.copyOf(hostBlocks);
    }

    public record HostBlock(
            int x,
            int y,
            int z,
            int[] localIndices,
            int[] paletteIndices
    ) {
        public HostBlock {
            localIndices = localIndices.clone();
            paletteIndices = paletteIndices.clone();
            if (localIndices.length != paletteIndices.length) {
                throw new IllegalArgumentException("Host block bit arrays have different lengths");
            }
        }

        @Override
        public int[] localIndices() {
            return localIndices.clone();
        }

        @Override
        public int[] paletteIndices() {
            return paletteIndices.clone();
        }

        public int bitCount() {
            return localIndices.length;
        }
    }
}
