package io.github.yromko.minesplat.voxel;

import java.util.Arrays;

public final class TsvoxGrid {
    private final int resolution;
    private final byte[] occupancy;
    private final int[] linearIndices;
    private final byte[] linearRgb;
    private final float[] origin;
    private final float voxelSize;
    private final Bounds bounds;

    TsvoxGrid(
            int resolution,
            byte[] occupancy,
            int[] linearIndices,
            byte[] linearRgb,
            float[] origin,
            float voxelSize
    ) {
        this.resolution = resolution;
        this.occupancy = occupancy;
        this.linearIndices = linearIndices;
        this.linearRgb = linearRgb;
        this.origin = origin;
        this.voxelSize = voxelSize;
        this.bounds = calculateBounds();
    }

    public int resolution() {
        return resolution;
    }

    public int occupiedCount() {
        return linearIndices.length;
    }

    public int linearIndexAt(int recordIndex) {
        return linearIndices[recordIndex];
    }

    public int colorChannelAt(int recordIndex, int channel) {
        return Byte.toUnsignedInt(linearRgb[recordIndex * 3 + channel]);
    }

    public int xOf(int linearIndex) {
        return linearIndex % resolution;
    }

    public int yOf(int linearIndex) {
        return (linearIndex / resolution) % resolution;
    }

    public int zOf(int linearIndex) {
        return linearIndex / (resolution * resolution);
    }

    public boolean isOccupied(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= resolution || y >= resolution || z >= resolution) {
            return false;
        }
        int linearIndex = x + resolution * (y + resolution * z);
        return (occupancy[linearIndex >>> 3] & (1 << (linearIndex & 7))) != 0;
    }

    public float[] origin() {
        return Arrays.copyOf(origin, origin.length);
    }

    public float voxelSize() {
        return voxelSize;
    }

    public Bounds bounds() {
        return bounds;
    }

    private Bounds calculateBounds() {
        if (linearIndices.length == 0) {
            return new Bounds(0, 0, 0, 0, 0, 0);
        }
        int minX = resolution;
        int minY = resolution;
        int minZ = resolution;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int linearIndex : linearIndices) {
            int x = xOf(linearIndex);
            int y = yOf(linearIndex);
            int z = zOf(linearIndex);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int width() {
            return maxX < minX ? 0 : maxX - minX + 1;
        }

        public int height() {
            return maxY < minY ? 0 : maxY - minY + 1;
        }

        public int depth() {
            return maxZ < minZ ? 0 : maxZ - minZ + 1;
        }
    }
}
