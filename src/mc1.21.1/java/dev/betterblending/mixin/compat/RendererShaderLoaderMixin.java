package dev.betterblending.mixin.compat;

import dev.betterblending.compat.RendererShaders;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/*
 Sodium 0.6 and Embeddium 1.0 keep their terrain shaders under their own namespaces, and
 addons load theirs through the same loader, so the name is checked whole: a terrain shader
 that is not the renderer's own is not ours to rewrite.
 */
@Pseudo
@Mixin(targets = {"net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader",
        "org.embeddedt.embeddium.impl.gl.shader.ShaderLoader"}, remap = false)
public abstract class RendererShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void betterBlending$shader(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        if (RendererShaders.patches(name.getNamespace(), name.getPath(), cir.getReturnValue())) {
            cir.setReturnValue(RendererShaders.patch(name.getPath(), cir.getReturnValue()));
        }
    }
}
