package io.github.yromko.minesplat.config;

public enum GenerationPreset {
    BASE("base", 512, 10),
    HIGH("high", 512, 20),
    XHIGH("xhigh", 1024, 20);

    private final String id;
    private final int imageResolution;
    private final int tripoSplatSteps;

    GenerationPreset(String id, int imageResolution, int tripoSplatSteps) {
        this.id = id;
        this.imageResolution = imageResolution;
        this.tripoSplatSteps = tripoSplatSteps;
    }

    public String id() {
        return id;
    }

    public int imageResolution() {
        return imageResolution;
    }

    public int tripoSplatSteps() {
        return tripoSplatSteps;
    }

    public static GenerationPreset fromId(String id) {
        for (GenerationPreset value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return BASE;
    }
}
