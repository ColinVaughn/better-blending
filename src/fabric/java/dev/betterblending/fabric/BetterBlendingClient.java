package dev.betterblending.fabric;

import dev.betterblending.BlendingConfig;
import dev.betterblending.TerrainShader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class BetterBlendingClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricEra.initialize();
        BlendingConfig.load(FabricLoader.getInstance().getConfigDir());
        dev.betterblending.compat.IrisCompatibility.initialize(FabricLoader.getInstance().isModLoaded("iris"));
        TerrainShader.alternateRenderer = FabricLoader.getInstance().isModLoaded("sodium");
        dev.betterblending.MixinAudit.runIfRequested();
    }
}
