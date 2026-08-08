package io.github.yromko.minesplat.cnb;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;

import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

public final class BlockStateStrings {
    private BlockStateStrings() {
    }

    public static BlockState resolve(String serialized) {
        validateSyntax(serialized);
        int bracket = serialized.indexOf('[');
        String identifier = bracket < 0 ? serialized : serialized.substring(0, bracket);
        if ((bracket < 0 && serialized.indexOf(']') >= 0)
                || (bracket >= 0 && !serialized.endsWith("]"))) {
            throw new IllegalArgumentException("Invalid block state syntax " + serialized);
        }
        Identifier id = Identifier.tryParse(identifier);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            throw new IllegalArgumentException("Unknown Minecraft block " + identifier);
        }
        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();
        if (bracket < 0) {
            return state;
        }
        String properties = serialized.substring(bracket + 1, serialized.length() - 1);
        if (properties.isBlank()) {
            throw new IllegalArgumentException("Empty block state properties " + serialized);
        }
        for (String assignment : properties.split(",")) {
            int equals = assignment.indexOf('=');
            if (equals < 1 || equals == assignment.length() - 1) {
                throw new IllegalArgumentException("Invalid block state property " + assignment);
            }
            String name = assignment.substring(0, equals);
            String value = assignment.substring(equals + 1);
            Property<?> property = block.getStateManager().getProperty(name);
            if (property == null) {
                throw new IllegalArgumentException(
                        "Block " + identifier + " has no property " + name);
            }
            state = withParsedProperty(state, property, value, identifier);
        }
        return state;
    }

    public static void validateSyntax(String serialized) {
        if (serialized == null || serialized.isBlank() || serialized.length() > 512) {
            throw new IllegalArgumentException("Invalid blueprint block state");
        }
        int bracket = serialized.indexOf('[');
        String identifier = bracket < 0 ? serialized : serialized.substring(0, bracket);
        if (!identifier.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || (bracket < 0 && serialized.indexOf(']') >= 0)
                || (bracket >= 0 && !serialized.endsWith("]"))) {
            throw new IllegalArgumentException("Invalid block state syntax " + serialized);
        }
        if (bracket < 0) {
            return;
        }
        String properties = serialized.substring(bracket + 1, serialized.length() - 1);
        if (properties.isBlank()) {
            throw new IllegalArgumentException("Empty block state properties " + serialized);
        }
        Set<String> names = new HashSet<>();
        for (String assignment : properties.split(",")) {
            int equals = assignment.indexOf('=');
            if (equals < 1 || equals == assignment.length() - 1
                    || !assignment.substring(0, equals).matches("[a-z0-9_]+")
                    || !assignment.substring(equals + 1).matches("[a-z0-9_.-]+")
                    || !names.add(assignment.substring(0, equals))) {
                throw new IllegalArgumentException("Invalid block state property " + assignment);
            }
        }
    }

    public static BlockState resolveRotated(String serialized, int quarterTurns) {
        BlockState result = resolve(serialized);
        BlockRotation rotation = switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> BlockRotation.CLOCKWISE_90;
            case 2 -> BlockRotation.CLOCKWISE_180;
            case 3 -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
        return result.rotate(rotation);
    }

    private static <T extends Comparable<T>> BlockState withParsedProperty(
            BlockState state,
            Property<T> property,
            String value,
            String blockId
    ) {
        Optional<T> parsed = property.parse(value);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid " + property.getName() + "=" + value + " for " + blockId);
        }
        return state.with(property, parsed.get());
    }
}
