package io.github.yromko.minesplat.cnb;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Plans sparse invisible light sources in real-block space for a C&B model. */
public final class CnbHiddenLighting {
    public static final int SECTION_SIZE = 5;

    private CnbHiddenLighting() {
    }

    public static List<Section> plan(CnbPackedModel packed) {
        Set<BlockPos> occupied = new HashSet<>(packed.hostBlocks().size());
        Set<SectionIndex> activeSections = new LinkedHashSet<>();
        for (CnbPackedModel.HostBlock host : packed.hostBlocks()) {
            BlockPos position = new BlockPos(host.x(), host.y(), host.z());
            occupied.add(position);
            activeSections.add(new SectionIndex(
                    host.x() / SECTION_SIZE,
                    host.y() / SECTION_SIZE,
                    host.z() / SECTION_SIZE));
        }

        List<SectionIndex> orderedSections = activeSections.stream()
                .sorted(Comparator.comparingInt(SectionIndex::y)
                        .thenComparingInt(SectionIndex::z)
                        .thenComparingInt(SectionIndex::x))
                .toList();
        List<Section> result = new ArrayList<>(orderedSections.size());
        for (SectionIndex section : orderedSections) {
            int startX = section.x() * SECTION_SIZE;
            int startY = section.y() * SECTION_SIZE;
            int startZ = section.z() * SECTION_SIZE;
            int centerX = center(startX, packed.width());
            int centerY = center(startY, packed.height());
            int centerZ = center(startZ, packed.depth());
            List<BlockPos> candidates = new ArrayList<>(
                    SECTION_SIZE * SECTION_SIZE * SECTION_SIZE);
            for (int y = startY; y < startY + SECTION_SIZE; y++) {
                for (int z = startZ; z < startZ + SECTION_SIZE; z++) {
                    for (int x = startX; x < startX + SECTION_SIZE; x++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (!occupied.contains(candidate)) {
                            candidates.add(candidate);
                        }
                    }
                }
            }
            candidates.sort(Comparator
                    .comparingInt((BlockPos value) -> manhattan(
                            value, centerX, centerY, centerZ))
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getZ));
            result.add(new Section(candidates));
        }
        return List.copyOf(result);
    }

    private static int center(int start, int modelSize) {
        int end = Math.min(start + SECTION_SIZE, modelSize);
        return start + Math.max(0, end - start - 1) / 2;
    }

    private static int manhattan(BlockPos value, int x, int y, int z) {
        return Math.abs(value.getX() - x)
                + Math.abs(value.getY() - y)
                + Math.abs(value.getZ() - z);
    }

    public record Section(List<BlockPos> candidates) {
        public Section {
            candidates = List.copyOf(candidates);
        }
    }

    private record SectionIndex(int x, int y, int z) {
    }
}
