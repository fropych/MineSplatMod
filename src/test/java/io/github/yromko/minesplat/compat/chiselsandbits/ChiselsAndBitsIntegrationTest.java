package io.github.yromko.minesplat.compat.chiselsandbits;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChiselsAndBitsIntegrationTest {
    @Test
    void invokesPublicInterfaceMethodOnNonPublicImplementation() throws Exception {
        BatchMutation implementation = new HiddenBatchMutation();
        Method close = BatchMutation.class.getMethod("close");

        ChiselsAndBitsIntegration.invokePublic(close, implementation);

        assertTrue(((HiddenBatchMutation) implementation).closed);
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
