package io.github.yromko.minesplat.palette;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MatchedVoxels {
    private final int width;
    private final int height;
    private final int depth;
    private final int[] x;
    private final int[] y;
    private final int[] z;
    private final PaletteEntry[] blocks;
    private final Map<String, Integer> materialCounts;

    MatchedVoxels(
            int width,
            int height,
            int depth,
            int[] x,
            int[] y,
            int[] z,
            PaletteEntry[] blocks,
            Map<String, Integer> materialCounts
    ) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blocks = blocks;
        this.materialCounts = Collections.unmodifiableMap(new LinkedHashMap<>(materialCounts));
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    public int size() {
        return blocks.length;
    }

    public int x(int index) {
        return x[index];
    }

    public int y(int index) {
        return y[index];
    }

    public int z(int index) {
        return z[index];
    }

    public PaletteEntry block(int index) {
        return blocks[index];
    }

    public Map<String, Integer> materialCounts() {
        return materialCounts;
    }
}
