package io.github.yromko.minesplat.internal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TargetMetadataTest {
    @Test
    void readsTheExactGeneratedContract() throws Exception {
        TargetMetadata metadata = read("""
                minecraftVersion=1.21.11
                chiselsAndBitsVersion=21.11.45
                """);

        assertEquals("1.21.11", metadata.minecraftVersion());
        assertEquals("21.11.45", metadata.chiselsAndBitsVersion());
    }

    @Test
    void rejectsMissingAndUnexpectedKeys() {
        assertThrows(IllegalStateException.class, () -> read("""
                minecraftVersion=1.21.1
                """));
        assertThrows(IllegalStateException.class, () -> read("""
                minecraftVersion=1.21.1
                chiselsAndBitsVersion=21.1.33
                legacy=true
                """));
    }

    @Test
    void rejectsMalformedOrPaddedVersions() {
        assertThrows(IllegalStateException.class, () -> read("""
                minecraftVersion=1.21
                chiselsAndBitsVersion=21.1.33
                """));
        assertThrows(IllegalStateException.class, () -> read(
                "minecraftVersion=1.21.1\nchiselsAndBitsVersion=21.1.33 \n"));
    }

    private static TargetMetadata read(String value) throws Exception {
        return TargetMetadata.read(new ByteArrayInputStream(
                value.getBytes(StandardCharsets.UTF_8)));
    }
}
