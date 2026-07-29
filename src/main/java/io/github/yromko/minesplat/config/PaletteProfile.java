package io.github.yromko.minesplat.config;

public enum PaletteProfile {
    SURVIVAL("survival"),
    MAXIMUM_COLOR("maximum_color");

    private final String id;

    PaletteProfile(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public PaletteProfile next() {
        PaletteProfile[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static PaletteProfile fromId(String id) {
        for (PaletteProfile value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return SURVIVAL;
    }
}
