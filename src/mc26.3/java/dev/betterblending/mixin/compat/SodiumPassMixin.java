package dev.betterblending.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.ModernProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class SodiumPassMixin {
    // Sodium looks up its active pipeline's compiled form and sets it on the pass; when
    // SodiumProgramMixin made that pipeline ours, the blending cache goes on the same pass.
    // Sodium binds its atlas, lightmap and uniforms after this.
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private CompiledRenderPipeline betterBlending$ours(RenderPipeline pipeline, Operation<CompiledRenderPipeline> original,
            @Share("blend") LocalBooleanRef blend) {
        blend.set(TerrainShader.program() instanceof ModernProgram program && program.owns(pipeline));
        return original.call(pipeline);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
    private void betterBlending$bind(RenderPass pass, CompiledRenderPipeline pipeline, Operation<Void> original,
            @Share("blend") LocalBooleanRef blend) {
        original.call(pass, pipeline);
        if (blend.get() && TerrainShader.program() instanceof ModernProgram program) program.bind(pass);
    }
}
