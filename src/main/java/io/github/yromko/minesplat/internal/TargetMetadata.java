package io.github.yromko.minesplat.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Build-time target metadata shared by version-sensitive runtime adapters. */
public record TargetMetadata(String minecraftVersion, String chiselsAndBitsVersion) {
    private static final String RESOURCE = "/minesplat-target.properties";
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "minecraftVersion", "chiselsAndBitsVersion");
    private static final Pattern DOTTED_VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){2}");

    public TargetMetadata {
        minecraftVersion = validateVersion("minecraftVersion", minecraftVersion);
        chiselsAndBitsVersion = validateVersion(
                "chiselsAndBitsVersion", chiselsAndBitsVersion);
    }

    public static TargetMetadata current() {
        return Holder.CURRENT;
    }

    static TargetMetadata read(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IllegalStateException("Missing build target metadata " + RESOURCE);
        }
        Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        Set<String> actualKeys = properties.stringPropertyNames();
        if (!actualKeys.equals(REQUIRED_KEYS)) {
            throw new IllegalStateException(
                    "Invalid build target metadata keys; expected=" + REQUIRED_KEYS
                            + ", actual=" + actualKeys);
        }
        return new TargetMetadata(
                properties.getProperty("minecraftVersion"),
                properties.getProperty("chiselsAndBitsVersion"));
    }

    private static TargetMetadata load() {
        try (InputStream stream = TargetMetadata.class.getResourceAsStream(RESOURCE)) {
            return read(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load build target metadata " + RESOURCE,
                    exception);
        }
    }

    private static String validateVersion(String key, String value) {
        if (value == null || !value.equals(value.strip())
                || !DOTTED_VERSION.matcher(value).matches()) {
            throw new IllegalStateException("Invalid " + key + " in " + RESOURCE + ": " + value);
        }
        return value;
    }

    private static final class Holder {
        private static final TargetMetadata CURRENT = load();

        private Holder() {
        }
    }
}
