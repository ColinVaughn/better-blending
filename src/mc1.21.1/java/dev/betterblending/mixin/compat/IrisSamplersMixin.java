package dev.betterblending.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.VanillaTexture;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.pipeline.programs.SodiumShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.opengl.GL33C.*;

@Pseudo
@Mixin(value = SodiumShader.class, remap = false)
public abstract class IrisSamplersMixin {
    @Shadow @Final private float alphaTest;

    @Inject(method = "setupState", at = @At("TAIL"))
    private void betterBlending$cutout(CallbackInfo ci) {
        glUniform1f(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "bb_AlphaCutoff"), alphaTest);
    }

    @ModifyExpressionValue(method = "buildSamplers", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/ProgramSamplers;builder(ILjava/util/Set;)Lnet/irisshaders/iris/gl/program/ProgramSamplers$Builder;"))
    private ProgramSamplers.Builder betterBlending$samplers(ProgramSamplers.Builder builder) {
        builder.addExternalSampler(0, "bb_Sampler0");
        String[] names = {"VolumeSampler", "BiomeSampler", "SurfaceColors", "MaterialSampler", "NoiseSampler"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            builder.addDynamicSampler(() -> {
                var texture = TerrainShader.rendererTexture(index);
                return texture == null ? 0 : VanillaTexture.glId(texture);
            }, "bb_" + names[i]);
        }
        return builder;
    }
}
