package dev.betterblending.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.ModernProgram;
import dev.betterblending.backend.ModernSmokeTest;
import dev.betterblending.compat.IrisCompatibility;
import dev.betterblending.compat.IrisSmokeTest;
import dev.betterblending.compat.SodiumShaders;
import dev.betterblending.compat.SodiumSmokeTest;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    @Shadow public abstract String getShader(Identifier id, ShaderType type);

    // Every pipeline compile asks here for its sources. The blended copy of Sodium's
    // shader has no file of its own: it is Sodium's loaded source, patched on request.
    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
    private void betterBlending$rendererShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        if (!id.equals(SodiumShaders.BLENDED)) return;
        String source = getShader(SodiumShaders.SODIUM, type);
        cir.setReturnValue(source == null ? null : SodiumShaders.patch(type, source));
    }

    // Vanilla precompiles its own pipelines at the end of this method, once every shader
    // source is loaded. Ours go through the same device at the same point.
    @Inject(method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("TAIL"))
    private void betterBlending$precompile(CallbackInfo ci) {
        if (!(TerrainShader.program() instanceof ModernProgram program)) return;
        boolean compiled = program.precompile();
        if (Boolean.getBoolean("better_blending.smokeTest")) {
            // Sodium is the only alternate renderer on this era; Iris requires it.
            boolean renderer = !TerrainShader.alternateRenderer || SodiumSmokeTest.run(program);
            if (IrisCompatibility.installed()) renderer &= IrisSmokeTest.run();
            ModernSmokeTest.run(program, compiled, renderer);
        }
    }
}
