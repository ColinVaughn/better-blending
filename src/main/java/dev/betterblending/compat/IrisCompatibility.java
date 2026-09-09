package dev.betterblending.compat;

import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.shadows.ShadowRenderingState;

/** Optional Iris calls are reached only when the loader reports Iris installed. */
public final class IrisCompatibility {
    private static boolean installed;
    private IrisCompatibility() {}
    public static void initialize(boolean present) { installed = present; }
    public static boolean installed() { return installed; }
    public static boolean shaderPackActive() { return installed && IrisApi.getInstance().isShaderPackInUse(); }
    public static boolean shadowPass() { return installed && ShadowRenderingState.areShadowsCurrentlyBeingRendered(); }
}
