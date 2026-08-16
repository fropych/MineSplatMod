package io.github.yromko.minesplat.inference;

public enum LocalModelSet {
    CORE("core"),
    TEXT("text");

    private final String id;

    LocalModelSet(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    static LocalModelSet fromId(String value) {
        for (LocalModelSet set : values()) {
            if (set.id.equals(value)) {
                return set;
            }
        }
        throw new IllegalArgumentException("Unknown local model set: " + value);
    }
}
