package io.github.yromko.minesplat.compat.chiselsandbits;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChiselsAndBitsIntegrationTest {
    @Test
    void invokesPublicInterfaceMethodOnNonPublicImplementation() throws Exception {
        BatchMutation implementation = new HiddenBatchMutation();
        Method close = BatchMutation.class.getMethod("close");

        ChiselsAndBitsIntegration.invokePublic(close, implementation);

        assertTrue(((HiddenBatchMutation) implementation).closed);
    }

    @Test
    void cachesCenteredBitTargetsForEverySupportedGrid() {
        for (int side : new int[]{1, 2, 4, 8, 16}) {
            CnbBitCenters centers = new CnbBitCenters(side);
            assertEquals(side * side * side, centers.size());

            int x = side - 1;
            int y = side / 2;
            int z = side > 1 ? 1 : 0;
            int localIndex = x + side * (y + side * z);
            Vec3d center = centers.at(localIndex);

            assertSame(center, centers.at(localIndex));
            assertEquals((x + 0.5) / side, center.x);
            assertEquals((y + 0.5) / side, center.y);
            assertEquals((z + 0.5) / side, center.z);
        }
    }

    public interface BatchMutation {
        void close();
    }

    private static final class HiddenBatchMutation implements BatchMutation {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
