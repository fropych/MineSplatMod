package io.github.yromko.minesplat.palette;

import io.github.yromko.minesplat.voxel.TsvoxGrid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ColorMatcher {
    public MatchedVoxels match(TsvoxGrid grid, List<PaletteEntry> candidates) {
        if (grid.occupiedCount() == 0) {
            throw new IllegalArgumentException("TSVOX grid is empty");
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No blocks remain in the selected palette");
        }

        int count = grid.occupiedCount();
        int[] outputX = new int[count];
        int[] outputY = new int[count];
        int[] outputZ = new int[count];
        PaletteEntry[] outputBlocks = new PaletteEntry[count];
        Map<String, Integer> materialCounts = new LinkedHashMap<>();
        TsvoxGrid.Bounds bounds = grid.bounds();

        for (int record = 0; record < count; record++) {
            int linear = grid.linearIndexAt(record);
            int x = grid.xOf(linear);
            int y = grid.yOf(linear);
            int z = grid.zOf(linear);
            double[] target = Oklab.fromLinearBytes(
                    grid.colorChannelAt(record, 0),
                    grid.colorChannelAt(record, 1),
                    grid.colorChannelAt(record, 2));

            PaletteEntry best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (PaletteEntry candidate : candidates) {
                double score = 0;
                int exposed = 0;
                for (Direction direction : Direction.values()) {
                    if (!grid.isOccupied(
                            x + direction.dx(),
                            y + direction.dy(),
                            z + direction.dz())) {
                        score += Oklab.squaredDistance(target, candidate.faceColor(direction));
                        exposed++;
                    }
                }
                if (exposed == 0) {
                    for (Direction direction : Direction.values()) {
                        score += Oklab.squaredDistance(target, candidate.faceColor(direction));
                    }
                    exposed = Direction.values().length;
                }
                score /= exposed;
                if (score < bestScore - 1e-12) {
                    bestScore = score;
                    best = candidate;
                }
            }

            outputX[record] = x - bounds.minX();
            outputY[record] = bounds.maxY() - y;
            outputZ[record] = z - bounds.minZ();
            outputBlocks[record] = best;
            materialCounts.merge(best.blockId(), 1, Integer::sum);
        }

        return new MatchedVoxels(
                bounds.width(), bounds.height(), bounds.depth(),
                outputX, outputY, outputZ, outputBlocks, materialCounts);
    }
}
