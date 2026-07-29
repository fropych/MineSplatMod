package io.github.yromko.minesplat.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileNames {
    private FileNames() {
    }

    public static String sanitize(String input) {
        String value = input == null ? "" : input.strip();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        value = value.replaceAll("\\s+", " ");
        value = value.replaceAll("^\\.+", "");
        if (value.length() > 64) {
            value = value.substring(0, 64).stripTrailing();
        }
        return value.isBlank() ? "minesplat" : value;
    }

    public static Path uniqueSchematicPath(Path directory, String name, int resolution) throws IOException {
        Files.createDirectories(directory);
        String base = sanitize(name) + "-r" + resolution;
        Path candidate = directory.resolve(base + ".litematic");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(base + "-" + suffix + ".litematic");
            suffix++;
        }
        return candidate;
    }
}
