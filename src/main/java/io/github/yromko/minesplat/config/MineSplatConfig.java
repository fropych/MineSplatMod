package io.github.yromko.minesplat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MineSplatConfig {
    public static final int SCHEMA_VERSION = 5;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private int schemaVersion = SCHEMA_VERSION;
    private String serverUrl = "";
    private String inferenceMode = InferenceMode.REMOTE.id();
    private int localDeviceIndex = 0;
    private String localModelDirectory = "";
    private String generationPreset = GenerationPreset.BASE.id();
    private long seed = 42;
    private String voxelPreset = VoxelPreset.STANDARD.id();
    private String paletteProfile = PaletteProfile.SURVIVAL.id();
    private String customPaletteId = "";
    private String outputMode = OutputMode.LITEMATICA.id();
    private Set<String> blacklistedBlocks = new LinkedHashSet<>();

    public static MineSplatConfig load() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return new MineSplatConfig();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            MineSplatConfig value = GSON.fromJson(reader, MineSplatConfig.class);
            if (value == null) {
                return new MineSplatConfig();
            }
            value.validateAndMigrate();
            return value;
        } catch (IOException | JsonParseException exception) {
            return new MineSplatConfig();
        }
    }

    public synchronized void save() throws IOException {
        validateAndMigrate();
        Path path = path();
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static MineSplatConfig parse(String json) {
        MineSplatConfig value = GSON.fromJson(json, MineSplatConfig.class);
        if (value == null) {
            value = new MineSplatConfig();
        }
        value.validateAndMigrate();
        return value;
    }

    String toJson() {
        validateAndMigrate();
        return GSON.toJson(this);
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("minesplat.json");
    }

    private void validateAndMigrate() {
        schemaVersion = SCHEMA_VERSION;
        serverUrl = serverUrl == null ? "" : serverUrl.trim();
        inferenceMode = InferenceMode.fromId(inferenceMode).id();
        if (localDeviceIndex < 0) {
            localDeviceIndex = 0;
        }
        localModelDirectory = localModelDirectory == null
                ? "" : localModelDirectory.trim();
        generationPreset = GenerationPreset.fromId(generationPreset).id();
        if (seed < 0) {
            seed = 42;
        }
        voxelPreset = VoxelPreset.fromId(voxelPreset).id();
        paletteProfile = PaletteProfile.fromId(paletteProfile).id();
        customPaletteId = validCustomPaletteId(customPaletteId);
        outputMode = OutputMode.fromId(outputMode).id();
        if (blacklistedBlocks == null) {
            blacklistedBlocks = new LinkedHashSet<>();
        } else {
            blacklistedBlocks.removeIf(value -> value == null || value.isBlank());
        }
    }

    public String serverUrl() {
        return serverUrl;
    }

    public void serverUrl(String value) {
        serverUrl = value == null ? "" : value.trim();
    }

    public InferenceMode inferenceMode() {
        return InferenceMode.fromId(inferenceMode);
    }

    public void inferenceMode(InferenceMode value) {
        inferenceMode = value == null ? InferenceMode.REMOTE.id() : value.id();
    }

    public int localDeviceIndex() {
        return localDeviceIndex;
    }

    public void localDeviceIndex(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("local device index must be non-negative");
        }
        localDeviceIndex = value;
    }

    public String localModelDirectory() {
        return localModelDirectory;
    }

    public void localModelDirectory(String value) {
        localModelDirectory = value == null ? "" : value.trim();
    }

    public GenerationPreset generationPreset() {
        return GenerationPreset.fromId(generationPreset);
    }

    public void generationPreset(GenerationPreset value) {
        generationPreset = value == null ? GenerationPreset.BASE.id() : value.id();
    }

    public long seed() {
        return seed;
    }

    public void seed(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("seed must be non-negative");
        }
        seed = value;
    }

    public VoxelPreset voxelPreset() {
        return VoxelPreset.fromId(voxelPreset);
    }

    public void voxelPreset(VoxelPreset value) {
        voxelPreset = value.id();
    }

    public PaletteProfile paletteProfile() {
        return PaletteProfile.fromId(paletteProfile);
    }

    public void paletteProfile(PaletteProfile value) {
        paletteProfile = value.id();
        customPaletteId = "";
    }

    public String customPaletteId() {
        return customPaletteId;
    }

    public void customPaletteId(String value) {
        customPaletteId = validCustomPaletteId(value);
    }

    public void selectCustomPalette(String id, PaletteProfile baseCategory) {
        paletteProfile = (baseCategory == null ? PaletteProfile.SURVIVAL : baseCategory).id();
        customPaletteId = validCustomPaletteId(id);
    }

    public OutputMode outputMode() {
        return OutputMode.fromId(outputMode);
    }

    public void outputMode(OutputMode value) {
        outputMode = value == null ? OutputMode.LITEMATICA.id() : value.id();
    }

    public Set<String> blacklistedBlocks() {
        return new LinkedHashSet<>(blacklistedBlocks);
    }

    public void blacklistedBlocks(Set<String> values) {
        blacklistedBlocks = new LinkedHashSet<>(values);
    }

    private static String validCustomPaletteId(String value) {
        String id = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        return id.matches("[a-z0-9][a-z0-9_-]{0,63}") ? id : "";
    }
}
