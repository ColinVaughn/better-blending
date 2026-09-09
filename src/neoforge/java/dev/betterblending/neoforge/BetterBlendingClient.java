package dev.betterblending.neoforge;

import dev.betterblending.BlendingConfig;
import dev.betterblending.BlendingConfigScreen;
import dev.betterblending.TerrainShader;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.fml.loading.FMLPaths;

@Mod(value = "better_blending", dist = Dist.CLIENT)
public final class BetterBlendingClient {
    public BetterBlendingClient(IEventBus modBus, ModContainer container) {
        NeoForgeEra.initialize(modBus);
        BlendingConfig.load(FMLPaths.CONFIGDIR.get());
        dev.betterblending.compat.IrisCompatibility.initialize(ModList.get().isLoaded("iris"));
        TerrainShader.alternateRenderer = ModList.get().isLoaded("sodium") || ModList.get().isLoaded("embeddium");
        if (ModList.get().isLoaded("cloth_config")) {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (minecraft, parent) -> BlendingConfigScreen.create(parent));
        }
        // Mods construct in parallel, and auditing loads other mods' mixin targets, whose
        // static initialisers may read configs their mods have not loaded yet.
        modBus.addListener(FMLLoadCompleteEvent.class, event -> dev.betterblending.MixinAudit.runIfRequested());
    }
}
