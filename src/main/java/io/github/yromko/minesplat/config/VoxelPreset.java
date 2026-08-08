package io.github.yromko.minesplat.config;

public enum VoxelPreset {
    PREVIEW("preview", 32),
    STANDARD("standard", 64),
    DETAILED("detailed", 128),
    ULTRA("ultra", 256),
    MAXIMUM("maximum", 512),
    EXTREME("extreme", 1024);

    private final String id;
    private final int resolution;

    VoxelPreset(String id, int resolution) {
        this.id = id;
        this.resolution = resolution;
    }

    public String id() {
        return id;
    }

    public int resolution() {
        return resolution;
    }

    public VoxelPreset next() {
        VoxelPreset[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static VoxelPreset fromId(String id) {
        for (VoxelPreset value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return STANDARD;
    }
}
