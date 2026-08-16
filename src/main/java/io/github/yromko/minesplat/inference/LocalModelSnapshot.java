package io.github.yromko.minesplat.inference;

import java.nio.file.Path;

public record LocalModelSnapshot(
        LocalModelState state,
        Path directory,
        String currentFile,
        long downloadedBytes,
        long totalBytes,
        String error
) {
    public static LocalModelSnapshot checking(Path directory) {
        return new LocalModelSnapshot(
                LocalModelState.CHECKING, directory, null, 0, 0, null);
    }

    public double progress() {
        return totalBytes <= 0 ? 0.0 : Math.min(1.0, (double) downloadedBytes / totalBytes);
    }
}
