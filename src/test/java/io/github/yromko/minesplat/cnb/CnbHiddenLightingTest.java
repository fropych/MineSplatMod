package io.github.yromko.minesplat.cnb;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CnbHiddenLightingTest {
    @Test
    void plansOneLightSectionForEachOccupiedFiveBlockCube() {
        CnbPackedModel packed = model(
                6, 5, 5,
                host(0, 0, 0),
                host(4, 4, 4),
                host(5, 0, 0));

        List<CnbHiddenLighting.Section> sections = CnbHiddenLighting.plan(packed);

        assertEquals(2, sections.size());
        assertEquals(new BlockPos(2, 2, 2), sections.get(0).candidates().get(0));
        assertEquals(new BlockPos(5, 2, 2), sections.get(1).candidates().get(0));
    }

    @Test
    void searchesNearestFreePositionWhenSectionCenterIsOccupied() {
        CnbPackedModel packed = model(5, 5, 5, host(2, 2, 2));

        CnbHiddenLighting.Section section = CnbHiddenLighting.plan(packed).get(0);

        assertFalse(section.candidates().contains(new BlockPos(2, 2, 2)));
        assertEquals(1, manhattan(section.candidates().get(0), new BlockPos(2, 2, 2)));
    }

    @Test
    void tinyOccupiedModelStillOffersNearbyAirOutsideItsBounds() {
        CnbPackedModel packed = model(1, 1, 1, host(0, 0, 0));

        List<BlockPos> candidates = CnbHiddenLighting.plan(packed)
                .get(0)
                .candidates();

        assertEquals(124, candidates.size());
        assertTrue(candidates.stream().noneMatch(new BlockPos(0, 0, 0)::equals));
        assertEquals(1, manhattan(candidates.get(0), new BlockPos(0, 0, 0)));
    }

    private static CnbPackedModel model(
            int width,
            int height,
            int depth,
            CnbPackedModel.HostBlock... hosts
    ) {
        return new CnbPackedModel(16, width, height, depth, List.of(hosts), hosts.length);
    }

    private static CnbPackedModel.HostBlock host(int x, int y, int z) {
        return new CnbPackedModel.HostBlock(x, y, z, new int[]{0}, new int[]{0});
    }

    private static int manhattan(BlockPos left, BlockPos right) {
        return Math.abs(left.getX() - right.getX())
                + Math.abs(left.getY() - right.getY())
                + Math.abs(left.getZ() - right.getZ());
    }
}
