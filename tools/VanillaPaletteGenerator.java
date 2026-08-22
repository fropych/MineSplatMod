import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Development-only vanilla palette generator.
 *
 * <p>Reads blockstates, block models, and block textures directly from an
 * installed Minecraft client JAR. It emits numeric face colors only; Mojang
 * textures are never copied into the project output.</p>
 *
 * <p>The generated audit palette intentionally includes only variant-based
 * states whose resolved model consists of one or more complete 16x16x16 cube
 * layers. Multipart, entity-rendered, and partial geometry is reported but
 * excluded rather than assigned a misleading full-voxel color.</p>
 */
public final class VanillaPaletteGenerator implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> DIRECTIONS = List.of(
            "down", "up", "north", "south", "west", "east");
    private static final int SAMPLE_SIZE = 16;
    private static final double PLAINS_TEMPERATURE = 0.8;
    private static final double PLAINS_DOWNFALL = 0.4;
    private static final Set<String> FALLING_BLOCKS = Set.of(
            "minecraft:sand",
            "minecraft:suspicious_sand",
            "minecraft:red_sand",
            "minecraft:gravel",
            "minecraft:suspicious_gravel",
            "minecraft:white_concrete_powder",
            "minecraft:orange_concrete_powder",
            "minecraft:magenta_concrete_powder",
            "minecraft:light_blue_concrete_powder",
            "minecraft:yellow_concrete_powder",
            "minecraft:lime_concrete_powder",
            "minecraft:pink_concrete_powder",
            "minecraft:gray_concrete_powder",
            "minecraft:light_gray_concrete_powder",
            "minecraft:cyan_concrete_powder",
            "minecraft:purple_concrete_powder",
            "minecraft:blue_concrete_powder",
            "minecraft:brown_concrete_powder",
            "minecraft:green_concrete_powder",
            "minecraft:red_concrete_powder",
            "minecraft:black_concrete_powder",
            "minecraft:anvil",
            "minecraft:chipped_anvil",
            "minecraft:damaged_anvil",
            "minecraft:dragon_egg");

    private final Path clientJar;
    private final ZipFile archive;
    private final Map<String, ResolvedModel> modelCache = new HashMap<>();
    private final Map<String, TexturePixels> textureCache = new HashMap<>();
    private final int plainsGrassTint;
    private final int plainsFoliageTint;

    private int blockstateCount;
    private int variantStateCount;
    private int emittedStateCount;
    private int multipartBlockCount;
    private int partialStateCount;
    private int translucentStateCount;
    private int fallingStateCount;
    private int missingResourceStateCount;
    private int unresolvedTintStateCount;

    private VanillaPaletteGenerator(Path clientJar) throws IOException {
        this.clientJar = clientJar.toAbsolutePath().normalize();
        this.archive = new ZipFile(this.clientJar.toFile());
        plainsGrassTint = colormapColor(
                "assets/minecraft/textures/colormap/grass.png");
        plainsFoliageTint = colormapColor(
                "assets/minecraft/textures/colormap/foliage.png");
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2 || arguments.length > 3) {
            System.err.println(
                    "Usage: java ... tools/VanillaPaletteGenerator.java "
                            + "<minecraft-client.jar> <output.json> [block-names.txt]");
            System.exit(2);
        }
        Path clientJar = Path.of(arguments[0]);
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(clientJar)) {
            throw new IllegalArgumentException("Minecraft client JAR not found: " + clientJar);
        }
        try (VanillaPaletteGenerator generator = new VanillaPaletteGenerator(clientJar)) {
            JsonObject palette = generator.generate();
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                GSON.toJson(palette, writer);
            }
            if (arguments.length == 3) {
                Path blockNames = Path.of(arguments[2]).toAbsolutePath().normalize();
                generator.writeBlockNames(palette, blockNames);
            }
            generator.printSummary(output);
        }
    }

    private JsonObject generate() throws Exception {
        List<String> blockstates = archive.stream()
                .map(ZipEntry::getName)
                .filter(name -> name.startsWith("assets/minecraft/blockstates/"))
                .filter(name -> name.endsWith(".json"))
                .sorted()
                .toList();
        blockstateCount = blockstates.size();

        List<JsonObject> entries = new ArrayList<>();
        for (String resource : blockstates) {
            String block = "minecraft:" + resource.substring(
                    "assets/minecraft/blockstates/".length(), resource.length() - 5);
            JsonObject blockstate = readJson(resource);
            if (!blockstate.has("variants")) {
                if (blockstate.has("multipart")) {
                    multipartBlockCount++;
                }
                continue;
            }
            TreeMap<String, JsonElement> variants = new TreeMap<>();
            blockstate.getAsJsonObject("variants").entrySet()
                    .forEach(entry -> variants.put(entry.getKey(), entry.getValue()));
            for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
                variantStateCount++;
                if (FALLING_BLOCKS.contains(block)) {
                    fallingStateCount++;
                    continue;
                }
                RenderedFaces rendered;
                try {
                    rendered = renderVariant(block, variant.getValue());
                } catch (UnresolvedTintException exception) {
                    unresolvedTintStateCount++;
                    continue;
                } catch (MissingResourceException exception) {
                    missingResourceStateCount++;
                    continue;
                }
                if (rendered == null) {
                    partialStateCount++;
                    continue;
                }
                if (!isFullyOpaque(rendered)) {
                    translucentStateCount++;
                    continue;
                }
                entries.add(entry(block, variant.getKey(), rendered));
                emittedStateCount++;
            }
        }
        entries.sort(Comparator
                .comparing((JsonObject value) -> value.get("block").getAsString())
                .thenComparing(value -> value.has("stateKey")
                        ? value.get("stateKey").getAsString() : ""));
        long uniqueBlocks = entries.stream()
                .map(value -> value.get("block").getAsString())
                .distinct()
                .count();
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("minecraftVersion", "1.21.1");
        root.addProperty(
                "colorSpace",
                "linear-light alpha-weighted face averages encoded as sRGB8; "
                        + "converted to OKLab at runtime");

        JsonObject source = new JsonObject();
        source.addProperty("clientJarSha256", sha256(clientJar));
        source.addProperty("generator", "tools/VanillaPaletteGenerator.java");
        source.addProperty("sampleSize", SAMPLE_SIZE);
        source.addProperty("animatedTexturePolicy", "all vertical frames weighted equally");
        source.addProperty("tintBiome", "plains");
        source.addProperty("plainsTemperature", PLAINS_TEMPERATURE);
        source.addProperty("plainsDownfall", PLAINS_DOWNFALL);
        root.add("source", source);

        JsonObject summary = new JsonObject();
        summary.addProperty("blockstates", blockstateCount);
        summary.addProperty("variantStates", variantStateCount);
        summary.addProperty("emittedFullCubeStates", emittedStateCount);
        summary.addProperty("emittedUniqueBlocks", uniqueBlocks);
        summary.addProperty("translucentStatesExcluded", translucentStateCount);
        summary.addProperty("fallingStatesExcluded", fallingStateCount);
        summary.addProperty("fallingBlockIds", FALLING_BLOCKS.size());
        summary.addProperty("multipartBlocksExcluded", multipartBlockCount);
        summary.addProperty("partialStatesExcluded", partialStateCount);
        summary.addProperty("missingResourceStatesExcluded", missingResourceStateCount);
        summary.addProperty("unresolvedTintStatesExcluded", unresolvedTintStateCount);
        root.add("summary", summary);

        JsonArray outputEntries = new JsonArray();
        entries.forEach(outputEntries::add);
        root.add("entries", outputEntries);
        return root;
    }

    private void writeBlockNames(JsonObject palette, Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> blocks = palette.getAsJsonArray("entries").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .map(entry -> entry.get("block").getAsString())
                .distinct()
                .sorted()
                .toList();
        Files.write(output, blocks, StandardCharsets.UTF_8);
        System.out.println("Block names written to: " + output);
    }

    private static boolean isFullyOpaque(RenderedFaces rendered) {
        return rendered.colors().values().stream()
                .allMatch(color -> color.opacity() >= 1.0 - 1e-9);
    }

    private RenderedFaces renderVariant(String block, JsonElement specification)
            throws IOException {
        List<ModelReference> alternatives = modelReferences(specification);
        if (alternatives.isEmpty()) {
            throw new MissingResourceException("Variant has no model");
        }
        List<WeightedFaces> rendered = new ArrayList<>();
        for (ModelReference alternative : alternatives) {
            RenderedFaces faces = renderModel(block, alternative);
            if (faces == null) {
                return null;
            }
            rendered.add(new WeightedFaces(faces, alternative.weight()));
        }
        return averageAlternatives(rendered);
    }

    private List<ModelReference> modelReferences(JsonElement specification) {
        List<ModelReference> result = new ArrayList<>();
        if (specification.isJsonArray()) {
            for (JsonElement element : specification.getAsJsonArray()) {
                addModelReference(result, element.getAsJsonObject());
            }
        } else if (specification.isJsonObject()) {
            addModelReference(result, specification.getAsJsonObject());
        }
        return result;
    }

    private void addModelReference(List<ModelReference> output, JsonObject object) {
        if (!object.has("model")) {
            return;
        }
        output.add(new ModelReference(
                normalizeId(object.get("model").getAsString()),
                object.has("x") ? object.get("x").getAsInt() : 0,
                object.has("y") ? object.get("y").getAsInt() : 0,
                object.has("weight") ? object.get("weight").getAsInt() : 1));
    }

    private RenderedFaces renderModel(String block, ModelReference reference)
            throws IOException {
        ResolvedModel model = resolveModel(reference.model(), new ArrayList<>());
        if (model.elements() == null || model.elements().isEmpty()) {
            return null;
        }
        Map<String, PixelGrid> layers = new LinkedHashMap<>();
        for (String direction : DIRECTIONS) {
            layers.put(direction, new PixelGrid());
        }

        for (JsonElement elementValue : model.elements()) {
            JsonObject element = elementValue.getAsJsonObject();
            if (element.has("rotation")
                    || !vectorEquals(element.getAsJsonArray("from"), 0.0)
                    || !vectorEquals(element.getAsJsonArray("to"), 16.0)) {
                return null;
            }
            if (!element.has("faces")) {
                return null;
            }
            JsonObject faces = element.getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> faceEntry : faces.entrySet()) {
                if (!DIRECTIONS.contains(faceEntry.getKey())) {
                    continue;
                }
                JsonObject face = faceEntry.getValue().getAsJsonObject();
                if (!face.has("texture")) {
                    throw new MissingResourceException("Face has no texture");
                }
                String texture = resolveTextureReference(
                        face.get("texture").getAsString(), model.textures());
                TexturePixels pixels = loadTexture(texture);
                int tint = 0xffffff;
                if (face.has("tintindex") && face.get("tintindex").getAsInt() >= 0) {
                    tint = tintFor(block).orElseThrow(() ->
                            new UnresolvedTintException("No canonical tint for " + block));
                }
                String worldDirection = rotateDirection(
                        faceEntry.getKey(), reference.x(), reference.y());
                layers.get(worldDirection).over(pixels, tint);
            }
        }

        Map<String, FaceColor> result = new LinkedHashMap<>();
        for (String direction : DIRECTIONS) {
            PixelGrid pixels = layers.get(direction);
            if (!pixels.hasLayer()) {
                return null;
            }
            result.put(direction, pixels.average());
        }
        return new RenderedFaces(result);
    }

    private ResolvedModel resolveModel(String model, List<String> stack) throws IOException {
        ResolvedModel cached = modelCache.get(model);
        if (cached != null) {
            return cached;
        }
        if (stack.contains(model)) {
            throw new MissingResourceException("Model inheritance cycle: " + stack + " -> " + model);
        }
        stack.add(model);
        JsonObject object = readJson(modelResource(model));
        Map<String, String> textures = new LinkedHashMap<>();
        JsonArray elements = null;
        if (object.has("parent")) {
            ResolvedModel parent = resolveModel(
                    normalizeId(object.get("parent").getAsString()), stack);
            textures.putAll(parent.textures());
            elements = parent.elements();
        }
        if (object.has("textures")) {
            object.getAsJsonObject("textures").entrySet().forEach(entry ->
                    textures.put(entry.getKey(), entry.getValue().getAsString()));
        }
        if (object.has("elements")) {
            elements = object.getAsJsonArray("elements");
        }
        ResolvedModel resolved = new ResolvedModel(Map.copyOf(textures), elements);
        modelCache.put(model, resolved);
        stack.removeLast();
        return resolved;
    }

    private String resolveTextureReference(String reference, Map<String, String> textures) {
        String current = reference;
        for (int depth = 0; depth < 32 && current.startsWith("#"); depth++) {
            current = textures.get(current.substring(1));
            if (current == null) {
                throw new MissingResourceException("Unresolved texture reference " + reference);
            }
        }
        if (current.startsWith("#")) {
            throw new MissingResourceException("Texture reference cycle " + reference);
        }
        return normalizeId(current);
    }

    private TexturePixels loadTexture(String texture) throws IOException {
        TexturePixels cached = textureCache.get(texture);
        if (cached != null) {
            return cached;
        }
        String resource = textureResource(texture);
        ZipEntry entry = archive.getEntry(resource);
        if (entry == null) {
            throw new MissingResourceException("Missing texture " + resource);
        }
        BufferedImage image;
        try (InputStream input = archive.getInputStream(entry)) {
            image = ImageIO.read(input);
        }
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new MissingResourceException("Invalid texture " + resource);
        }
        int frameSize = image.getWidth();
        int frameCount = image.getHeight() % frameSize == 0
                ? image.getHeight() / frameSize : 1;
        int frameHeight = frameCount == 1 ? image.getHeight() : frameSize;
        double[][][] pixels = new double[SAMPLE_SIZE][SAMPLE_SIZE][4];
        for (int sampleY = 0; sampleY < SAMPLE_SIZE; sampleY++) {
            for (int sampleX = 0; sampleX < SAMPLE_SIZE; sampleX++) {
                double alphaSum = 0.0;
                double redPremultiplied = 0.0;
                double greenPremultiplied = 0.0;
                double bluePremultiplied = 0.0;
                for (int frame = 0; frame < frameCount; frame++) {
                    int sourceX = Math.min(
                            image.getWidth() - 1,
                            sampleX * image.getWidth() / SAMPLE_SIZE);
                    int sourceY = Math.min(
                            frame * frameHeight + frameHeight - 1,
                            frame * frameHeight + sampleY * frameHeight / SAMPLE_SIZE);
                    int argb = image.getRGB(sourceX, sourceY);
                    double alpha = ((argb >>> 24) & 0xff) / 255.0;
                    redPremultiplied += ((argb >>> 16) & 0xff) / 255.0 * alpha;
                    greenPremultiplied += ((argb >>> 8) & 0xff) / 255.0 * alpha;
                    bluePremultiplied += (argb & 0xff) / 255.0 * alpha;
                    alphaSum += alpha;
                }
                double alpha = alphaSum / frameCount;
                pixels[sampleY][sampleX][3] = alpha;
                if (alphaSum > 0.0) {
                    pixels[sampleY][sampleX][0] = redPremultiplied / alphaSum;
                    pixels[sampleY][sampleX][1] = greenPremultiplied / alphaSum;
                    pixels[sampleY][sampleX][2] = bluePremultiplied / alphaSum;
                }
            }
        }
        TexturePixels result = new TexturePixels(pixels);
        textureCache.put(texture, result);
        return result;
    }

    private RenderedFaces averageAlternatives(List<WeightedFaces> alternatives) {
        Map<String, FaceColor> result = new LinkedHashMap<>();
        for (String direction : DIRECTIONS) {
            double red = 0.0;
            double green = 0.0;
            double blue = 0.0;
            double opacity = 0.0;
            int totalWeight = 0;
            for (WeightedFaces alternative : alternatives) {
                FaceColor color = alternative.faces().colors().get(direction);
                int weight = Math.max(1, alternative.weight());
                red += srgbToLinear(color.red() / 255.0) * weight;
                green += srgbToLinear(color.green() / 255.0) * weight;
                blue += srgbToLinear(color.blue() / 255.0) * weight;
                opacity += color.opacity() * weight;
                totalWeight += weight;
            }
            result.put(direction, new FaceColor(
                    toByte(linearToSrgb(red / totalWeight)),
                    toByte(linearToSrgb(green / totalWeight)),
                    toByte(linearToSrgb(blue / totalWeight)),
                    opacity / totalWeight));
        }
        return new RenderedFaces(result);
    }

    private JsonObject entry(String block, String stateKey, RenderedFaces rendered) {
        JsonObject result = new JsonObject();
        result.addProperty("block", block);
        Map<String, String> properties = properties(stateKey);
        if (!properties.isEmpty()) {
            JsonObject outputProperties = new JsonObject();
            properties.forEach(outputProperties::addProperty);
            result.add("properties", outputProperties);
            result.addProperty("stateKey", stateKey);
        }
        result.addProperty("priority", 100);

        JsonObject faces = new JsonObject();
        FaceColor all = averageDirections(rendered);
        faces.add("all", rgb(all));
        for (String direction : DIRECTIONS) {
            faces.add(direction, rgb(rendered.colors().get(direction)));
        }
        result.add("faces", faces);

        JsonObject opacity = new JsonObject();
        for (String direction : DIRECTIONS) {
            opacity.addProperty(
                    direction,
                    round(rendered.colors().get(direction).opacity(), 6));
        }
        result.add("faceOpacity", opacity);
        return result;
    }

    private FaceColor averageDirections(RenderedFaces rendered) {
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;
        double opacity = 0.0;
        for (String direction : DIRECTIONS) {
            FaceColor color = rendered.colors().get(direction);
            red += srgbToLinear(color.red() / 255.0);
            green += srgbToLinear(color.green() / 255.0);
            blue += srgbToLinear(color.blue() / 255.0);
            opacity += color.opacity();
        }
        return new FaceColor(
                toByte(linearToSrgb(red / DIRECTIONS.size())),
                toByte(linearToSrgb(green / DIRECTIONS.size())),
                toByte(linearToSrgb(blue / DIRECTIONS.size())),
                opacity / DIRECTIONS.size());
    }

    private JsonArray rgb(FaceColor color) {
        JsonArray result = new JsonArray();
        result.add(color.red());
        result.add(color.green());
        result.add(color.blue());
        return result;
    }

    private OptionalInt tintFor(String block) {
        if (block.equals("minecraft:grass_block")) {
            return OptionalInt.of(plainsGrassTint);
        }
        if (block.equals("minecraft:spruce_leaves")) {
            return OptionalInt.of(0x619961);
        }
        if (block.equals("minecraft:birch_leaves")) {
            return OptionalInt.of(0x80a755);
        }
        if (block.endsWith("_leaves")) {
            return OptionalInt.of(plainsFoliageTint);
        }
        return OptionalInt.empty();
    }

    private int colormapColor(String resource) throws IOException {
        ZipEntry entry = archive.getEntry(resource);
        if (entry == null) {
            throw new MissingResourceException("Missing colormap " + resource);
        }
        BufferedImage image;
        try (InputStream input = archive.getInputStream(entry)) {
            image = ImageIO.read(input);
        }
        double humidity = PLAINS_DOWNFALL * PLAINS_TEMPERATURE;
        int x = clamp((int) ((1.0 - PLAINS_TEMPERATURE) * 255.0), 0, 255);
        int y = clamp((int) ((1.0 - humidity) * 255.0), 0, 255);
        x = Math.min(image.getWidth() - 1, x);
        y = Math.min(image.getHeight() - 1, y);
        return image.getRGB(x, y) & 0xffffff;
    }

    private JsonObject readJson(String resource) throws IOException {
        ZipEntry entry = archive.getEntry(resource);
        if (entry == null) {
            throw new MissingResourceException("Missing resource " + resource);
        }
        try (Reader reader = new java.io.InputStreamReader(
                archive.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Map<String, String> properties(String stateKey) {
        Map<String, String> result = new TreeMap<>();
        if (stateKey == null || stateKey.isBlank()) {
            return result;
        }
        for (String pair : stateKey.split(",")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && separator + 1 < pair.length()) {
                result.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        return result;
    }

    private static boolean vectorEquals(JsonArray vector, double expected) {
        if (vector == null || vector.size() != 3) {
            return false;
        }
        for (JsonElement coordinate : vector) {
            if (Math.abs(coordinate.getAsDouble() - expected) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    private static String rotateDirection(String direction, int xDegrees, int yDegrees) {
        int[] vector = switch (direction) {
            case "down" -> new int[]{0, -1, 0};
            case "up" -> new int[]{0, 1, 0};
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "west" -> new int[]{-1, 0, 0};
            case "east" -> new int[]{1, 0, 0};
            default -> throw new IllegalArgumentException("Unknown direction " + direction);
        };
        for (int turns = Math.floorMod(xDegrees, 360) / 90; turns > 0; turns--) {
            vector = new int[]{vector[0], -vector[2], vector[1]};
        }
        for (int turns = Math.floorMod(yDegrees, 360) / 90; turns > 0; turns--) {
            vector = new int[]{-vector[2], vector[1], vector[0]};
        }
        if (vector[1] < 0) {
            return "down";
        }
        if (vector[1] > 0) {
            return "up";
        }
        if (vector[2] < 0) {
            return "north";
        }
        if (vector[2] > 0) {
            return "south";
        }
        return vector[0] < 0 ? "west" : "east";
    }

    private static String normalizeId(String id) {
        String value = id.contains(":") ? id : "minecraft:" + id;
        int separator = value.indexOf(':');
        String namespace = value.substring(0, separator);
        String path = value.substring(separator + 1);
        return namespace + ":" + path;
    }

    private static String modelResource(String model) {
        int separator = model.indexOf(':');
        return "assets/" + model.substring(0, separator) + "/models/"
                + model.substring(separator + 1) + ".json";
    }

    private static String textureResource(String texture) {
        int separator = texture.indexOf(':');
        return "assets/" + texture.substring(0, separator) + "/textures/"
                + texture.substring(separator + 1) + ".png";
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private void printSummary(Path output) {
        System.out.println("Minecraft client: " + clientJar);
        System.out.println("Blockstates: " + blockstateCount);
        System.out.println("Variant states: " + variantStateCount);
        System.out.println("Full-cube states emitted: " + emittedStateCount);
        System.out.println("Translucent states excluded: " + translucentStateCount);
        System.out.println("Falling states excluded: " + fallingStateCount);
        System.out.println("Multipart blocks excluded: " + multipartBlockCount);
        System.out.println("Partial states excluded: " + partialStateCount);
        System.out.println("Missing-resource states excluded: " + missingResourceStateCount);
        System.out.println("Unresolved-tint states excluded: " + unresolvedTintStateCount);
        System.out.println("Palette written to: " + output);
    }

    private static double srgbToLinear(double value) {
        return value <= 0.04045
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double linearToSrgb(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped <= 0.0031308
                ? clamped * 12.92
                : 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;
    }

    private static int toByte(double value) {
        return clamp((int) Math.round(value * 255.0), 0, 255);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10.0, places);
        return Math.round(value * scale) / scale;
    }

    @Override
    public void close() throws IOException {
        archive.close();
    }

    private record ResolvedModel(Map<String, String> textures, JsonArray elements) {
    }

    private record ModelReference(String model, int x, int y, int weight) {
    }

    private record RenderedFaces(Map<String, FaceColor> colors) {
    }

    private record WeightedFaces(RenderedFaces faces, int weight) {
    }

    private record FaceColor(int red, int green, int blue, double opacity) {
    }

    private record TexturePixels(double[][][] pixels) {
    }

    private static final class PixelGrid {
        private final double[][][] pixels = new double[SAMPLE_SIZE][SAMPLE_SIZE][4];
        private boolean hasLayer;

        private void over(TexturePixels texture, int tint) {
            double tintRed = ((tint >>> 16) & 0xff) / 255.0;
            double tintGreen = ((tint >>> 8) & 0xff) / 255.0;
            double tintBlue = (tint & 0xff) / 255.0;
            for (int y = 0; y < SAMPLE_SIZE; y++) {
                for (int x = 0; x < SAMPLE_SIZE; x++) {
                    double[] source = texture.pixels()[y][x];
                    double sourceAlpha = source[3];
                    double destinationAlpha = pixels[y][x][3];
                    double outputAlpha = sourceAlpha
                            + destinationAlpha * (1.0 - sourceAlpha);
                    if (outputAlpha <= 0.0) {
                        continue;
                    }
                    double sourceRed = source[0] * tintRed;
                    double sourceGreen = source[1] * tintGreen;
                    double sourceBlue = source[2] * tintBlue;
                    pixels[y][x][0] = (
                            sourceRed * sourceAlpha
                                    + pixels[y][x][0] * destinationAlpha
                                    * (1.0 - sourceAlpha)) / outputAlpha;
                    pixels[y][x][1] = (
                            sourceGreen * sourceAlpha
                                    + pixels[y][x][1] * destinationAlpha
                                    * (1.0 - sourceAlpha)) / outputAlpha;
                    pixels[y][x][2] = (
                            sourceBlue * sourceAlpha
                                    + pixels[y][x][2] * destinationAlpha
                                    * (1.0 - sourceAlpha)) / outputAlpha;
                    pixels[y][x][3] = outputAlpha;
                }
            }
            hasLayer = true;
        }

        private boolean hasLayer() {
            return hasLayer;
        }

        private FaceColor average() {
            double alphaTotal = 0.0;
            double red = 0.0;
            double green = 0.0;
            double blue = 0.0;
            for (int y = 0; y < SAMPLE_SIZE; y++) {
                for (int x = 0; x < SAMPLE_SIZE; x++) {
                    double[] pixel = pixels[y][x];
                    double alpha = pixel[3];
                    red += srgbToLinear(pixel[0]) * alpha;
                    green += srgbToLinear(pixel[1]) * alpha;
                    blue += srgbToLinear(pixel[2]) * alpha;
                    alphaTotal += alpha;
                }
            }
            if (alphaTotal <= 0.0) {
                return new FaceColor(0, 0, 0, 0.0);
            }
            return new FaceColor(
                    toByte(linearToSrgb(red / alphaTotal)),
                    toByte(linearToSrgb(green / alphaTotal)),
                    toByte(linearToSrgb(blue / alphaTotal)),
                    alphaTotal / (SAMPLE_SIZE * SAMPLE_SIZE));
        }
    }

    private static class MissingResourceException extends RuntimeException {
        private MissingResourceException(String message) {
            super(message);
        }
    }

    private static final class UnresolvedTintException extends MissingResourceException {
        private UnresolvedTintException(String message) {
            super(message);
        }
    }
}
