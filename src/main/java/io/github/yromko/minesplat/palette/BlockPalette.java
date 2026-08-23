package io.github.yromko.minesplat.palette;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.internal.TargetMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlockPalette {
    private static final Gson GSON = new Gson();
    private final List<PaletteEntry> entries;

    private BlockPalette(List<PaletteEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static BlockPalette loadDefault() {
        String minecraftVersion = TargetMetadata.current().minecraftVersion();
        String resource = "/assets/minesplat/palette/palette-"
                + minecraftVersion + ".json";
        try (InputStream stream = BlockPalette.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing block palette resource " + resource);
            }
            JsonObject root = GSON.fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            return parse(root, minecraftVersion);
        } catch (IOException | JsonParseException exception) {
            throw new IllegalStateException(
                    "Cannot load MineSplat block palette for Minecraft " + minecraftVersion,
                    exception);
        }
    }

    static BlockPalette parse(JsonObject root, String expectedMinecraftVersion) {
        if (root == null || !root.has("schemaVersion")
                || root.get("schemaVersion").getAsInt() != 1
                || !root.has("minecraftVersion")
                || !root.has("entries") || !root.get("entries").isJsonArray()) {
            throw new JsonParseException("Unsupported palette schema");
        }
        String paletteMinecraftVersion = root.get("minecraftVersion").getAsString();
        if (!expectedMinecraftVersion.equals(paletteMinecraftVersion)) {
            throw new JsonParseException(
                    "Palette targets Minecraft " + paletteMinecraftVersion
                            + " but this build targets " + expectedMinecraftVersion);
        }
        List<PaletteEntry> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("entries")) {
            JsonObject object = element.getAsJsonObject();
            String block = object.get("block").getAsString();
            if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new JsonParseException("Invalid block identifier " + block);
            }
            Map<String, String> properties = new LinkedHashMap<>();
            if (object.has("properties")) {
                for (Map.Entry<String, JsonElement> property
                        : object.getAsJsonObject("properties").entrySet()) {
                    properties.put(property.getKey(), property.getValue().getAsString());
                }
            }
            Set<String> profiles = new LinkedHashSet<>();
            for (JsonElement profile : object.getAsJsonArray("profiles")) {
                profiles.add(profile.getAsString());
            }
            int priority = object.has("priority") ? object.get("priority").getAsInt() : 100;
            JsonObject faces = object.getAsJsonObject("faces");
            int[] all = rgb(faces.getAsJsonArray("all"));
            EnumMap<Direction, double[]> faceColors = new EnumMap<>(Direction.class);
            EnumMap<Direction, int[]> faceRgb = new EnumMap<>(Direction.class);
            for (Direction direction : Direction.values()) {
                String key = direction.name().toLowerCase();
                int[] color = faces.has(key) ? rgb(faces.getAsJsonArray(key)) : all;
                faceColors.put(direction, Oklab.fromSrgbBytes(color[0], color[1], color[2]));
                faceRgb.put(direction, color.clone());
            }
            result.add(new PaletteEntry(
                    block, properties, profiles, priority, faceColors, faceRgb));
        }
        if (result.isEmpty()) {
            throw new JsonParseException("Palette has no entries");
        }
        return new BlockPalette(result);
    }

    public List<PaletteEntry> candidates(PaletteProfile profile, Set<String> blacklist) {
        List<PaletteEntry> result = new ArrayList<>();
        for (PaletteEntry entry : entries) {
            if (entry.supportsProfile(profile.id()) && !blacklist.contains(entry.blockId())) {
                result.add(entry);
            }
        }
        result.sort((left, right) -> {
            int priority = Integer.compare(left.priority(), right.priority());
            return priority != 0 ? priority : left.stateKey().compareTo(right.stateKey());
        });
        return Collections.unmodifiableList(result);
    }

    public Set<String> blockIds() {
        Set<String> result = new LinkedHashSet<>();
        for (PaletteEntry entry : entries) {
            result.add(entry.blockId());
        }
        return Collections.unmodifiableSet(result);
    }

    public Set<String> blockIds(PaletteProfile profile) {
        Set<String> result = new LinkedHashSet<>();
        for (PaletteEntry entry : entries) {
            if (entry.supportsProfile(profile.id())) {
                result.add(entry.blockId());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static int[] rgb(JsonArray array) {
        if (array == null || array.size() != 3) {
            throw new JsonParseException("RGB face color must have three channels");
        }
        int[] result = {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
        for (int channel : result) {
            if (channel < 0 || channel > 255) {
                throw new JsonParseException("RGB face channel is out of range");
            }
        }
        return result;
    }
}
