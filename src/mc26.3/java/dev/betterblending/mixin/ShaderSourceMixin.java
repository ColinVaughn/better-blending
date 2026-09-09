package dev.betterblending.mixin;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.betterblending.compat.SodiumShaders;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderManager.Configs.class)
public abstract class ShaderSourceMixin {
    // The loaded shader sources, which every pipeline compile asks for its stages. The
    // blended copy of Sodium's shader has no file of its own: it is Sodium's loaded
    // source, patched on request.
    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
    private void betterBlending$rendererShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        if (!id.equals(SodiumShaders.BLENDED)) return;
        String source = ((ShaderSource) (Object) this).getShader(SodiumShaders.SODIUM, type);
        cir.setReturnValue(source == null ? null : SodiumShaders.patch(type, source));
    }
}
