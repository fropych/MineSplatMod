package io.github.yromko.minesplat.workflow;

import io.github.yromko.minesplat.api.TripoSplatApiClient;

import java.nio.file.Path;

public sealed interface GenerationSource
        permits GenerationSource.Image, GenerationSource.Prompt {
    record Image(Path path) implements GenerationSource {
        public Image {
            if (path == null) {
                throw new IllegalArgumentException("Input image is required");
            }
            path = path.toAbsolutePath().normalize();
        }
    }

    record Prompt(String text) implements GenerationSource {
        public Prompt {
            text = TripoSplatApiClient.validatePrompt(text);
        }
    }
}
