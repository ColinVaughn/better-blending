package dev.betterblending.mixin.compat;

import dev.betterblending.BlendingConfig;
import dev.betterblending.compat.IrisShaders;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.util.EnumMap;
import java.util.Map;

@Pseudo
@Mixin(value = SodiumPrograms.class, remap = false)
public abstract class IrisProgramsMixin {
    @ModifyVariable(method = "createGlShaders", at = @At("HEAD"), argsOnly = true)
    private Map<PatchShaderType, String> betterBlending$albedo(Map<PatchShaderType, String> sources, String passName,
                                                            Map<PatchShaderType, String> original) {
        if (passName.startsWith("shadow")) return sources;
        // Geometry/tessellation need varying forwarding through their extra stages.
        if (sources.entrySet().stream().anyMatch(e -> e.getValue() != null
                && e.getKey() != PatchShaderType.VERTEX && e.getKey() != PatchShaderType.FRAGMENT)) {
            BlendingConfig.LOGGER.warn("Better Blending cannot yet forward terrain coordinates through geometry/tessellation in {}", passName);
            return sources;
        }
        String fragment = sources.get(PatchShaderType.FRAGMENT), vertex = sources.get(PatchShaderType.VERTEX);
        if (fragment == null || vertex == null) return sources;
        String patched = IrisShaders.fragment(fragment);
        if (patched.equals(fragment)) {
            BlendingConfig.LOGGER.warn("No terrain atlas samples found for Better Blending in Iris pass {}", passName);
            return sources;
        }
        var result = new EnumMap<PatchShaderType, String>(PatchShaderType.class);
        result.putAll(sources); // Iris caches the originals; do not patch them twice on reload.
        result.put(PatchShaderType.FRAGMENT, patched);
        result.put(PatchShaderType.VERTEX, IrisShaders.vertex(vertex));
        return result;
    }
}
