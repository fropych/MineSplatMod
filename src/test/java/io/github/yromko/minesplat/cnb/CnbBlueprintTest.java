package io.github.yromko.minesplat.cnb;

import io.github.yromko.minesplat.config.PaletteProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CnbBlueprintTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsCompressedNbtAndAvoidsOverwrite() throws Exception {
        CnbBlueprintStore store = new CnbBlueprintStore(temporary);
        CnbBlueprint source = blueprint(
                32, 3, 2, 2,
                new long[]{
                        CnbBlueprint.pack(0, 0),
                        CnbBlueprint.pack(2 + 3L * (1 + 2L), 0)
                });

        Path first = store.writeUnique(source);
        Path second = store.writeUnique(source);
        CnbBlueprint loaded = store.read(first);

        assertEquals("sample", loaded.name());
        assertEquals(3, loaded.width());
        assertEquals(2, loaded.height());
        assertEquals(2, loaded.depth());
        assertEquals(2, loaded.voxelCount());
        assertArrayEquals(source.voxels(), loaded.voxels());
        assertTrue(first.getFileName().toString().endsWith("-cnb-r32.msbp"));
        assertTrue(second.getFileName().toString().endsWith("-cnb-r32-2.msbp"));
    }

    @Test
    void rejectsTruncatedAndDuplicatePayloads() throws Exception {
        CnbBlueprintStore store = new CnbBlueprintStore(temporary);
        Path valid = store.writeUnique(blueprint(
                32, 1, 1, 2,
                new long[]{CnbBlueprint.pack(0, 0), CnbBlueprint.pack(1, 0)}));
        byte[] bytes = Files.readAllBytes(valid);
        Path truncated = temporary.resolve("truncated.msbp");
        Files.write(truncated, java.util.Arrays.copyOf(bytes, bytes.length / 2));

        assertThrows(Exception.class, () -> store.read(truncated));
        assertThrows(IllegalArgumentException.class, () -> blueprint(
                32, 1, 1, 1,
                new long[]{CnbBlueprint.pack(0, 0), CnbBlueprint.pack(0, 0)}));
    }

    @Test
    void packsAllSupportedGridSizesAndRotatesCoordinates() {
        CnbBlueprint source = blueprint(
                32, 32, 17, 8,
                new long[]{CnbBlueprint.pack(31, 0)});

        for (int side : new int[]{16, 8, 4, 2, 1}) {
            CnbPackedModel packed = CnbPacking.pack(source, side, 0);
            assertEquals((32 + side - 1) / side, packed.width());
            assertEquals((17 + side - 1) / side, packed.height());
            assertEquals((8 + side - 1) / side, packed.depth());
        }

        CnbPackedModel rotated = CnbPacking.pack(source, 16, 1);
        assertEquals(1, rotated.width());
        assertEquals(2, rotated.depth());
        assertEquals(0, rotated.hostBlocks().get(0).x());
        assertEquals(1, rotated.hostBlocks().get(0).z());

        int x = 7;
        int z = 3;
        int width = 32;
        int depth = 8;
        for (int turn = 0; turn < 4; turn++) {
            int nextX = CnbPacking.rotateX(x, z, width, depth, 1);
            int nextZ = CnbPacking.rotateZ(x, z, width, depth, 1);
            x = nextX;
            z = nextZ;
            int oldWidth = width;
            width = depth;
            depth = oldWidth;
        }
        assertEquals(7, x);
        assertEquals(3, z);
    }

    @Test
    void validatesDirectionalStateSyntax() {
        BlockStateStrings.validateSyntax("minecraft:oak_log[axis=x]");
        assertThrows(IllegalArgumentException.class, () ->
                BlockStateStrings.validateSyntax("minecraft:oak_log[axis=x,axis=z]"));
    }

    @Test
    void previewGreedilyMergesAdjacentFaces() {
        CnbBlueprint source = blueprint(
                32, 2, 1, 1,
                new long[]{CnbBlueprint.pack(0, 0), CnbBlueprint.pack(1, 0)});
        CnbPreviewMesh mesh = CnbPreviewMesh.build(source, 16, 0);
        assertEquals(6, mesh.quadCount());
    }

    @Test
    void acceptsServerMaximum1024BlueprintResolution() {
        CnbBlueprint maximum = blueprint(
                1024, 1024, 1, 1,
                new long[]{CnbBlueprint.pack(1023, 0)});

        assertEquals(1024, maximum.resolution());
        assertEquals(64, CnbPacking.pack(maximum, 16, 0).width());
    }

    @Test
    void buildsSparsePreviewWithoutAllocatingTheFull1024Cube() {
        CnbBlueprint sparse = blueprint(
                1024, 1024, 1024, 1024,
                new long[]{CnbBlueprint.pack(0, 0)});

        CnbPreviewMesh mesh = CnbPreviewMesh.build(sparse, 16, 0);

        assertEquals(6, mesh.quadCount());
    }

    private static CnbBlueprint blueprint(
            int resolution,
            int width,
            int height,
            int depth,
            long[] voxels
    ) {
        int[] colors = new int[18];
        java.util.Arrays.fill(colors, 127);
        return new CnbBlueprint(
                "sample",
                "tester",
                100,
                4189,
                resolution,
                width,
                height,
                depth,
                PaletteProfile.SURVIVAL,
                List.of(new CnbPaletteEntry(
                        "minecraft:oak_log[axis=x]", colors)),
                voxels);
    }
}
