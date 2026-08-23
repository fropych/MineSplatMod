package io.github.yromko.minesplat.cnb;

import io.github.yromko.minesplat.internal.TargetMetadata;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class CnbIntegrationLoader {
    private static final String IMPLEMENTATION =
            "io.github.yromko.minesplat.compat.chiselsandbits.ChiselsAndBitsIntegration";

    private CnbIntegrationLoader() {
    }

    public static CnbIntegration load() {
        String supportedVersion = TargetMetadata.current().chiselsAndBitsVersion();
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("chiselsandbits")) {
            return new UnavailableCnbIntegration(
                    "Chisels & Bits " + supportedVersion + " is not installed");
        }
        ModContainer container = loader.getModContainer("chiselsandbits").orElse(null);
        String installed = container == null
                ? "unknown"
                : container.getMetadata().getVersion().getFriendlyString();
        if (!supportedVersion.equals(installed)) {
            return new UnavailableCnbIntegration(
                    "Unsupported Chisels & Bits version " + installed
                            + "; install " + supportedVersion);
        }
        try {
            return (CnbIntegration) Class.forName(IMPLEMENTATION)
                    .getConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            return new UnavailableCnbIntegration(
                    "Cannot initialize Chisels & Bits integration: "
                            + exception.getClass().getSimpleName());
        }
    }
}
