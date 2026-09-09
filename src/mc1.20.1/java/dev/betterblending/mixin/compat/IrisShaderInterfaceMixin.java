package dev.betterblending.mixin.compat;

import dev.betterblending.TerrainShader;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkShaderInterface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.opengl.GL33C.*;

@Pseudo
@Mixin(value = IrisChunkShaderInterface.class, remap = false)
public abstract class IrisShaderInterfaceMixin {
    @Shadow @Final private float alpha;

    // With a pack active, Iris 1.7 binds its program and sets it up here, then cancels
    // Sodium's begin, where blending otherwise sets its uniforms. They follow it here.
    @Inject(method = "setupState", at = @At("TAIL"))
    private void betterBlending$uniforms(CallbackInfo ci) {
        glUniform1f(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "bb_AlphaCutoff"), alpha);
        TerrainShader.bindRenderer();
    }
}
