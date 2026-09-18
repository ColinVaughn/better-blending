package dev.betterblending.mixin;

import dev.betterblending.BlendingConfig;
import dev.betterblending.compat.IrisCompatibility;
import dev.betterblending.compat.IrisSmokeTest;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 -PsmokeTest on 1.20.1. The GL-era shaders are covered by the GPU tests on the
 primary node; what those cannot reach is Iris's own transform, so with Iris installed
 this links a pack program through it, then exits. The GL context is current and Iris
 initialised by the end of the game's constructor.
 */
@Mixin(Minecraft.class)
public abstract class SmokeTestMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void betterBlending$smokeTest(CallbackInfo ci) {
        if (!Boolean.getBoolean("better_blending.smokeTest")) return;
        boolean passed = !IrisCompatibility.installed() || IrisSmokeTest.run();
        BlendingConfig.LOGGER.info("Better Blending smoke test {}{}", passed ? "passed" : "FAILED",
                IrisCompatibility.installed() ? "" : " (nothing to check without Iris on this era)");
        System.exit(passed ? 0 : 1);
    }
}
