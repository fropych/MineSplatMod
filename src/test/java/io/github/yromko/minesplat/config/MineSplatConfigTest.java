package io.github.yromko.minesplat.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MineSplatConfigTest {
    @Test
    void migratesAndValidatesSchemaOne() {
        MineSplatConfig config = MineSplatConfig.parse("""
                {
                  "schemaVersion": 0,
                  "serverUrl": "  http://example.test:8080/  ",
                  "seed": -2,
                  "voxelPreset": "future",
                  "paletteProfile": "unknown",
                  "blacklistedBlocks": ["minecraft:stone", "", null]
                }
                """);

        assertEquals("http://example.test:8080/", config.serverUrl());
        assertEquals(42, config.seed());
        assertEquals(VoxelPreset.STANDARD, config.voxelPreset());
        assertEquals(PaletteProfile.SURVIVAL, config.paletteProfile());
        assertEquals(OutputMode.LITEMATICA, config.outputMode());
        assertEquals(InferenceMode.REMOTE, config.inferenceMode());
        assertEquals(GenerationPreset.BASE, config.generationPreset());
        assertEquals(0, config.localDeviceIndex());
        assertEquals("", config.localModelDirectory());
        assertEquals(Set.of("minecraft:stone"), config.blacklistedBlocks());

        JsonObject json = JsonParser.parseString(config.toJson()).getAsJsonObject();
        assertEquals(4, json.get("schemaVersion").getAsInt());
        assertEquals("base", json.get("generationPreset").getAsString());
        assertEquals("litematica", json.get("outputMode").getAsString());
        assertEquals("remote", json.get("inferenceMode").getAsString());
    }

    @Test
    void preservesLocalInferenceSettings() {
        MineSplatConfig config = MineSplatConfig.parse("""
                {
                  "schemaVersion": 3,
                  "inferenceMode": "local",
                  "localDeviceIndex": 2,
                  "localModelDirectory": "  /models/triposplat  ",
                  "generationPreset": "high"
                }
                """);

        assertEquals(InferenceMode.LOCAL, config.inferenceMode());
        assertEquals(2, config.localDeviceIndex());
        assertEquals("/models/triposplat", config.localModelDirectory());
        assertEquals(GenerationPreset.HIGH, config.generationPreset());
    }
}
