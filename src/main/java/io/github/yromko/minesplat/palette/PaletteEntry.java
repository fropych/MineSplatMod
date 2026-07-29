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

    PaletteEntry(
            String blockId,
            Map<String, String> properties,
            Set<String> profiles,
            int priority,
            EnumMap<Direction, double[]> faceColors
    ) {
        this.blockId = blockId;
        this.properties = Collections.unmodifiableMap(properties);
        this.profiles = Collections.unmodifiableSet(profiles);
        this.priority = priority;
        this.faceColors = faceColors;
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

    public String stateKey() {
        return properties.isEmpty() ? blockId : blockId + properties;
    }
}
