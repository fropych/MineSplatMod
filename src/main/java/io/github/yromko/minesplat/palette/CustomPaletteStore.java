package io.github.yromko.minesplat.palette;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class CustomPaletteStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path directory;

    public CustomPaletteStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static CustomPaletteStore gameStore() {
        return new CustomPaletteStore(
                FabricLoader.getInstance().getConfigDir().resolve("minesplat/palettes"));
    }

    public synchronized List<CustomPalette> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<CustomPalette> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> read(path).ifPresent(result::add));
        } catch (IOException ignored) {
            return List.of();
        }
        result.sort(Comparator
                .comparing(CustomPalette::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CustomPalette::id));
        return List.copyOf(result);
    }

    public synchronized Optional<CustomPalette> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String validated;
        try {
            validated = CustomPalette.validateId(id);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return read(directory.resolve(validated + ".json"));
    }

    public synchronized void save(CustomPalette palette) throws IOException {
        Files.createDirectories(directory);
        Path destination = directory.resolve(palette.id() + ".json");
        Path temporary = directory.resolve(palette.id() + ".json.tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(palette.toJson(), writer);
        }
        try {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized CustomPalette duplicate(CustomPalette source, String name)
            throws IOException {
        CustomPalette copy = new CustomPalette(
                java.util.UUID.randomUUID().toString(),
                name,
                source.baseCategory(),
                source.includedBlocks(),
                source.excludedBlocks());
        save(copy);
        return copy;
    }

    public synchronized boolean delete(String id) throws IOException {
        String validated = CustomPalette.validateId(id);
        return Files.deleteIfExists(directory.resolve(validated + ".json"));
    }

    public Path directory() {
        return directory;
    }

    private Optional<CustomPalette> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject object = GSON.fromJson(reader, JsonObject.class);
            CustomPalette palette = CustomPalette.parse(object);
            String expectedId = path.getFileName().toString();
            expectedId = expectedId.substring(0, expectedId.length() - ".json".length());
            if (!expectedId.equals(palette.id())) {
                return Optional.empty();
            }
            return Optional.of(palette);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
