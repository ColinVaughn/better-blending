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

@Mixin(SectionCompiler.class)
public abstract class TerrainCompilerMixin {
    @WrapMethod(method = "compile")
    private SectionCompiler.Results betterBlending$prepare(SectionPos position, RenderSectionRegion region,
            VertexSorting sorting, SectionBufferBuilderPack buffers, Operation<SectionCompiler.Results> original) {
        var generation = TerrainShader.sections();
        var result = original.call(position, region, sorting, buffers);
        // Finish before the caller can upload this mesh or mark it compiled. Capturing the
        // generation also prevents a cancelled reload task from publishing into a new atlas.
        if (generation != null && !result.renderedLayers.isEmpty()) generation.compile(position, region, result.visibilitySet);
        return result;
    }
}
