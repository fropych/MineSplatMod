package io.github.yromko.minesplat.inference;

import io.github.yromko.minesplat.api.TripoSplatApiClient;

public sealed interface InferenceTarget
        permits InferenceTarget.Remote, InferenceTarget.Local {
    String identity();

    record Remote(String baseUrl) implements InferenceTarget {
        public Remote {
            baseUrl = TripoSplatApiClient.normalizeBaseUrl(baseUrl);
        }

        @Override
        public String identity() {
            return "remote:" + baseUrl;
        }
    }

    record Local(int deviceIndex) implements InferenceTarget {
        public Local {
            if (deviceIndex < 0) {
                throw new IllegalArgumentException("Vulkan device index must be non-negative");
            }
        }

        @Override
        public String identity() {
            return "local:" + TripoSplatRuntimeVersion.SOURCE_COMMIT + ":" + deviceIndex;
        }
    }
}
