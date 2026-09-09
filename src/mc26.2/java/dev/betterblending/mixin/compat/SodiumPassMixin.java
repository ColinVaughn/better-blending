package dev.betterblending.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.ModernProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class SodiumPassMixin {
    // Sodium binds its atlas, lightmap and uniforms after this; ours go on the same pass.
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void betterBlending$bind(RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
        original.call(pass, pipeline);
        if (TerrainShader.program() instanceof ModernProgram program && program.owns(pipeline)) program.bind(pass);
    }
}
