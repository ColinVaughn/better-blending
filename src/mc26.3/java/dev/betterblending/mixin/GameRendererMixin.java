package dev.betterblending.mixin;

import dev.betterblending.TerrainShader;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "resetData", at = @At("HEAD"))
    private void betterBlending$disconnect(CallbackInfo ci) {
        TerrainShader.clear();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void betterBlending$end(CallbackInfo ci) {
        TerrainShader.end();
    }
}
