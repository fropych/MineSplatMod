package io.github.yromko.minesplat.cnb;

import io.github.yromko.minesplat.palette.Direction;

import java.util.Arrays;

public final class CnbPaletteEntry {
    private static final int FACE_COLOR_COMPONENTS = Direction.values().length * 3;

    private final String blockState;
    private final int[] faceColors;

    public CnbPaletteEntry(String blockState, int[] faceColors) {
        if (blockState == null || blockState.isBlank()) {
            throw new IllegalArgumentException("Blueprint block state is blank");
        }
        if (faceColors == null || faceColors.length != FACE_COLOR_COMPONENTS) {
            throw new IllegalArgumentException(
                    "Blueprint palette entry must contain six RGB face colors");
        }
        for (int component : faceColors) {
            if (component < 0 || component > 255) {
                throw new IllegalArgumentException("Blueprint RGB component is out of range");
            }
        }
        this.blockState = blockState;
        this.faceColors = faceColors.clone();
    }

    public String blockState() {
        return blockState;
    }

    public int[] faceColors() {
        return faceColors.clone();
    }

    public int[] faceColor(Direction direction) {
        int offset = direction.ordinal() * 3;
        return Arrays.copyOfRange(faceColors, offset, offset + 3);
    }
}
