import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds the version-independent block-category map from reviewed plain text. */
public final class BuildBlockCategories {
    private static final List<String> CATEGORY_ORDER = List.of(
            "all", "survival", "solid_colors");
    private static final Set<String> CATEGORIES = Set.copyOf(CATEGORY_ORDER);
    private static final Pattern BLOCK_ID = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+");

    private BuildBlockCategories() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            System.err.println(
                    "Usage: java tools/palette/BuildBlockCategories.java "
                            + "<block-names.txt> <block-categories.txt> <output.json>");
            System.exit(2);
        }

        Path namesPath = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path classificationPath = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path outputPath = Path.of(arguments[2]).toAbsolutePath().normalize();

        List<String> expectedBlocks = readBlockNames(namesPath);
        Map<String, List<String>> classifications = readClassifications(classificationPath);
        validateCoverage(expectedBlocks, classifications);
        writeJson(outputPath, classifications);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String category : CATEGORY_ORDER) {
            counts.put(category, 0);
        }
        classifications.values().forEach(categories -> categories.forEach(category ->
                counts.compute(category, (ignored, count) -> count + 1)));

        System.out.println("Blocks mapped: " + classifications.size());
        counts.forEach((category, count) ->
                System.out.println(category + ": " + count));
        System.out.println("Category map written to: " + outputPath);
    }

    private static List<String> readBlockNames(Path input) throws IOException {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String rawLine : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            String block = rawLine.strip();
            if (block.isEmpty()) {
                throw new IllegalArgumentException("Blank line in " + input);
            }
            validateBlockId(block, input);
            if (!seen.add(block)) {
                throw new IllegalArgumentException("Duplicate block " + block + " in " + input);
            }
            result.add(block);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No block names in " + input);
        }
        List<String> sorted = result.stream().sorted().toList();
        if (!result.equals(sorted)) {
            throw new IllegalArgumentException("Block names must be sorted in " + input);
        }
        return result;
    }

    private static Map<String, List<String>> readClassifications(Path input)
            throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (rawLine.isBlank()) {
                throw new IllegalArgumentException("Blank line in " + input);
            }
            String[] columns = rawLine.split("\\t", -1);
            if (columns.length != 2) {
                throw new IllegalArgumentException(
                        "Expected block<TAB>categories, got: " + rawLine);
            }
            String block = columns[0];
            validateBlockId(block, input);
            if (result.containsKey(block)) {
                throw new IllegalArgumentException("Duplicate classification for " + block);
            }

            List<String> categories = List.of(columns[1].split(",", -1));
            if (categories.isEmpty() || !categories.getFirst().equals("all")) {
                throw new IllegalArgumentException(block + " must belong to all");
            }
            if (new LinkedHashSet<>(categories).size() != categories.size()) {
                throw new IllegalArgumentException("Duplicate category for " + block);
            }
            if (!CATEGORIES.containsAll(categories)) {
                throw new IllegalArgumentException(
                        "Unknown category for " + block + ": " + categories);
            }
            List<String> canonicalOrder = CATEGORY_ORDER.stream()
                    .filter(categories::contains)
                    .toList();
            if (!categories.equals(canonicalOrder)) {
                throw new IllegalArgumentException(
                        "Categories are out of order for " + block + ": " + categories);
            }
            result.put(block, List.copyOf(categories));
        }
        return result;
    }

    private static void validateCoverage(
            List<String> expectedBlocks,
            Map<String, List<String>> classifications
    ) {
        Set<String> expected = new LinkedHashSet<>(expectedBlocks);
        Set<String> actual = classifications.keySet();
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(expected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Classification coverage mismatch; missing=" + missing
                            + ", unexpected=" + unexpected);
        }
        if (!new ArrayList<>(actual).equals(expectedBlocks)) {
            throw new IllegalArgumentException("Classifications must be sorted by block ID");
        }
    }

    private static void validateBlockId(String block, Path input) {
        if (!BLOCK_ID.matcher(block).matches()) {
            throw new IllegalArgumentException("Invalid block ID " + block + " in " + input);
        }
    }

    private static void writeJson(Path output, Map<String, List<String>> classifications)
            throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"categoryDefinitions\": {\n");
        json.append("    \"all\": \"All\",\n");
        json.append("    \"survival\": \"Survival\",\n");
        json.append("    \"solid_colors\": \"Solid Colors\"\n");
        json.append("  },\n");
        json.append("  \"blockCategories\": {\n");
        int index = 0;
        for (Map.Entry<String, List<String>> entry : classifications.entrySet()) {
            json.append("    \"").append(entry.getKey()).append("\": [");
            for (int categoryIndex = 0;
                    categoryIndex < entry.getValue().size();
                    categoryIndex++) {
                if (categoryIndex > 0) {
                    json.append(", ");
                }
                json.append("\"").append(entry.getValue().get(categoryIndex)).append("\"");
            }
            json.append("]");
            if (++index < classifications.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  }\n");
        json.append("}\n");
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }
}
