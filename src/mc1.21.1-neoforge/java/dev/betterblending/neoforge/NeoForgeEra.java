package dev.betterblending.neoforge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.betterblending.BlendingConfig;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.Backend;
import dev.betterblending.backend.VanillaBackend;
import dev.betterblending.backend.VanillaProgram;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/** NeoForge setup that differs by Minecraft era. Before 1.21.5 that is a core shader to register. */
final class NeoForgeEra {
    private NeoForgeEra() {
    }

    static void initialize(IEventBus modBus) {
        Backend.install(new VanillaBackend());
        modBus.addListener(NeoForgeEra::registerShaders);
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath("better_blending", "terrain"), DefaultVertexFormat.BLOCK),
                    shader -> TerrainShader.setProgram(new VanillaProgram(shader)));
        } catch (java.io.IOException exception) {
            TerrainShader.setProgram(null);
            BlendingConfig.LOGGER.warn("Terrain blending unavailable; using ordinary terrain", exception);
        }
    }
}
