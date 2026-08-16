package io.github.yromko.minesplat.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalInferenceMetadataTest {
    private static final long MODEL_BYTES = 3_779_190_284L;
    private static final long TEXT_MODEL_BYTES = 6_696_835_812L;

    @TempDir
    Path temporary;

    @Test
    void normalizesInferenceTargetsAndKeepsBackendsDistinct() {
        InferenceTarget.Remote remote = new InferenceTarget.Remote("example.test:8080/");
        InferenceTarget.Local local = new InferenceTarget.Local(2);

        assertEquals("http://example.test:8080", remote.baseUrl());
        assertEquals("remote:http://example.test:8080", remote.identity());
        assertEquals("local:" + TripoSplatRuntimeVersion.SOURCE_COMMIT + ":2",
                local.identity());
        assertThrows(IllegalArgumentException.class, () -> new InferenceTarget.Local(-1));
    }

    @Test
    void loadsPinnedModelAndRuntimeManifests() throws Exception {
        try (LocalModelManager models = new LocalModelManager(temporary.resolve("models"));
             LocalRuntimeManager runtime = new LocalRuntimeManager(temporary, models)) {
            models.refresh().get(5, TimeUnit.SECONDS);

            assertEquals(LocalModelState.MISSING, models.snapshot().state());
            assertEquals(MODEL_BYTES, models.snapshot().totalBytes());
            assertEquals(0, models.snapshot().downloadedBytes());
            assertEquals(LocalModelState.MISSING, models.textSnapshot().state());
            assertEquals(TEXT_MODEL_BYTES, models.textSnapshot().totalBytes());
            String platform = LocalRuntimeManager.detectPlatform();
            if (platform == null) {
                assertFalse(runtime.supported());
                assertEquals(LocalRuntimeState.UNSUPPORTED, runtime.snapshot().state());
            } else {
                assertTrue(runtime.supported());
                assertEquals(platform, runtime.platform());
                assertEquals(LocalRuntimeState.MISSING_MODELS, runtime.snapshot().state());
            }
        }
    }
}
