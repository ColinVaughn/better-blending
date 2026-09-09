package dev.betterblending.mixin.compat;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.ModernProgram;
import dev.betterblending.compat.IrisCompatibility;
import dev.betterblending.compat.SodiumShaders;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class SodiumProgramMixin {
    // Sodium asks for its pass's pipeline as each pass begins, then both sets it on the
    // render pass and reads its push constants from it. Answering with our copy here
    // keeps the two in agreement. Order-independent transparency stages, and a pipeline
    // that is not Sodium's own shader, such as a shader pack's, are left alone.
    @Inject(method = "compileProgram", at = @At("RETURN"), cancellable = true)
    private void betterBlending$blend(TerrainRenderPass pass, OitStage stage, CallbackInfoReturnable<RenderPipeline> cir) {
        var pipeline = cir.getReturnValue();
        if (pass.isTranslucent() || stage != null || pipeline == null
                || !SodiumShaders.SODIUM.equals(pipeline.getShaders().get(ShaderType.VERTEX))
                || !TerrainShader.rendererReady() || IrisCompatibility.shaderPackActive()
                || !(TerrainShader.program() instanceof ModernProgram program)) return;
        var replacement = program.replaceRenderer(pipeline, SodiumShaders.BLENDED);
        if (replacement != null) cir.setReturnValue(replacement);
    }
}
