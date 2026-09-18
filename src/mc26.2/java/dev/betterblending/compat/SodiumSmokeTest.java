package dev.betterblending.compat;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.betterblending.BlendingConfig;
import dev.betterblending.backend.ModernProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;

/**
 The Sodium half of the 26.x GPU smoke test. Asks Sodium for its opaque terrain
 pipelines the way its chunk renderer does, then compiles each one and our blended
 copy of it through the real device, with the shader manager serving the patched source.
 */
public final class SodiumSmokeTest {
    private SodiumSmokeTest() {
    }

    public static boolean run(ModernProgram program) {
        var probe = new Probe();
        boolean passed = true;
        for (var pass : new TerrainRenderPass[]{DefaultTerrainRenderPasses.SOLID, DefaultTerrainRenderPasses.CUTOUT}) {
            var sodium = probe.pipeline(pass);
            boolean own = RenderSystem.getDevice().precompilePipeline(sodium).isValid();
            boolean blended = program.replaceRenderer(sodium, SodiumShaders.BLENDED) != null;
            BlendingConfig.LOGGER.info("Sodium pipeline {}: Sodium's compiled={}, blended copy compiled={}",
                    sodium.getLocation(), own, blended);
            passed &= own && blended;
        }
        return passed;
    }

    /* Reaches Sodium's pipeline factory. Never draws; the smoke test exits first. */
    private static final class Probe extends ShaderChunkRenderer {
        Probe() {
            super(ChunkMeshFormats.COMPACT);
        }

        RenderPipeline pipeline(TerrainRenderPass pass) {
            return compileProgram(pass);
        }

        @Override
        public void render(ChunkRenderMatrices matrices, ChunkRenderListIterable lists, TerrainRenderPass pass,
                CameraTransform camera, FogParameters fog, boolean sortTranslucent, GpuSampler sampler,
                GpuBufferSlice globals, GpuBuffer sectionTimes) {
            throw new UnsupportedOperationException("probe only");
        }

        @Override
        public void rotate() {
            // Nothing to rotate: the probe owns no per-frame buffers.
        }
    }
}
