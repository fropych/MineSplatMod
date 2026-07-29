package io.github.yromko.minesplat.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNamesTest {
    @TempDir
    Path directory;

    @Test
    void sanitizesTraversalAndLimitsLength() {
        assertEquals("_evil_name", FileNames.sanitize("../evil/name"));
        assertEquals("minesplat", FileNames.sanitize("..."));
        assertTrue(FileNames.sanitize("x".repeat(100)).length() <= 64);
    }

    @Test
    void neverOverwritesAndAddsSuffix() throws Exception {
        Path first = FileNames.uniqueSchematicPath(directory, "cat", 64);
        Files.createFile(first);
        Path second = FileNames.uniqueSchematicPath(directory, "cat", 64);
        Files.createFile(second);
        Path third = FileNames.uniqueSchematicPath(directory, "cat", 64);

        assertEquals("cat-r64.litematic", first.getFileName().toString());
        assertEquals("cat-r64-2.litematic", second.getFileName().toString());
        assertEquals("cat-r64-3.litematic", third.getFileName().toString());
    }
}
