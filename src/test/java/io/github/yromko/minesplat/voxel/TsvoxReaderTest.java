package io.github.yromko.minesplat.voxel;

import io.github.yromko.minesplat.testutil.TsvoxFixtures;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsvoxReaderTest {
    private final TsvoxReader reader = new TsvoxReader();

    @Test
    void readsSparseGridInXFastestOrder() {
        int[] indices = {0, 1, 32, 1024, 32767};
        byte[] colors = new byte[indices.length * 3];
        Arrays.fill(colors, (byte) 127);
        TsvoxGrid grid = reader.read(TsvoxFixtures.valid(32, indices, colors), 32);

        assertEquals(5, grid.occupiedCount());
        assertEquals(1, grid.xOf(grid.linearIndexAt(1)));
        assertEquals(1, grid.yOf(grid.linearIndexAt(2)));
        assertEquals(1, grid.zOf(grid.linearIndexAt(3)));
        assertEquals(32, grid.bounds().width());
        assertTrue(grid.isOccupied(31, 31, 31));
    }

    @Test
    void rejectsBadMagicVersionAndFlags() {
        byte[] badMagic = TsvoxFixtures.oneWhiteVoxel();
        badMagic[0] = 'X';
        assertThrows(TsvoxFormatException.class, () -> reader.read(badMagic, 32));

        byte[] badVersion = TsvoxFixtures.oneWhiteVoxel();
        putInt(badVersion, 8, 3);
        assertThrows(TsvoxFormatException.class, () -> reader.read(badVersion, 32));

        byte[] badFlags = TsvoxFixtures.oneWhiteVoxel();
        putInt(badFlags, 84, 0x07);
        assertThrows(TsvoxFormatException.class, () -> reader.read(badFlags, 32));
    }

    @Test
    void rejectsPopcountTruncationExtraColorsAndWrongResolution() {
        byte[] badCount = TsvoxFixtures.oneWhiteVoxel();
        putLong(badCount, 32, 2);
        putLong(badCount, 40, 2);
        assertThrows(TsvoxFormatException.class, () -> reader.read(badCount, 32));

        byte[] truncated = Arrays.copyOf(
                TsvoxFixtures.oneWhiteVoxel(),
                TsvoxFixtures.oneWhiteVoxel().length - 1);
        assertThrows(TsvoxFormatException.class, () -> reader.read(truncated, 32));

        byte[] extraColor = Arrays.copyOf(
                TsvoxFixtures.oneWhiteVoxel(),
                TsvoxFixtures.oneWhiteVoxel().length + 3);
        assertThrows(TsvoxFormatException.class, () -> reader.read(extraColor, 32));

        assertThrows(
                TsvoxFormatException.class,
                () -> reader.read(TsvoxFixtures.valid(
                        3, new int[]{0}, new byte[]{1, 2, 3}), 3));
        assertThrows(
                TsvoxFormatException.class,
                () -> reader.read(TsvoxFixtures.oneWhiteVoxel(), 64));
    }

    @Test
    void acceptsServerMaximum1024Resolution() {
        int lastVoxel = 1024 * 1024 * 1024 - 1;
        TsvoxGrid grid = reader.read(
                TsvoxFixtures.valid(
                        1024,
                        new int[]{0, lastVoxel},
                        new byte[]{0, 0, 0, (byte) 255, (byte) 255, (byte) 255}),
                1024);

        assertEquals(1024, grid.resolution());
        assertEquals(2, grid.occupiedCount());
    }

    private static void putInt(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    private static void putLong(byte[] data, int offset, long value) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
    }
}
