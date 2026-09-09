package dev.betterblending.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.ModernProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = {
        "net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawSeparate",
        "net.minecraft.client.renderer.chunk.ChunkSectionsToRender$DrawIndirect"})
public abstract class ChunkSectionsToRenderMixin {
    // Each draw path looks up its layer's compiled pipeline and sets it on the pass that
    // renderGroup opened. Solid and cutout terrain get our pipeline instead, plus the
    // blending cache bound alongside the atlas and lightmap vanilla has already bound.
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private CompiledRenderPipeline betterBlending$blend(RenderPipeline pipeline, Operation<CompiledRenderPipeline> original,
            @Share("blend") LocalBooleanRef blend) {
        if (TerrainShader.currentProgram() instanceof ModernProgram program) {
            var replacement = program.replace(pipeline);
            if (replacement != null) {
                blend.set(true);
                return original.call(replacement);
            }
        }
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
