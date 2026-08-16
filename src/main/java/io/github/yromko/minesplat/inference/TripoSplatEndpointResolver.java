package io.github.yromko.minesplat.inference;

import io.github.yromko.minesplat.api.ApiModels.ConnectionInfo;
import io.github.yromko.minesplat.api.TripoSplatApiClient;

import java.util.concurrent.CompletableFuture;

public final class TripoSplatEndpointResolver implements AutoCloseable {
    private final LocalRuntimeManager localRuntime;

    public TripoSplatEndpointResolver(LocalRuntimeManager localRuntime) {
        this.localRuntime = localRuntime;
    }

    public LocalRuntimeManager localRuntime() {
        return localRuntime;
    }

    public CompletableFuture<PreparedEndpoint> prepare(InferenceTarget target) {
        return prepare(target, false);
    }

    public CompletableFuture<PreparedEndpoint> prepare(
            InferenceTarget target,
            boolean requireTextModels
    ) {
        if (target instanceof InferenceTarget.Remote remote) {
            try {
                return CompletableFuture.completedFuture(new PreparedEndpoint(
                        remote.identity(), new TripoSplatApiClient(remote.baseUrl()), () -> null));
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        InferenceTarget.Local local = (InferenceTarget.Local) target;
        if (localRuntime == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Local inference runtime is unavailable"));
        }
        return localRuntime.prepare(local.deviceIndex(), requireTextModels).thenApply(endpoint ->
                new PreparedEndpoint(
                        local.identity(),
                        new TripoSplatApiClient(endpoint.baseUrl()),
                        localRuntime::failureMessage));
    }

    public CompletableFuture<ConnectionInfo> test(InferenceTarget target) {
        return prepare(target).thenCompose(endpoint -> endpoint.api().testConnection());
    }

    public void stopLocal() {
        if (localRuntime != null) {
            localRuntime.stop();
        }
    }

    @Override
    public void close() {
        if (localRuntime != null) {
            localRuntime.close();
        }
    }
}
