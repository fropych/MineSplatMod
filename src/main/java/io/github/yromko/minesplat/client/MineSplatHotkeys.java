package io.github.yromko.minesplat.client;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import net.minecraft.client.MinecraftClient;

import java.util.List;

public final class MineSplatHotkeys implements IKeybindProvider {
    private static final KeybindSettings OPEN_SETTINGS = KeybindSettings.create(
            KeybindSettings.Context.ANY,
            KeyAction.PRESS,
            false,
            true,
            false,
            true);

    private final ConfigHotkey open = new ConfigHotkey(
            "openMineSplat",
            "M,G",
            OPEN_SETTINGS,
            "Open the MineSplat image-to-schematic screen",
            "Open MineSplat");

    public MineSplatHotkeys(Runnable callback) {
        open.getKeybind().setCallback((action, keybind) -> {
            MinecraftClient.getInstance().execute(callback);
            return true;
        });
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        manager.addKeybindToMap(open.getKeybind());
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory("MineSplat", "MineSplat", List.of(open));
    }
}
