package io.github.yromko.minesplat.config;

public enum OutputMode {
    LITEMATICA("litematica"),
    CHISELS_AND_BITS("chisels_and_bits");

    private final String id;

    OutputMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static OutputMode fromId(String id) {
        for (OutputMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return LITEMATICA;
    }
}
