package io.github.yromko.minesplat.palette;

import com.google.gson.JsonParser;
import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.testutil.TsvoxFixtures;
import io.github.yromko.minesplat.voxel.TsvoxGrid;
import io.github.yromko.minesplat.voxel.TsvoxReader;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorMatcherTest {
    @Test
    void filtersProfilesAndEveryStateOfBlacklistedBlock() {
        BlockPalette palette = BlockPalette.loadDefault();

        assertEquals(399, palette.blockIds().size());
        assertEquals(796, palette.candidates(PaletteProfile.ALL, Set.of()).size());
        assertEquals(731, palette.candidates(PaletteProfile.SURVIVAL, Set.of()).size());
        assertEquals(49, palette.candidates(PaletteProfile.SOLID_COLORS, Set.of()).size());
        assertTrue(palette.candidates(PaletteProfile.ALL, Set.of()).stream()
                .anyMatch(entry -> entry.blockId().equals("minecraft:bedrock")));
        assertFalse(palette.candidates(PaletteProfile.SURVIVAL, Set.of()).stream()
                .anyMatch(entry -> entry.blockId().equals("minecraft:bedrock")));
        assertTrue(palette.candidates(PaletteProfile.SOLID_COLORS, Set.of()).stream()
                .anyMatch(entry -> entry.blockId().equals("minecraft:red_concrete")));
        assertFalse(palette.candidates(PaletteProfile.SOLID_COLORS, Set.of()).stream()
                .anyMatch(entry -> entry.blockId().equals("minecraft:red_glazed_terracotta")));
        assertFalse(palette.candidates(
                        PaletteProfile.SURVIVAL, Set.of("minecraft:stripped_oak_log")).stream()
                .anyMatch(entry -> entry.blockId().equals("minecraft:stripped_oak_log")));
        Set<String> falling = Set.of(
                "minecraft:sand",
                "minecraft:red_sand",
                "minecraft:gravel",
                "minecraft:suspicious_sand",
                "minecraft:suspicious_gravel",
                "minecraft:dragon_egg");
        assertTrue(palette.blockIds().stream().noneMatch(falling::contains));
    }

    @Test
    void usesPriorityThenIdentifierAsDeterministicTieBreaker() {
        BlockPalette priorityPalette = parse("""
                [
                  {"block":"minecraft:stone","profiles":["survival"],"priority":2,
                   "faces":{"all":[0,0,0]}},
                  {"block":"minecraft:cobblestone","profiles":["survival"],"priority":1,
                   "faces":{"all":[0,0,0]}}
                ]
                """);
        MatchedVoxels priority = matchOne(priorityPalette);
        assertEquals("minecraft:cobblestone", priority.block(0).blockId());

        BlockPalette identifierPalette = parse("""
                [
                  {"block":"minecraft:stone","profiles":["survival"],"priority":1,
                   "faces":{"all":[0,0,0]}},
                  {"block":"minecraft:andesite","profiles":["survival"],"priority":1,
                   "faces":{"all":[0,0,0]}}
                ]
                """);
        MatchedVoxels identifier = matchOne(identifierPalette);
        assertEquals("minecraft:andesite", identifier.block(0).blockId());
    }

    @Test
    void scoresOnlyExposedFacesAndSelectsDirectionalLogAxis() {
        BlockPalette palette = parse("""
                [
                  {"block":"minecraft:stripped_oak_log","properties":{"axis":"x"},
                   "profiles":["survival"],"priority":1,
                   "faces":{"all":[0,0,0],"east":[255,0,0],"west":[255,0,0]}},
                  {"block":"minecraft:stripped_oak_log","properties":{"axis":"y"},
                   "profiles":["survival"],"priority":1,
                   "faces":{"all":[0,0,0],"up":[255,0,0],"down":[255,0,0]}}
                ]
                """);
        int n = 32;
        int center = linear(10, 10, 10, n);
        int[] indices = {
                linear(10, 9, 10, n),
                linear(10, 10, 9, n),
                center,
                linear(10, 10, 11, n),
                linear(10, 11, 10, n)
        };
        Arrays.sort(indices);
        byte[] colors = new byte[indices.length * 3];
        for (int index = 0; index < indices.length; index++) {
            colors[index * 3] = (byte) 255;
        }
        TsvoxGrid grid = new TsvoxReader().read(
                TsvoxFixtures.valid(32, indices, colors), 32);
        MatchedVoxels matched = new ColorMatcher().match(
                grid, palette.candidates(PaletteProfile.SURVIVAL, Set.of()));

        for (int index = 0; index < matched.size(); index++) {
            if (matched.x(index) == 0 && matched.y(index) == 1 && matched.z(index) == 1) {
                assertEquals("x", matched.block(index).properties().get("axis"));
                return;
            }
        }
        throw new AssertionError("Center voxel was not found");
    }

    @Test
    void rejectsEmptyCandidateSet() {
        BlockPalette palette = BlockPalette.loadDefault();
        TsvoxGrid grid = new TsvoxReader().read(TsvoxFixtures.oneWhiteVoxel(), 32);
        Set<String> all = palette.blockIds();
        List<PaletteEntry> candidates = palette.candidates(PaletteProfile.SURVIVAL, all);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ColorMatcher().match(grid, candidates));
    }

    @Test
    void matchesAFullBaseGridAgainstTheCompletePalette() {
        int voxelCount = 32 * 32 * 32;
        int[] indices = new int[voxelCount];
        byte[] colors = new byte[voxelCount * 3];
        for (int index = 0; index < voxelCount; index++) {
            indices[index] = index;
            colors[index * 3] = (byte) index;
            colors[index * 3 + 1] = (byte) (index >>> 5);
            colors[index * 3 + 2] = (byte) (index >>> 10);
        }
        TsvoxGrid grid = new TsvoxReader().read(
                TsvoxFixtures.valid(32, indices, colors), 32);
        BlockPalette palette = BlockPalette.loadDefault();

        MatchedVoxels matched = new ColorMatcher().match(
                grid, palette.candidates(PaletteProfile.ALL, Set.of()));

        assertEquals(voxelCount, matched.size());
    }

    @Test
    void flipsTsvoxYIntoMinecraftY() {
        BlockPalette palette = parse("""
                [
                  {"block":"minecraft:black_concrete","profiles":["survival"],"priority":1,
                   "faces":{"all":[0,0,0]}},
                  {"block":"minecraft:white_concrete","profiles":["survival"],"priority":1,
                   "faces":{"all":[255,255,255]}}
                ]
                """);
        int[] indices = {
                linear(3, 4, 5, 32),
                linear(3, 6, 5, 32)
        };
        byte[] colors = {
                0, 0, 0,
                (byte) 255, (byte) 255, (byte) 255
        };
        TsvoxGrid grid = new TsvoxReader().read(
                TsvoxFixtures.valid(32, indices, colors), 32);
        MatchedVoxels matched = new ColorMatcher().match(
                grid, palette.candidates(PaletteProfile.SURVIVAL, Set.of()));

        assertEquals("minecraft:black_concrete", matched.block(0).blockId());
        assertEquals(2, matched.y(0));
        assertEquals("minecraft:white_concrete", matched.block(1).blockId());
        assertEquals(0, matched.y(1));
    }

    private static MatchedVoxels matchOne(BlockPalette palette) {
        TsvoxGrid grid = new TsvoxReader().read(
                TsvoxFixtures.valid(32, new int[]{0}, new byte[]{0, 0, 0}), 32);
        return new ColorMatcher().match(
                grid, palette.candidates(PaletteProfile.SURVIVAL, Set.of()));
    }

    private static BlockPalette parse(String entries) {
        return BlockPalette.parse(JsonParser.parseString("""
                {"minecraftVersion":"1.21.1","entries":%s}
                """.formatted(entries)).getAsJsonObject());
    }

    private static int linear(int x, int y, int z, int resolution) {
        return x + resolution * (y + resolution * z);
    }
}
