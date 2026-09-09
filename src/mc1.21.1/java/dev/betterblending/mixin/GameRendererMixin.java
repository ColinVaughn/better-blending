/*
 Copyright (c) 2026 Colin Vaughn

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.
 */

package dev.betterblending.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.VanillaProgram;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
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

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void betterBlending$shadeTerrain(LevelRenderer renderer, DeltaTracker delta, boolean outline,
                                             Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                                             Matrix4f view, Matrix4f projection, Operation<Void> original) {
        try {
            TerrainShader.begin(Minecraft.getInstance(), camera);
            original.call(renderer, delta, outline, camera, gameRenderer, lightTexture, view, projection);
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
