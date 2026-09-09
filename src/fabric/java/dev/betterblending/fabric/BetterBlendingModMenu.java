package dev.betterblending.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.betterblending.BlendingConfigScreen;
import net.fabricmc.loader.api.FabricLoader;

public final class BetterBlendingModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) return parent -> null;
        return BlendingConfigScreen::create;
    }
}
