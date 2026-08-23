package io.github.yromko.minesplat;

import fi.dy.masa.malilib.event.InputEventHandler;
import io.github.yromko.minesplat.client.MineSplatDraft;
import io.github.yromko.minesplat.client.MineSplatHotkeys;
import io.github.yromko.minesplat.cnb.CnbBlueprintExporter;
import io.github.yromko.minesplat.cnb.CnbBlueprintStore;
import io.github.yromko.minesplat.cnb.CnbIntegration;
import io.github.yromko.minesplat.cnb.CnbIntegrationLoader;
import io.github.yromko.minesplat.cnb.CnbPlacementController;
import io.github.yromko.minesplat.config.MineSplatConfig;
import io.github.yromko.minesplat.gui.MineSplatScreen;
import io.github.yromko.minesplat.inference.LocalModelManager;
import io.github.yromko.minesplat.inference.LocalRuntimeManager;
import io.github.yromko.minesplat.inference.TripoSplatEndpointResolver;
import io.github.yromko.minesplat.inference.TripoSplatRuntimeVersion;
import io.github.yromko.minesplat.litematica.LitematicaBridge;
import io.github.yromko.minesplat.palette.BlockPalette;
import io.github.yromko.minesplat.palette.CustomPaletteStore;
import io.github.yromko.minesplat.workflow.MineSplatController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.concurrent.ForkJoinPool;
import java.nio.file.Path;

public final class MineSplatClient implements ClientModInitializer {
    private static MineSplatConfig config;
    private static BlockPalette palette;
    private static CustomPaletteStore customPalettes;
    private static MineSplatController controller;
    private static CnbIntegration cnbIntegration;
    private static CnbBlueprintStore cnbBlueprints;
    private static CnbPlacementController cnbPlacement;
    private static LocalModelManager localModels;
    private static LocalRuntimeManager localRuntime;
    private static final MineSplatDraft DRAFT = new MineSplatDraft();
    private static MineSplatHotkeys hotkeys;

    @Override
    public void onInitializeClient() {
        config = MineSplatConfig.load();
        palette = BlockPalette.loadDefault();
        customPalettes = CustomPaletteStore.gameStore();
        MinecraftClient client = MinecraftClient.getInstance();
        cnbIntegration = CnbIntegrationLoader.load();
        cnbBlueprints = CnbBlueprintStore.gameStore();
        cnbPlacement = new CnbPlacementController(client, cnbIntegration, cnbBlueprints);
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        Path defaultModels = gameDirectory.resolve("minesplat/models")
                .resolve(TripoSplatRuntimeVersion.MODEL_REVISION);
        Path modelDirectory = defaultModels;
        if (!config.localModelDirectory().isBlank()) {
            try {
                modelDirectory = Path.of(config.localModelDirectory());
            } catch (RuntimeException ignored) {
                config.localModelDirectory("");
            }
        }
        localModels = new LocalModelManager(modelDirectory);
        localRuntime = new LocalRuntimeManager(gameDirectory, localModels);
        controller = new MineSplatController(
                palette,
                new LitematicaBridge(client, ForkJoinPool.commonPool()),
                new CnbBlueprintExporter(
                        cnbBlueprints,
                        () -> client.getSession().getUsername(),
                        ForkJoinPool.commonPool()),
                cnbIntegration,
                new TripoSplatEndpointResolver(localRuntime));

        hotkeys = new MineSplatHotkeys(MineSplatClient::open);
        InputEventHandler.getKeybindManager().registerKeybindProvider(hotkeys);
        InputEventHandler.getKeybindManager().updateUsedKeys();
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> {
            controller.close();
            localModels.close();
            cnbPlacement.close();
        });
    }

    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        open(client.currentScreen);
    }

    public static Screen createScreen(Screen parent) {
        requireInitialized();
        return new MineSplatScreen(
                parent,
                config,
                palette,
                customPalettes,
                controller,
                DRAFT,
                cnbPlacement,
                cnbBlueprints,
                localModels,
                localRuntime);
    }

    public static MineSplatConfig config() {
        requireInitialized();
        return config;
    }

    private static void open(Screen parent) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof MineSplatScreen)) {
            client.setScreen(createScreen(parent));
        }
    }

    private static void requireInitialized() {
        if (config == null || palette == null || customPalettes == null || controller == null
                || cnbPlacement == null || cnbBlueprints == null
                || localModels == null || localRuntime == null) {
            throw new IllegalStateException("MineSplat client has not initialized");
        }
    }
}
