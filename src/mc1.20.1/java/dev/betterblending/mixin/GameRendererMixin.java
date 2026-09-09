package dev.betterblending.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.VanillaProgram;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "resetData", at = @At("HEAD"))
    private void betterBlending$disconnect(CallbackInfo ci) {
        TerrainShader.clear();
    }

    @Inject(method = "reloadShaders", at = @At("HEAD"))
    private void betterBlending$reload(CallbackInfo ci) {
        TerrainShader.setProgram(null);
    }

    // Before 1.21 the level render takes a pose stack and partial tick rather than a DeltaTracker.
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"))
    private void betterBlending$shadeTerrain(LevelRenderer renderer, PoseStack pose, float partialTick, long nanos,
                                             boolean outline, Camera camera, GameRenderer gameRenderer,
                                             LightTexture lightTexture, Matrix4f projection, Operation<Void> original) {
        try {
            TerrainShader.begin(Minecraft.getInstance(), camera);
            original.call(renderer, pose, partialTick, nanos, outline, camera, gameRenderer, lightTexture, projection);
        } finally {
            TerrainShader.end();
        }
    }

    @Inject(method = "getRendertypeSolidShader", at = @At("HEAD"), cancellable = true)
    private static void betterBlending$blendTerrain(CallbackInfoReturnable<ShaderInstance> cir) {
        ShaderInstance shader = VanillaProgram.unwrap(TerrainShader.currentProgram());
        if (shader != null) cir.setReturnValue(shader);
    }

    @Inject(method = "getRendertypeCutoutMippedShader", at = @At("HEAD"), cancellable = true)
    private static void betterBlending$blendMippedTerrain(CallbackInfoReturnable<ShaderInstance> cir) {
        ShaderInstance shader = VanillaProgram.unwrap(TerrainShader.currentProgram(0.5F));
        if (shader != null) cir.setReturnValue(shader);
    }

    @Inject(method = "getRendertypeCutoutShader", at = @At("HEAD"), cancellable = true)
    private static void betterBlending$blendCutoutTerrain(CallbackInfoReturnable<ShaderInstance> cir) {
        ShaderInstance shader = VanillaProgram.unwrap(TerrainShader.currentProgram(0.1F));
        if (shader != null) cir.setReturnValue(shader);
    }
}
