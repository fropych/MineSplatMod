package io.github.yromko.minesplat.palette;

import io.github.yromko.minesplat.config.PaletteProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomPaletteStoreTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsVersionIndependentSelectionDiff() throws Exception {
        BlockPalette palette = BlockPalette.loadDefault();
        Set<String> selected = new LinkedHashSet<>(palette.blockIds(PaletteProfile.SURVIVAL));
        selected.remove("minecraft:stone");
        selected.add("minecraft:bedrock");
        CustomPalette custom = CustomPalette.fromSelection(
                "builders-choice",
                "Builder's choice",
                PaletteProfile.SURVIVAL,
                selected,
                palette);
        CustomPaletteStore store = new CustomPaletteStore(temporary.resolve("palettes"));

        store.save(custom);
        CustomPalette loaded = store.find(custom.id()).orElseThrow();

        assertEquals("Builder's choice", loaded.name());
        assertEquals(PaletteProfile.SURVIVAL, loaded.baseCategory());
        assertEquals(Set.of("minecraft:bedrock"), loaded.includedBlocks());
        assertEquals(Set.of("minecraft:stone"), loaded.excludedBlocks());
        assertEquals(selected, loaded.effectiveBlocks(palette));
        Set<String> runtimeBlocks = palette.candidates(
                        PaletteProfile.ALL,
                        loaded.combinedBlacklist(palette, Set.of("minecraft:bedrock")))
                .stream()
                .map(PaletteEntry::blockId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> expectedRuntimeBlocks = new LinkedHashSet<>(selected);
        expectedRuntimeBlocks.remove("minecraft:bedrock");
        assertEquals(expectedRuntimeBlocks, runtimeBlocks);
        String json = Files.readString(
                store.directory().resolve("builders-choice.json"), StandardCharsets.UTF_8);
        assertFalse(json.contains("minecraftVersion"));
        assertFalse(json.contains("faces"));
    }

    @Test
    void duplicatesDeletesAndSkipsInvalidFiles() throws Exception {
        BlockPalette palette = BlockPalette.loadDefault();
        CustomPaletteStore store = new CustomPaletteStore(temporary.resolve("palettes"));
        CustomPalette source = CustomPalette.fromSelection(
                "original",
                "Original",
                PaletteProfile.SOLID_COLORS,
                palette.blockIds(PaletteProfile.SOLID_COLORS),
                palette);
        store.save(source);
        CustomPalette copy = store.duplicate(source, "Copy");
        Files.writeString(
                store.directory().resolve("broken.json"),
                "{not json",
                StandardCharsets.UTF_8);

        assertEquals(2, store.list().size());
        assertEquals(source.effectiveBlocks(palette), copy.effectiveBlocks(palette));
        assertTrue(store.delete(source.id()));
        assertFalse(store.find(source.id()).isPresent());
        assertEquals(1, store.list().size());
    }

    @Test
    void editingPreservesBlockIdsFromOtherMinecraftVersions() {
        BlockPalette palette = BlockPalette.loadDefault();
        CustomPalette imported = new CustomPalette(
                "future-ready",
                "Future ready",
                PaletteProfile.SOLID_COLORS,
                Set.of("minecraft:future_colored_block"),
                Set.of());

        CustomPalette edited = imported.withSelection(
                "Renamed",
                PaletteProfile.SOLID_COLORS,
                palette.blockIds(PaletteProfile.SOLID_COLORS),
                palette);

        assertTrue(edited.includedBlocks().contains("minecraft:future_colored_block"));
        assertEquals(
                palette.blockIds(PaletteProfile.SOLID_COLORS),
                edited.effectiveBlocks(palette));
    }
}
