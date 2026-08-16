package io.github.yromko.minesplat.inference;

import io.github.yromko.minesplat.api.TripoSplatApiClient;

import java.util.function.Supplier;

public record PreparedEndpoint(
        String identity,
        TripoSplatApiClient api,
        Supplier<String> runtimeFailure
) {
    public PreparedEndpoint {
        if (identity == null || identity.isBlank() || api == null) {
            throw new IllegalArgumentException("Prepared endpoint is incomplete");
        }
        runtimeFailure = runtimeFailure == null ? () -> null : runtimeFailure;
    }
}
