package io.github.yromko.minesplat.cnb;

import io.github.yromko.minesplat.config.PaletteProfile;
import io.github.yromko.minesplat.util.FileNames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CnbBlueprintStore {
    private static final long MAX_COMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 128L * 1024 * 1024;
    private static final Set<String> ROOT_KEYS = Set.of(
            "schemaVersion", "dataVersion", "name", "author", "createdAt",
            "resolution", "size", "paletteProfile", "palette", "voxels");
    private static final Set<String> PALETTE_KEYS = Set.of("state", "faceColors");

    private final Path directory;

    public CnbBlueprintStore(Path directory) {
        this.directory = directory;
    }

    public static CnbBlueprintStore gameStore() {
        return new CnbBlueprintStore(FabricLoader.getInstance().getGameDir()
                .resolve("minesplat")
                .resolve("blueprints"));
    }

    public Path directory() {
        return directory;
    }

    public Path writeUnique(CnbBlueprint blueprint) throws IOException {
        Path output = FileNames.uniqueBlueprintPath(
                directory, blueprint.name(), blueprint.resolution());
        Files.createDirectories(directory);
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(toNbt(blueprint), temporary);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output);
            }
            return output;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public CnbBlueprint read(Path input) throws IOException {
        Path normalized = input.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().endsWith(".msbp")) {
            throw new IOException("MineSplat blueprint must use the .msbp extension");
        }
        long size = Files.size(normalized);
        if (size < 1 || size > MAX_COMPRESSED_BYTES) {
            throw new IOException("MineSplat blueprint file size is invalid");
        }
        NbtCompound root = NbtIo.readCompressed(
                normalized, NbtSizeTracker.of(MAX_UNCOMPRESSED_BYTES));
        try {
            return fromNbt(root);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid MineSplat blueprint: " + exception.getMessage(), exception);
        }
    }

    public List<BlueprintFile> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<BlueprintFile> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(value -> value.getFileName().toString().endsWith(".msbp"))
                    .toList()) {
                try {
                    CnbBlueprint blueprint = read(path);
                    result.add(new BlueprintFile(path, blueprint, null));
                } catch (IOException exception) {
                    result.add(new BlueprintFile(path, null, exception.getMessage()));
                }
            }
        }
        result.sort(Comparator.comparingLong((BlueprintFile value) ->
                value.blueprint() == null ? Long.MIN_VALUE : value.blueprint().createdAt())
                .reversed()
                .thenComparing(value -> value.path().getFileName().toString()));
        return List.copyOf(result);
    }

    private static NbtCompound toNbt(CnbBlueprint blueprint) {
        NbtCompound root = new NbtCompound();
        root.putInt("schemaVersion", CnbBlueprint.SCHEMA_VERSION);
        root.putInt("dataVersion", blueprint.dataVersion());
        root.putString("name", blueprint.name());
        root.putString("author", blueprint.author());
        root.putLong("createdAt", blueprint.createdAt());
        root.putInt("resolution", blueprint.resolution());
        root.putIntArray("size", new int[]{
                blueprint.width(), blueprint.height(), blueprint.depth()});
        root.putString("paletteProfile", blueprint.paletteProfile().id());
        NbtList palette = new NbtList();
        for (CnbPaletteEntry entry : blueprint.palette()) {
            NbtCompound serialized = new NbtCompound();
            serialized.putString("state", entry.blockState());
            serialized.putIntArray("faceColors", entry.faceColors());
            palette.add(serialized);
        }
        root.put("palette", palette);
        root.putLongArray("voxels", blueprint.voxels());
        return root;
    }

    private static CnbBlueprint fromNbt(NbtCompound root) {
        requireExactKeys(root, ROOT_KEYS, "root");
        int schema = requiredInt(root, "schemaVersion");
        if (schema != CnbBlueprint.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schema version " + schema);
        }
        int[] dimensions = requiredIntArray(root, "size");
        if (dimensions.length != 3) {
            throw new IllegalArgumentException("Blueprint size must have three components");
        }
        String profileId = requiredString(root, "paletteProfile");
        PaletteProfile profile = null;
        if ("maximum_color".equals(profileId)) {
            profile = PaletteProfile.ALL;
        }
        for (PaletteProfile candidate : PaletteProfile.values()) {
            if (candidate.id().equals(profileId)) {
                profile = candidate;
                break;
            }
        }
        if (profile == null) {
            throw new IllegalArgumentException("Unknown palette profile " + profileId);
        }

        NbtList paletteNbt = root.getList("palette").orElseThrow(() ->
                missingOrInvalid("palette"));
        if (paletteNbt.isEmpty()) {
            throw new IllegalArgumentException("Blueprint palette is empty");
        }
        List<CnbPaletteEntry> palette = new ArrayList<>(paletteNbt.size());
        Set<String> states = new HashSet<>();
        for (int index = 0; index < paletteNbt.size(); index++) {
            NbtCompound serialized = requiredCompound(paletteNbt, index);
            requireExactKeys(serialized, PALETTE_KEYS, "palette entry");
            String state = requiredString(serialized, "state");
            BlockStateStrings.validateSyntax(state);
            if (!states.add(state)) {
                throw new IllegalArgumentException("Blueprint palette contains duplicate states");
            }
            palette.add(new CnbPaletteEntry(
                    state, requiredIntArray(serialized, "faceColors")));
        }
        return new CnbBlueprint(
                requiredString(root, "name"),
                requiredString(root, "author"),
                requiredLong(root, "createdAt"),
                requiredInt(root, "dataVersion"),
                requiredInt(root, "resolution"),
                dimensions[0], dimensions[1], dimensions[2],
                profile,
                palette,
                root.getLongArray("voxels").orElseThrow(() ->
                        missingOrInvalid("voxels")));
    }

    private static int requiredInt(NbtCompound compound, String key) {
        return compound.getInt(key).orElseThrow(() -> missingOrInvalid(key));
    }

    private static long requiredLong(NbtCompound compound, String key) {
        return compound.getLong(key).orElseThrow(() -> missingOrInvalid(key));
    }

    private static String requiredString(NbtCompound compound, String key) {
        return compound.getString(key).orElseThrow(() -> missingOrInvalid(key));
    }

    private static int[] requiredIntArray(NbtCompound compound, String key) {
        return compound.getIntArray(key).orElseThrow(() -> missingOrInvalid(key));
    }

    private static NbtCompound requiredCompound(NbtList list, int index) {
        return list.getCompound(index).orElseThrow(() ->
                missingOrInvalid("palette[" + index + "]"));
    }

    private static IllegalArgumentException missingOrInvalid(String key) {
        return new IllegalArgumentException("Missing or invalid blueprint field " + key);
    }

    private static void requireExactKeys(
            NbtCompound compound,
            Set<String> expected,
            String description
    ) {
        if (!compound.getKeys().equals(expected)) {
            throw new IllegalArgumentException(
                    "Unexpected " + description + " fields " + compound.getKeys());
        }
    }

    public record BlueprintFile(Path path, CnbBlueprint blueprint, String error) {
        public boolean valid() {
            return blueprint != null;
        }
    }
}
