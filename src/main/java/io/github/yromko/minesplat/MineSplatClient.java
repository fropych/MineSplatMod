package io.github.yromko.minesplat;

import fi.dy.masa.malilib.event.InputEventHandler;
import io.github.yromko.minesplat.client.MineSplatDraft;
import io.github.yromko.minesplat.client.MineSplatHotkeys;
import io.github.yromko.minesplat.config.MineSplatConfig;
import io.github.yromko.minesplat.gui.MineSplatScreen;
import io.github.yromko.minesplat.litematica.LitematicaBridge;
import io.github.yromko.minesplat.palette.BlockPalette;
import io.github.yromko.minesplat.workflow.MineSplatController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.concurrent.ForkJoinPool;

public final class MineSplatClient implements ClientModInitializer {
    private static MineSplatConfig config;
    private static BlockPalette palette;
    private static MineSplatController controller;
    private static final MineSplatDraft DRAFT = new MineSplatDraft();
    private static MineSplatHotkeys hotkeys;

    @Override
    public void onInitializeClient() {
        config = MineSplatConfig.load();
        palette = BlockPalette.loadDefault();
        MinecraftClient client = MinecraftClient.getInstance();
        controller = new MineSplatController(
                palette,
                new LitematicaBridge(client, ForkJoinPool.commonPool()));

        hotkeys = new MineSplatHotkeys(MineSplatClient::open);
        InputEventHandler.getKeybindManager().registerKeybindProvider(hotkeys);
        InputEventHandler.getKeybindManager().updateUsedKeys();
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> controller.close());
    }

    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        open(client.currentScreen);
    }

    public static Screen createScreen(Screen parent) {
        requireInitialized();
        return new MineSplatScreen(parent, config, palette, controller, DRAFT);
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
        if (config == null || palette == null || controller == null) {
            throw new IllegalStateException("MineSplat client has not initialized");
        }
    }
}
