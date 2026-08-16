package io.github.yromko.minesplat.client;

import io.github.yromko.minesplat.config.GenerationSourceMode;

import java.nio.file.Path;

public final class MineSplatDraft {
    private Path image;
    private String prompt = "";
    private GenerationSourceMode sourceMode = GenerationSourceMode.IMAGE;
    private String schematicName = "minesplat";

    public Path image() {
        return image;
    }

    public void image(Path value) {
        image = value;
    }

    public String prompt() {
        return prompt;
    }

    public void prompt(String value) {
        prompt = value == null ? "" : value;
    }

    public GenerationSourceMode sourceMode() {
        return sourceMode;
    }

    public void sourceMode(GenerationSourceMode value) {
        sourceMode = value == null ? GenerationSourceMode.IMAGE : value;
    }

    public String schematicName() {
        return schematicName;
    }

    public void schematicName(String value) {
        schematicName = value;
    }
}
