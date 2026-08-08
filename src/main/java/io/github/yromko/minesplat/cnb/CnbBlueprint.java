package io.github.yromko.minesplat.cnb;

import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.palette.Direction;
import io.github.yromko.minesplat.palette.MatchedVoxels;
import io.github.yromko.minesplat.palette.PaletteEntry;
import io.github.yromko.minesplat.util.FileNames;
import io.github.yromko.minesplat.voxel.VoxelResolutions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CnbBlueprint {
    public static final int SCHEMA_VERSION = 1;
    private static final long PALETTE_MASK = 0xffffL;

    private final String name;
    private final String author;
    private final long createdAt;
    private final int dataVersion;
    private final int resolution;
    private final int width;
    private final int height;
    private final int depth;
    private final PaletteProfile paletteProfile;
    private final List<CnbPaletteEntry> palette;
    private final long[] voxels;

    public CnbBlueprint(
            String name,
            String author,
            long createdAt,
            int dataVersion,
            int resolution,
            int width,
            int height,
            int depth,
            PaletteProfile paletteProfile,
            List<CnbPaletteEntry> palette,
            long[] voxels
    ) {
        this.name = FileNames.sanitize(name);
        this.author = author == null ? "" : author;
        this.createdAt = createdAt;
        this.dataVersion = dataVersion;
        this.resolution = resolution;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.paletteProfile = paletteProfile;
        this.palette = List.copyOf(palette);
        this.voxels = voxels.clone();
        validate();
    }

    public static CnbBlueprint fromMatched(
            String name,
            String author,
            long createdAt,
            int dataVersion,
            int resolution,
            PaletteProfile profile,
            MatchedVoxels matched
    ) {
        Map<String, Integer> paletteIndices = new LinkedHashMap<>();
        List<CnbPaletteEntry> palette = new ArrayList<>();
        long[] voxels = new long[matched.size()];
        for (int index = 0; index < matched.size(); index++) {
            PaletteEntry entry = matched.block(index);
            int paletteIndex = paletteIndices.computeIfAbsent(entry.stateString(), state -> {
                int[] colors = new int[Direction.values().length * 3];
                for (Direction direction : Direction.values()) {
                    int[] rgb = entry.faceRgb(direction);
                    int offset = direction.ordinal() * 3;
                    System.arraycopy(rgb, 0, colors, offset, 3);
                }
                palette.add(new CnbPaletteEntry(state, colors));
                return palette.size() - 1;
            });
            long linear = matched.x(index)
                    + (long) matched.width()
                    * (matched.y(index) + (long) matched.height() * matched.z(index));
            voxels[index] = pack(linear, paletteIndex);
        }
        Arrays.sort(voxels);
        return new CnbBlueprint(
                name, author, createdAt, dataVersion, resolution,
                matched.width(), matched.height(), matched.depth(),
                profile, palette, voxels);
    }

    private void validate() {
        if (!VoxelResolutions.isSupportedByServer(resolution)) {
            throw new IllegalArgumentException("Unsupported blueprint resolution " + resolution);
        }
        if (width < 1 || height < 1 || depth < 1
                || width > resolution || height > resolution || depth > resolution) {
            throw new IllegalArgumentException("Blueprint dimensions are out of range");
        }
        if (paletteProfile == null || palette.isEmpty() || palette.size() > 0xffff) {
            throw new IllegalArgumentException("Blueprint palette is invalid");
        }
        long volume = Math.multiplyExact(Math.multiplyExact((long) width, height), depth);
        if (voxels.length < 1 || voxels.length > volume) {
            throw new IllegalArgumentException("Blueprint voxel count is invalid");
        }
        long previousLinear = -1;
        for (long packed : voxels) {
            long linear = linearIndex(packed);
            int paletteIndex = paletteIndex(packed);
            if (linear < 0 || linear >= volume) {
                throw new IllegalArgumentException("Blueprint voxel coordinate is out of range");
            }
            if (linear <= previousLinear) {
                throw new IllegalArgumentException(
                        "Blueprint voxel coordinates are duplicated or unsorted");
            }
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                throw new IllegalArgumentException("Blueprint palette index is out of range");
            }
            previousLinear = linear;
        }
    }

    public static long pack(long linearIndex, int paletteIndex) {
        if (linearIndex < 0 || paletteIndex < 0 || paletteIndex > PALETTE_MASK) {
            throw new IllegalArgumentException("Cannot pack blueprint voxel");
        }
        return (linearIndex << 16) | paletteIndex;
    }

    public static long linearIndex(long packed) {
        return packed >>> 16;
    }

    public static int paletteIndex(long packed) {
        return (int) (packed & PALETTE_MASK);
    }

    public int x(int voxelIndex) {
        return (int) (linearIndex(voxels[voxelIndex]) % width);
    }

    public int y(int voxelIndex) {
        return (int) ((linearIndex(voxels[voxelIndex]) / width) % height);
    }

    public int z(int voxelIndex) {
        return (int) (linearIndex(voxels[voxelIndex]) / ((long) width * height));
    }

    public int paletteIndexAt(int voxelIndex) {
        return paletteIndex(voxels[voxelIndex]);
    }

    public String name() {
        return name;
    }

    public String author() {
        return author;
    }

    public long createdAt() {
        return createdAt;
    }

    public int dataVersion() {
        return dataVersion;
    }

    public int resolution() {
        return resolution;
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

    public PaletteProfile paletteProfile() {
        return paletteProfile;
    }

    public List<CnbPaletteEntry> palette() {
        return palette;
    }

    public long[] voxels() {
        return voxels.clone();
    }

    public int voxelCount() {
        return voxels.length;
    }
}
