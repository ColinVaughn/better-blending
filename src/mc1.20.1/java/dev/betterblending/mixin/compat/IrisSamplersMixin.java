package dev.betterblending.mixin.compat;

import dev.betterblending.TerrainShader;
import dev.betterblending.backend.TerrainUniforms;
import dev.betterblending.backend.VanillaTexture;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.samplers.IrisSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Set;

@Pseudo
@Mixin(value = ProgramSamplers.class, remap = false)
public abstract class IrisSamplersMixin {
    // Iris 1.7 builds a terrain program's samplers in a lambda, so they are offered to
    // every program here instead. Only programs IrisShaders patched declare these names,
    // and Iris gives no texture unit to a sampler a program lacks. The pack's atlas
    // sampler is external on the albedo unit, and blending's reads the same texture.
    @Inject(method = "builder", at = @At("RETURN"))
    private static void betterBlending$samplers(int program, Set<Integer> reserved,
            CallbackInfoReturnable<ProgramSamplers.Builder> cir) {
        var builder = cir.getReturnValue();
        builder.addExternalSampler(IrisSamplers.ALBEDO_TEXTURE_UNIT, "bb_Sampler0");
        for (int i = 0; i < TerrainUniforms.SAMPLERS.length; i++) {
            int index = i;
            builder.addDynamicSampler(() -> {
                var texture = TerrainShader.rendererTexture(index);
                return texture == null ? 0 : VanillaTexture.glId(texture);
            }, "bb_" + TerrainUniforms.SAMPLERS[i]);
        }
    }
}
