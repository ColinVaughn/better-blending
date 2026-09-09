package dev.betterblending.mixin;

import dev.betterblending.TerrainShader;
import dev.betterblending.backend.ModernProgram;
import dev.betterblending.backend.ModernSmokeTest;
import dev.betterblending.compat.IrisCompatibility;
import dev.betterblending.compat.IrisSmokeTest;
import dev.betterblending.compat.SodiumSmokeTest;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    // By the end of this method the reload has compiled vanilla's pipelines and installed
    // the pipeline cache that serves the new shader sources. Ours compile into it now.
    @Inject(method = "apply(Lcom/mojang/renderpearl/api/device/GpuDevice;Lnet/minecraft/client/renderer/ShaderManager$PendingResults;)V",
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
