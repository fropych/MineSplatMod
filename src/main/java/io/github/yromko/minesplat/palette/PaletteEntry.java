package io.github.yromko.minesplat.palette;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public final class PaletteEntry {
    private final String blockId;
    private final Map<String, String> properties;
    private final Set<String> profiles;
    private final int priority;
    private final EnumMap<Direction, double[]> faceColors;
    private final EnumMap<Direction, int[]> faceRgb;

    PaletteEntry(
            String blockId,
            Map<String, String> properties,
            Set<String> profiles,
            int priority,
            EnumMap<Direction, double[]> faceColors,
            EnumMap<Direction, int[]> faceRgb
    ) {
        this.blockId = blockId;
        this.properties = Collections.unmodifiableMap(properties);
        this.profiles = Collections.unmodifiableSet(profiles);
        this.priority = priority;
        this.faceColors = faceColors;
        this.faceRgb = faceRgb;
    }

    public String blockId() {
        return blockId;
    }

    public Map<String, String> properties() {
        return properties;
    }

    public boolean supportsProfile(String profile) {
        return profiles.contains(profile);
    }

    public int priority() {
        return priority;
    }

    public double[] faceColor(Direction direction) {
        return faceColors.get(direction);
    }

    public int[] faceRgb(Direction direction) {
        return faceRgb.get(direction).clone();
    }

    public String stateString() {
        if (properties.isEmpty()) {
            return blockId;
        }
        StringBuilder result = new StringBuilder(blockId).append('[');
        properties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (result.charAt(result.length() - 1) != '[') {
                        result.append(',');
                    }
                    result.append(entry.getKey()).append('=').append(entry.getValue());
                });
        return result.append(']').toString();
    }

    public String stateKey() {
        return stateString();
    }
}
