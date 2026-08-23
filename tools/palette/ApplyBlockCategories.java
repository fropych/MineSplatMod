import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies the reusable block-category map to a version-specific color palette. */
public final class ApplyBlockCategories {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){2}");
    private static final Pattern BLOCK_ID = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+");

    private ApplyBlockCategories() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            System.err.println(
                    "Usage: java ... tools/palette/ApplyBlockCategories.java "
                            + "<minecraft-version> <generated-palette.json> "
                            + "<block-categories.json> <output.json>");
            System.exit(2);
        }

        String minecraftVersion = arguments[0];
        if (!VERSION.matcher(minecraftVersion).matches()) {
            throw new IllegalArgumentException(
                    "Invalid target Minecraft version: " + minecraftVersion);
        }
        Path palettePath = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path categoriesPath = Path.of(arguments[2]).toAbsolutePath().normalize();
        Path outputPath = Path.of(arguments[3]).toAbsolutePath().normalize();
        JsonObject palette = readObject(palettePath);
        JsonObject categoryMap = readObject(categoriesPath);

        JsonObject definitions = requiredObject(categoryMap, "categoryDefinitions");
        JsonObject blockCategories = requiredObject(categoryMap, "blockCategories");
        JsonArray entries = requiredArray(palette, "entries");
        if (!categoryMap.has("schemaVersion")
                || categoryMap.get("schemaVersion").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported category-map schema");
        }
        if (!palette.has("schemaVersion") || palette.get("schemaVersion").getAsInt() != 1
                || !palette.has("minecraftVersion")
                || !minecraftVersion.equals(palette.get("minecraftVersion").getAsString())) {
            throw new IllegalArgumentException(
                    "Generated palette does not target Minecraft " + minecraftVersion);
        }

        Map<String, JsonArray> categoriesByBlock = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> mapping : blockCategories.entrySet()) {
            String block = mapping.getKey();
            if (!BLOCK_ID.matcher(block).matches()) {
                throw new IllegalArgumentException("Invalid mapped block ID " + block);
            }
            categoriesByBlock.put(
                    block, validatedCategories(block, mapping.getValue(), definitions));
        }

        Set<String> paletteBlocks = new LinkedHashSet<>();
        Map<String, Integer> stateCounts = new LinkedHashMap<>();
        definitions.keySet().forEach(category -> stateCounts.put(category, 0));
        for (JsonElement value : entries) {
            JsonObject entry = value.getAsJsonObject();
            String block = entry.get("block").getAsString();
            paletteBlocks.add(block);
            JsonArray profiles = categoriesByBlock.get(block);
            if (profiles == null) {
                throw new IllegalArgumentException("No categories mapped for " + block);
            }
            for (JsonElement profile : profiles) {
                String category = profile.getAsString();
                stateCounts.compute(category, (ignored, count) -> count + 1);
            }
            entry.add("profiles", profiles.deepCopy());
            entry.remove("faceOpacity");
        }

        Set<String> mappedBlocks = categoriesByBlock.keySet();
        Set<String> missingFromMap = new LinkedHashSet<>(paletteBlocks);
        missingFromMap.removeAll(mappedBlocks);
        if (!missingFromMap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Category map does not cover the target palette: " + missingFromMap);
        }

        palette.addProperty("categoryMap", "block-categories.json");
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (var writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            GSON.toJson(palette, writer);
        }

        System.out.println("Unique blocks: " + paletteBlocks.size());
        System.out.println("States: " + entries.size());
        stateCounts.forEach((category, count) ->
                System.out.println(category + " states: " + count));
        System.out.println("Palette written to: " + outputPath);
    }

    private static JsonArray validatedCategories(
            String block,
            JsonElement mapped,
            JsonObject definitions
    ) {
        if (mapped == null || !mapped.isJsonArray()) {
            throw new IllegalArgumentException("No categories mapped for " + block);
        }
        JsonArray profiles = mapped.getAsJsonArray();
        if (profiles.isEmpty() || !"all".equals(profiles.get(0).getAsString())) {
            throw new IllegalArgumentException(block + " must belong to all");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement profile : profiles) {
            if (!profile.isJsonPrimitive()) {
                throw new IllegalArgumentException("Invalid category for " + block);
            }
            String category = profile.getAsString();
            if (!definitions.has(category)) {
                throw new IllegalArgumentException(
                        "Unknown category " + category + " for " + block);
            }
            if (!seen.add(category)) {
                throw new IllegalArgumentException(
                        "Duplicate category " + category + " for " + block);
            }
        }
        return profiles;
    }

    private static JsonObject readObject(Path input) throws Exception {
        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            JsonElement value = JsonParser.parseReader(reader);
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Expected JSON object in " + input);
            }
            return value.getAsJsonObject();
        }
    }

    private static JsonObject requiredObject(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            throw new IllegalArgumentException("Missing JSON object " + key);
        }
        return parent.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonArray()) {
            throw new IllegalArgumentException("Missing JSON array " + key);
        }
        return parent.getAsJsonArray(key);
    }
}
