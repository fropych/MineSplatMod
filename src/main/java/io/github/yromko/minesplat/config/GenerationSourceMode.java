package io.github.yromko.minesplat.config;

public enum GenerationSourceMode {
    IMAGE("image"),
    PROMPT("prompt");

    private final String id;

    GenerationSourceMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
