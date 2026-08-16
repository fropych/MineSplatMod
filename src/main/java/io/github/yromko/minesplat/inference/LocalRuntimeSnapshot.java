package io.github.yromko.minesplat.inference;

public record LocalRuntimeSnapshot(
        LocalRuntimeState state,
        String platform,
        String message,
        String error,
        String device,
        int deviceIndex,
        String baseUrl
) {
    public static LocalRuntimeSnapshot stopped(String platform) {
        return new LocalRuntimeSnapshot(
                LocalRuntimeState.STOPPED, platform, "Local runtime stopped",
                null, null, 0, null);
    }
}
