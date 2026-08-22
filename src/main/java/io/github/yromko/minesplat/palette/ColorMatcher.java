package io.github.yromko.minesplat.palette;

import io.github.yromko.minesplat.voxel.TsvoxGrid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ColorMatcher {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int ALL_FACES = (1 << DIRECTIONS.length) - 1;

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
        CandidateScores[] scoreCache = new CandidateScores[ALL_FACES + 1];

        for (int record = 0; record < count; record++) {
            int linear = grid.linearIndexAt(record);
            int x = grid.xOf(linear);
            int y = grid.yOf(linear);
            int z = grid.zOf(linear);
            double[] target = Oklab.fromLinearBytes(
                    grid.colorChannelAt(record, 0),
                    grid.colorChannelAt(record, 1),
                    grid.colorChannelAt(record, 2));
            int exposedFaces = 0;
            for (int directionIndex = 0;
                    directionIndex < DIRECTIONS.length;
                    directionIndex++) {
                Direction direction = DIRECTIONS[directionIndex];
                if (!grid.isOccupied(
                        x + direction.dx(),
                        y + direction.dy(),
                        z + direction.dz())) {
                    exposedFaces |= 1 << directionIndex;
                }
            }
            if (exposedFaces == 0) {
                exposedFaces = ALL_FACES;
            }
            CandidateScores scores = scoreCache[exposedFaces];
            if (scores == null) {
                scores = CandidateScores.create(candidates, exposedFaces);
                scoreCache[exposedFaces] = scores;
            }
            double targetSquared = target[0] * target[0]
                    + target[1] * target[1]
                    + target[2] * target[2];

            PaletteEntry best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int candidateIndex = 0;
                    candidateIndex < candidates.size();
                    candidateIndex++) {
                double score = targetSquared
                        - 2.0 * (
                                target[0] * scores.meanL()[candidateIndex]
                                        + target[1] * scores.meanA()[candidateIndex]
                                        + target[2] * scores.meanB()[candidateIndex])
                        + scores.meanSquared()[candidateIndex];
                if (score < bestScore - 1e-12) {
                    bestScore = score;
                    best = candidates.get(candidateIndex);
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

    private record CandidateScores(
            double[] meanL,
            double[] meanA,
            double[] meanB,
            double[] meanSquared
    ) {
        private static CandidateScores create(List<PaletteEntry> candidates, int faceMask) {
            int exposed = Integer.bitCount(faceMask);
            double[] meanL = new double[candidates.size()];
            double[] meanA = new double[candidates.size()];
            double[] meanB = new double[candidates.size()];
            double[] meanSquared = new double[candidates.size()];
            for (int candidateIndex = 0;
                    candidateIndex < candidates.size();
                    candidateIndex++) {
                PaletteEntry candidate = candidates.get(candidateIndex);
                for (int directionIndex = 0;
                        directionIndex < DIRECTIONS.length;
                        directionIndex++) {
                    if ((faceMask & (1 << directionIndex)) == 0) {
                        continue;
                    }
                    double[] color = candidate.faceColor(DIRECTIONS[directionIndex]);
                    meanL[candidateIndex] += color[0];
                    meanA[candidateIndex] += color[1];
                    meanB[candidateIndex] += color[2];
                    meanSquared[candidateIndex] += color[0] * color[0]
                            + color[1] * color[1]
                            + color[2] * color[2];
                }
                meanL[candidateIndex] /= exposed;
                meanA[candidateIndex] /= exposed;
                meanB[candidateIndex] /= exposed;
                meanSquared[candidateIndex] /= exposed;
            }
            return new CandidateScores(meanL, meanA, meanB, meanSquared);
        }
    }
}
