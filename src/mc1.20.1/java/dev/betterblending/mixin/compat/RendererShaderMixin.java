package dev.betterblending.mixin.compat;

import dev.betterblending.TerrainShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer", remap = false)
public abstract class RendererShaderMixin {
    @Inject(method = "begin", at = @At("TAIL"))
    private void betterBlending$bind(CallbackInfo ci) { TerrainShader.bindRenderer(); }

    @Inject(method = "end", at = @At("HEAD"))
    private void betterBlending$restore(CallbackInfo ci) { TerrainShader.unbindRenderer(); }
}
