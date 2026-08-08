package io.github.yromko.minesplat.cnb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CnbPacking {
    private CnbPacking() {
    }

    public static CnbPackedModel pack(
            CnbBlueprint blueprint,
            int bitsPerBlockSide,
            int quarterTurns
    ) {
        if (bitsPerBlockSide != 16 && bitsPerBlockSide != 8
                && bitsPerBlockSide != 4 && bitsPerBlockSide != 2
                && bitsPerBlockSide != 1) {
            throw new IllegalArgumentException(
                    "Unsupported Chisels & Bits grid size " + bitsPerBlockSide);
        }
        int turns = Math.floorMod(quarterTurns, 4);
        int rotatedWidth = turns % 2 == 0 ? blueprint.width() : blueprint.depth();
        int rotatedDepth = turns % 2 == 0 ? blueprint.depth() : blueprint.width();
        Map<Long, HostBuilder> groups = new LinkedHashMap<>();
        for (int index = 0; index < blueprint.voxelCount(); index++) {
            int sourceX = blueprint.x(index);
            int sourceZ = blueprint.z(index);
            int x = rotateX(sourceX, sourceZ, blueprint.width(), blueprint.depth(), turns);
            int z = rotateZ(sourceX, sourceZ, blueprint.width(), blueprint.depth(), turns);
            int y = blueprint.y(index);
            int hostX = x / bitsPerBlockSide;
            int hostY = y / bitsPerBlockSide;
            int hostZ = z / bitsPerBlockSide;
            int localX = x % bitsPerBlockSide;
            int localY = y % bitsPerBlockSide;
            int localZ = z % bitsPerBlockSide;
            int local = localX + bitsPerBlockSide
                    * (localY + bitsPerBlockSide * localZ);
            long key = hostKey(hostX, hostY, hostZ);
            groups.computeIfAbsent(
                    key, unused -> new HostBuilder(hostX, hostY, hostZ))
                    .add(local, blueprint.paletteIndexAt(index));
        }
        List<CnbPackedModel.HostBlock> hosts = groups.values().stream()
                .map(HostBuilder::build)
                .sorted(Comparator.comparingInt(CnbPackedModel.HostBlock::y)
                        .thenComparingInt(CnbPackedModel.HostBlock::z)
                        .thenComparingInt(CnbPackedModel.HostBlock::x))
                .toList();
        return new CnbPackedModel(
                bitsPerBlockSide,
                ceilDiv(rotatedWidth, bitsPerBlockSide),
                ceilDiv(blueprint.height(), bitsPerBlockSide),
                ceilDiv(rotatedDepth, bitsPerBlockSide),
                hosts,
                blueprint.voxelCount());
    }

    public static int rotateX(
            int x,
            int z,
            int width,
            int depth,
            int quarterTurns
    ) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> depth - 1 - z;
            case 2 -> width - 1 - x;
            case 3 -> z;
            default -> x;
        };
    }

    public static int rotateZ(
            int x,
            int z,
            int width,
            int depth,
            int quarterTurns
    ) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> x;
            case 2 -> depth - 1 - z;
            case 3 -> width - 1 - x;
            default -> z;
        };
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static long hostKey(int x, int y, int z) {
        return ((long) x << 42) ^ ((long) y << 21) ^ z;
    }

    private static final class HostBuilder {
        private final int x;
        private final int y;
        private final int z;
        private final List<Integer> localIndices = new ArrayList<>();
        private final List<Integer> paletteIndices = new ArrayList<>();

        private HostBuilder(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private void add(int localIndex, int paletteIndex) {
            localIndices.add(localIndex);
            paletteIndices.add(paletteIndex);
        }

        private CnbPackedModel.HostBlock build() {
            int[] local = new int[localIndices.size()];
            int[] palette = new int[paletteIndices.size()];
            for (int index = 0; index < local.length; index++) {
                local[index] = localIndices.get(index);
                palette[index] = paletteIndices.get(index);
            }
            return new CnbPackedModel.HostBlock(x, y, z, local, palette);
        }
    }
}
