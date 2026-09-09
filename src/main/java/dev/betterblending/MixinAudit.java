package dev.betterblending;

import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 Development check. Launched with {@code -Dbetter_blending.auditMixins=true}, the client
 applies every mixin to its target class during mod loading and then exits: from the
 client entrypoint on Fabric, and once every mod is constructed on Forge and NeoForge.
 Compiling a node proves that mixin target classes exist, not that each injection still
 finds its method or call site. A required injection that no longer matches normally
 fails only when its class first loads, which may be minutes into a session. This makes
 that failure immediate. On Fabric it runs before the game window opens, so it needs no
 display; Forge and NeoForge open a loading window before any mod runs.
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
