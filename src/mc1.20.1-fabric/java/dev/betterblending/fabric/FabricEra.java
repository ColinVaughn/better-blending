package dev.betterblending.fabric;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.betterblending.BlendingConfig;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.Backend;
import dev.betterblending.backend.VanillaBackend;
import dev.betterblending.backend.VanillaProgram;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.resources.ResourceLocation;

/** Fabric setup that differs by Minecraft era. Before 1.21.5 that is a core shader to register. */
final class FabricEra {
    private FabricEra() {
    }

    static void initialize() {
        Backend.install(new VanillaBackend());
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            try {
                // ResourceLocation.fromNamespaceAndPath only arrived in 1.21.
                context.register(new ResourceLocation("better_blending", "terrain"),
                        DefaultVertexFormat.BLOCK, shader -> TerrainShader.setProgram(new VanillaProgram(shader)));
            } catch (java.io.IOException exception) {
                TerrainShader.setProgram(null);
                BlendingConfig.LOGGER.warn("Terrain blending unavailable; using ordinary terrain", exception);
            }
        });
    }
}
