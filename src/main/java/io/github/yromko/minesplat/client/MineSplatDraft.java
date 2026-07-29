package io.github.yromko.minesplat.client;

import java.nio.file.Path;

public final class MineSplatDraft {
    private Path image;
    private String schematicName = "minesplat";

    public Path image() {
        return image;
    }

    public void image(Path value) {
        image = value;
    }

    public String schematicName() {
        return schematicName;
    }

    public void schematicName(String value) {
        schematicName = value;
    }
}
