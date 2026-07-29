package io.github.yromko.minesplat.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.yromko.minesplat.MineSplatClient;

public final class MineSplatModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MineSplatClient::createScreen;
    }
}
