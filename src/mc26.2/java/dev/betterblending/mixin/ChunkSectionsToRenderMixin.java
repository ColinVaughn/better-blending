package dev.betterblending.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.ModernProgram;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    // renderGroup opens one pass and sets each layer's pipeline on it in turn. Solid and
    // cutout terrain get our pipeline instead, plus the blending cache bound alongside
    // the atlas and lightmap vanilla has already bound.
    @WrapOperation(method = "renderGroup", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void betterBlending$blend(RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
        if (TerrainShader.currentProgram() instanceof ModernProgram program) {
            var replacement = program.replace(pipeline);
            if (replacement != null) {
                original.call(pass, replacement);
                program.bind(pass);
                return;
            }
        }
        original.call(pass, pipeline);
    }
}
