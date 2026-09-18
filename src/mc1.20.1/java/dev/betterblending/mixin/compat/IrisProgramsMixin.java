package dev.betterblending.mixin.compat;

import dev.betterblending.BlendingConfig;
import dev.betterblending.compat.IrisShaders;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.EnumMap;
import java.util.Map;

/* Iris 1.7 on Fabric and Oculus 1.8 on Forge, which share this code. */
@Pseudo
@Mixin(value = TransformPatcher.class, remap = false)
public abstract class IrisProgramsMixin {
    // Iris transforms each pack program for Sodium's terrain here, from a lambda per
    // pass, so the result is taken on the way out. Shadow passes stay unblended.
    @Inject(method = "patchSodium", at = @At("RETURN"), cancellable = true)
    @SuppressWarnings("PMD.ExcessiveParameterList") // Mirrors the target's parameters.
    private static void betterBlending$albedo(String name, String vertex, String geometry, String tessControl,
            String tessEval, String fragment, AlphaTest alpha, ShaderAttributeInputs inputs,
            Object2ObjectMap<?, ?> textures, CallbackInfoReturnable<Map<PatchShaderType, String>> cir) {
        var sources = cir.getReturnValue();
        if (sources == null || name.startsWith("shadow")) return;
        // Geometry/tessellation need varying forwarding through their extra stages.
        if (sources.entrySet().stream().anyMatch(e -> e.getValue() != null
                && e.getKey() != PatchShaderType.VERTEX && e.getKey() != PatchShaderType.FRAGMENT)) {
            BlendingConfig.LOGGER.warn("Better Blending cannot yet forward terrain coordinates through geometry/tessellation in {}", name);
            return;
        }
        String patchedFragment = sources.get(PatchShaderType.FRAGMENT), patchedVertex = sources.get(PatchShaderType.VERTEX);
        if (patchedFragment == null || patchedVertex == null) return;
        String blended = IrisShaders.fragment(patchedFragment);
        if (blended.equals(patchedFragment)) {
            BlendingConfig.LOGGER.warn("No terrain atlas samples found for Better Blending in Iris pass {}", name);
            return;
        }
        var result = new EnumMap<PatchShaderType, String>(PatchShaderType.class);
        result.putAll(sources); // Iris caches the originals; do not patch them twice on reload.
        result.put(PatchShaderType.FRAGMENT, blended);
        result.put(PatchShaderType.VERTEX, IrisShaders.vertex(patchedVertex));
        cir.setReturnValue(result);
    }
}
