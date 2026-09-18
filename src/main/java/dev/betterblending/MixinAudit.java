package dev.betterblending;

import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 Development check. With {@code -Dbetter_blending.auditMixins=true} the client applies
 every mixin during mod loading and exits, so an injection that no longer matches fails
 at startup instead of whenever its target class first loads.
 */
public final class MixinAudit {
    private MixinAudit() {
    }

    public static void runIfRequested() {
        if (!Boolean.getBoolean("better_blending.auditMixins")) return;
        // Throws on the first required injection that fails to apply.
        MixinEnvironment.getCurrentEnvironment().audit();
        BlendingConfig.LOGGER.info("Better Blending mixin audit passed");
        System.exit(0);
    }
}
