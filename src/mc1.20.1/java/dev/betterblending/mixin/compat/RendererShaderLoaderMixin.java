package dev.betterblending.mixin.compat;

import dev.betterblending.compat.RendererShaders;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/*
 Sodium 0.5, Embeddium 0.3 and Rubidium 0.7 all load terrain shaders here, from the sodium
 namespace. Addons load their own shaders through the same loader, so the name is checked
 whole: a terrain shader that is not the renderer's own is not ours to rewrite.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
public abstract class RendererShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void betterBlending$shader(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        if (RendererShaders.patches(name.getNamespace(), name.getPath(), cir.getReturnValue())) {
            cir.setReturnValue(RendererShaders.patch(name.getPath(), cir.getReturnValue()));
        }
    }
}
