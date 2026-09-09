package dev.betterblending.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.betterblending.TerrainShader;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(SectionCompiler.class)
public abstract class TerrainCompilerMixin {
    // Rebuild tasks call NeoForge's added overload directly, and the vanilla overload
    // delegates to it, so wrapping this one sees every compile exactly once.
    @WrapMethod(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;")
    private SectionCompiler.Results betterBlending$prepare(SectionPos position, RenderSectionRegion region,
            VertexSorting sorting, SectionBufferBuilderPack buffers, List<?> additionalRenderers,
            Operation<SectionCompiler.Results> original) {
        var generation = TerrainShader.sections();
        var result = original.call(position, region, sorting, buffers, additionalRenderers);
        if (generation != null && !result.renderedLayers.isEmpty()) generation.compile(position, region, result.visibilitySet);
        return result;
    }
}
