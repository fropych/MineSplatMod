package io.github.yromko.minesplat.compat.chiselsandbits;

import net.minecraft.util.math.Vec3d;

final class CnbBitCenters {
    private final Vec3d[] centers;

    CnbBitCenters(int side) {
        if (side != 16 && side != 8 && side != 4 && side != 2 && side != 1) {
            throw new IllegalArgumentException("Unsupported Chisels & Bits grid size " + side);
        }
        centers = new Vec3d[side * side * side];
        for (int z = 0; z < side; z++) {
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    int index = x + side * (y + side * z);
                    centers[index] = new Vec3d(
                            (x + 0.5) / side,
                            (y + 0.5) / side,
                            (z + 0.5) / side);
                }
            }
        }
    }

    Vec3d at(int localIndex) {
        return centers[localIndex];
    }

    int size() {
        return centers.length;
    }
}
