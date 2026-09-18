package dev.betterblending.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlTexture;
import dev.betterblending.TerrainShader;
import dev.betterblending.backend.ModernTexture;
import dev.betterblending.backend.TerrainUniforms;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.opengl.GL33C.*;

@Pseudo
@Mixin(value = ExtendedShader.class, remap = false)
public abstract class IrisSamplersMixin {
    @Shadow @Final private float alphaTest;
    /* bb_Enabled, bb_AlphaCutoff, then each mirrored uniform; resolved on first use. */
    @Unique private int[] betterBlending$locations;

    // Every pack program is offered these; only programs IrisShaders patched declare the
    // names, and Iris gives no texture unit to a sampler the program lacks.
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/ProgramSamplers;builder(ILjava/util/Set;)Lnet/irisshaders/iris/gl/program/ProgramSamplers$Builder;"))
    private ProgramSamplers.Builder betterBlending$samplers(ProgramSamplers.Builder builder) {
        // The atlas, sampled as terrain samples it. Sodium binds it for the pack under the
        // pack's own name, which blending's module cannot declare a second time.
        builder.addDynamicSampler(TextureType.TEXTURE_2D, IrisSamplersMixin::betterBlending$atlas,
                () -> GlSampler.MIPPED_NEAREST, "bb_Sampler0");
        for (int i = 0; i < TerrainUniforms.SAMPLERS.length; i++) {
            int index = i;
            builder.addDynamicSampler(TextureType.TEXTURE_2D, () -> betterBlending$cache(index),
                    () -> betterBlending$filter(index), "bb_" + TerrainUniforms.SAMPLERS[i]);
        }
        return builder;
    }

    // Iris has just made this program current and set its own uniforms.
    @Inject(method = "iris$setupState", at = @At("TAIL"))
    private void betterBlending$uniforms(CallbackInfo ci) {
        var locations = betterBlending$resolveLocations();
        if (locations[0] < 0) return; // Not a program blending patched.
        boolean enabled = TerrainShader.rendererReady();
        glUniform1i(locations[0], enabled ? 1 : 0);
        glUniform1f(locations[1], alphaTest);
        if (!enabled) return;
        var program = TerrainShader.program();
        for (int i = 0; i < TerrainUniforms.MIRRORED.length; i++) {
            float[] values = program.uniformValues(TerrainUniforms.MIRRORED[i]);
            if (values != null && locations[2 + i] >= 0) betterBlending$uniform(locations[2 + i], values);
        }
    }

    @Unique
    private int[] betterBlending$resolveLocations() {
        if (betterBlending$locations == null) {
            int program = ((GlProgram) (Object) this).getProgramId();
            var locations = new int[2 + TerrainUniforms.MIRRORED.length];
            locations[0] = glGetUniformLocation(program, "bb_Enabled");
            locations[1] = glGetUniformLocation(program, "bb_AlphaCutoff");
            for (int i = 0; i < TerrainUniforms.MIRRORED.length; i++) {
                locations[2 + i] = glGetUniformLocation(program, "bb_" + TerrainUniforms.MIRRORED[i]);
            }
            betterBlending$locations = locations;
        }
        return betterBlending$locations;
    }

    @Unique
    private static void betterBlending$uniform(int location, float[] values) {
        switch (values.length) {
            case 1 -> glUniform1f(location, values[0]);
            case 2 -> glUniform2f(location, values[0], values[1]);
            case 3 -> glUniform3f(location, values[0], values[1], values[2]);
            default -> throw new IllegalStateException("Unexpected uniform size " + values.length);
        }
    }

    @Unique
    private static int betterBlending$atlas() {
        var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        return ((GlTexture) atlas.getTexture()).glId();
    }

    /* One of the cache's textures, or none while this frame's cache is not uploaded. */
    @Unique
    private static int betterBlending$cache(int index) {
        var texture = TerrainShader.rendererTexture(index);
        return texture == null ? 0 : ((GlTexture) ModernTexture.texture(texture)).glId();
    }

    @Unique
    private static GlSampler betterBlending$filter(int index) {
        var texture = TerrainShader.rendererTexture(index);
        return texture != null && ModernTexture.linear(texture) ? GlSampler.LINEAR_REPEAT : GlSampler.NEAREST_REPEAT;
    }
}
