package io.github.yromko.minesplat.config;

public enum InferenceMode {
    REMOTE("remote"),
    LOCAL("local");

    private final String id;

    InferenceMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static InferenceMode fromId(String id) {
        for (InferenceMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return REMOTE;
    }
}
