package io.github.yromko.minesplat.cnb;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class CnbIntegrationLoader {
    public static final String SUPPORTED_VERSION = "21.1.33";
    private static final String IMPLEMENTATION =
            "io.github.yromko.minesplat.compat.chiselsandbits.ChiselsAndBitsIntegration";

    private CnbIntegrationLoader() {
    }

    public static CnbIntegration load() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("chiselsandbits")) {
            return new UnavailableCnbIntegration(
                    "Chisels & Bits 21.1.33 is not installed");
        }
        ModContainer container = loader.getModContainer("chiselsandbits").orElse(null);
        String installed = container == null
                ? "unknown"
                : container.getMetadata().getVersion().getFriendlyString();
        if (!SUPPORTED_VERSION.equals(installed)) {
            return new UnavailableCnbIntegration(
                    "Unsupported Chisels & Bits version " + installed
                            + "; install " + SUPPORTED_VERSION);
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
