package dev.betterblending.forge;

import dev.betterblending.BlendingConfig;
import dev.betterblending.BlendingConfigScreen;
import dev.betterblending.TerrainShader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("better_blending")
public final class BetterBlendingClient {
    public BetterBlendingClient() {
        // Client-only: a dedicated server that loads the jar anyway must not touch rendering.
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ForgeEra.initialize(modBus);
        BlendingConfig.load(FMLPaths.CONFIGDIR.get());
        // On Forge, Iris ships as Oculus and Sodium as Embeddium or Rubidium.
        dev.betterblending.compat.IrisCompatibility.initialize(ModList.get().isLoaded("oculus"));
        TerrainShader.alternateRenderer = ModList.get().isLoaded("embeddium") || ModList.get().isLoaded("rubidium");
        if (ModList.get().isLoaded("cloth_config")) {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> BlendingConfigScreen.create(parent)));
        }
        // Mods construct in parallel, and auditing loads other mods' mixin targets, whose
        // static initialisers may read configs their mods have not loaded yet.
        modBus.addListener((FMLLoadCompleteEvent event) -> dev.betterblending.MixinAudit.runIfRequested());
    }
}
