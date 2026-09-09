package dev.betterblending.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterblending.BlendingConfig;
import dev.betterblending.compat.IrisShaders;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Pseudo
@Mixin(value = ShaderCreator.class, remap = false)
public abstract class IrisProgramsMixin {
    // Iris 1.11 transforms every pack program that draws Sodium's terrain here. Shadow
    // programs are built by createShadow instead and stay unblended.
    @WrapOperation(method = "create", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/transform/TransformPatcher;patchSodium(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/gl/blending/AlphaTest;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;Ljava/util/Set;Z)Ljava/util/Map;"))
    private static Map<PatchShaderType, String> betterBlending$albedo(String name, String vertex, String geometry,
            String tessControl, String tessEval, String fragment, AlphaTest alpha, Object2ObjectMap<?, ?> textures,
            Set<String> overrides, boolean flag, Operation<Map<PatchShaderType, String>> original) {
        var sources = original.call(name, vertex, geometry, tessControl, tessEval, fragment, alpha, textures, overrides, flag);
        // Geometry/tessellation need varying forwarding through their extra stages.
        if (sources.entrySet().stream().anyMatch(e -> e.getValue() != null
                && e.getKey() != PatchShaderType.VERTEX && e.getKey() != PatchShaderType.FRAGMENT)) {
            BlendingConfig.LOGGER.warn("Better Blending cannot yet forward terrain coordinates through geometry/tessellation in {}", name);
            return sources;
        }
        String patchedFragment = sources.get(PatchShaderType.FRAGMENT), patchedVertex = sources.get(PatchShaderType.VERTEX);
        if (patchedFragment == null || patchedVertex == null) return sources;
        String blended = IrisShaders.fragment(patchedFragment);
        if (blended.equals(patchedFragment)) {
            BlendingConfig.LOGGER.warn("No terrain atlas samples found for Better Blending in Iris pass {}", name);
            return sources;
        }
        var result = new EnumMap<PatchShaderType, String>(PatchShaderType.class);
        result.putAll(sources); // Iris caches the originals; do not patch them twice on reload.
        result.put(PatchShaderType.FRAGMENT, blended);
        result.put(PatchShaderType.VERTEX, IrisShaders.vertex(patchedVertex));
        return result;
    }
}
