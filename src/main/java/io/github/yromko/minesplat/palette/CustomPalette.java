package io.github.yromko.minesplat.palette;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.github.yromko.minesplat.config.PaletteProfile;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

public record CustomPalette(
        String id,
        String name,
        PaletteProfile baseCategory,
        Set<String> includedBlocks,
        Set<String> excludedBlocks
) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NAME_LENGTH = 48;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern BLOCK_ID = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+");

    public CustomPalette {
        id = validateId(id);
        name = validateName(name);
        if (baseCategory == null) {
            throw new IllegalArgumentException("Base category is required");
        }
        includedBlocks = validatedBlocks(includedBlocks, "includedBlocks");
        excludedBlocks = validatedBlocks(excludedBlocks, "excludedBlocks");
        Set<String> overlap = new LinkedHashSet<>(includedBlocks);
        overlap.retainAll(excludedBlocks);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Blocks cannot be both included and excluded: " + overlap);
        }
    }

    public static CustomPalette fromSelection(
            String id,
            String name,
            PaletteProfile baseCategory,
            Set<String> selectedBlocks,
            BlockPalette palette
    ) {
        Set<String> all = palette.blockIds();
        Set<String> selected = new LinkedHashSet<>(selectedBlocks);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("A custom palette cannot be empty");
        }
        if (!all.containsAll(selected)) {
            Set<String> unknown = new LinkedHashSet<>(selected);
            unknown.removeAll(all);
            throw new IllegalArgumentException("Unknown palette blocks: " + unknown);
        }
        Set<String> base = palette.blockIds(baseCategory);
        Set<String> included = new LinkedHashSet<>(selected);
        included.removeAll(base);
        Set<String> excluded = new LinkedHashSet<>(base);
        excluded.removeAll(selected);
        return new CustomPalette(id, name, baseCategory, included, excluded);
    }

    public static CustomPalette create(
            String name,
            PaletteProfile baseCategory,
            Set<String> selectedBlocks,
            BlockPalette palette
    ) {
        return fromSelection(
                UUID.randomUUID().toString(), name, baseCategory, selectedBlocks, palette);
    }

    public CustomPalette withSelection(
            String newName,
            PaletteProfile newBaseCategory,
            Set<String> selectedBlocks,
            BlockPalette palette
    ) {
        CustomPalette updated = fromSelection(
                id, newName, newBaseCategory, selectedBlocks, palette);
        Set<String> known = palette.blockIds();
        Set<String> included = new LinkedHashSet<>(updated.includedBlocks());
        includedBlocks.stream()
                .filter(block -> !known.contains(block))
                .forEach(included::add);
        Set<String> excluded = new LinkedHashSet<>(updated.excludedBlocks());
        excludedBlocks.stream()
                .filter(block -> !known.contains(block))
                .forEach(excluded::add);
        return new CustomPalette(id, newName, newBaseCategory, included, excluded);
    }

    public Set<String> effectiveBlocks(BlockPalette palette) {
        Set<String> all = palette.blockIds();
        Set<String> result = new LinkedHashSet<>(palette.blockIds(baseCategory));
        result.removeAll(excludedBlocks);
        result.addAll(includedBlocks);
        result.retainAll(all);
        return result;
    }

    public Set<String> combinedBlacklist(BlockPalette palette, Set<String> blacklist) {
        Set<String> result = new LinkedHashSet<>(blacklist);
        Set<String> outsidePalette = new LinkedHashSet<>(palette.blockIds());
        outsidePalette.removeAll(effectiveBlocks(palette));
        result.addAll(outsidePalette);
        return Set.copyOf(result);
    }

    JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", SCHEMA_VERSION);
        result.addProperty("id", id);
        result.addProperty("name", name);
        result.addProperty("baseCategory", baseCategory.id());
        result.add("includedBlocks", array(includedBlocks));
        result.add("excludedBlocks", array(excludedBlocks));
        return result;
    }

    static CustomPalette parse(JsonObject object) {
        if (object == null
                || !object.has("schemaVersion")
                || object.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
            throw new JsonParseException("Unsupported custom palette schema");
        }
        try {
            return new CustomPalette(
                    object.get("id").getAsString(),
                    object.get("name").getAsString(),
                    baseCategory(object.get("baseCategory").getAsString()),
                    blocks(object, "includedBlocks"),
                    blocks(object, "excludedBlocks"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new JsonParseException("Invalid custom palette", exception);
        }
    }

    static String validateId(String value) {
        String id = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid custom palette ID " + value);
        }
        return id;
    }

    static String validateName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Palette name must contain 1-" + MAX_NAME_LENGTH + " printable characters");
        }
        return name;
    }

    private static Set<String> validatedBlocks(Set<String> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            if (value == null || !BLOCK_ID.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid block ID in " + field + ": " + value);
            }
            sorted.add(value);
        }
        return Set.copyOf(sorted);
    }

    private static Set<String> blocks(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new JsonParseException("Missing " + key);
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement value : object.getAsJsonArray(key)) {
            result.add(value.getAsString());
        }
        return result;
    }

    private static PaletteProfile baseCategory(String id) {
        for (PaletteProfile profile : PaletteProfile.values()) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        throw new JsonParseException("Unknown base category " + id);
    }

    private static JsonArray array(Set<String> values) {
        JsonArray result = new JsonArray();
        values.stream().sorted().forEach(result::add);
        return result;
    }
}
