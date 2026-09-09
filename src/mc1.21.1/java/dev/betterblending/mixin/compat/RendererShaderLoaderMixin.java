package dev.betterblending.mixin.compat;

import dev.betterblending.compat.RendererShaders;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader",
        "org.embeddedt.embeddium.impl.gl.shader.ShaderLoader"}, remap = false)
public abstract class RendererShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void betterBlending$shader(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        if (name.getPath().equals("blocks/block_layer_opaque.vsh") || name.getPath().equals("blocks/block_layer_opaque.fsh")) {
            cir.setReturnValue(RendererShaders.patch(name.getPath(), cir.getReturnValue()));
        }
    }
}
