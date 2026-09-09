package dev.betterblending.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.betterblending.TerrainSections;
import dev.betterblending.TerrainShader;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 Before 1.20.2 there is no SectionCompiler; a rebuild task compiles its chunk itself.
 The task and its result type are package-private, so neither is named here. Everything
 is taken from compile as it runs. The task reaches its chunk only through a synthetic
 field, Forge adds a second constructor, and compile empties the region field before it
 builds; locals are no help at its return either, because Mojang's bytecode drops the
 region from its frames once it is last used.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask")
public abstract class RebuildTaskMixin {
    @Shadow protected RenderChunkRegion region;

    // Capturing the generation before compiling prevents a task cancelled by a reload
    // from publishing into the new atlas.
    @Inject(method = "compile", at = @At("HEAD"))
    private void betterBlending$begin(CallbackInfoReturnable<?> cir,
            @Share("generation") LocalRef<TerrainSections> generation,
            @Share("region") LocalRef<RenderChunkRegion> snapshot) {
        generation.set(TerrainShader.sections());
        snapshot.set(region);
    }

    // compile's first immutable() is the chunk's origin, taken from its render chunk.
    @ModifyExpressionValue(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;immutable()Lnet/minecraft/core/BlockPos;", ordinal = 0))
    private BlockPos betterBlending$origin(BlockPos origin, @Share("origin") LocalRef<BlockPos> shared) {
        shared.set(origin);
        return origin;
    }

    // Finish before the caller can upload this mesh or mark it compiled.
    @Inject(method = "compile", at = @At("RETURN"))
    private void betterBlending$prepare(CallbackInfoReturnable<?> cir,
            @Share("generation") LocalRef<TerrainSections> generation,
            @Share("region") LocalRef<RenderChunkRegion> snapshot,
            @Share("origin") LocalRef<BlockPos> origin) {
        var sections = generation.get();
        var region = snapshot.get();
        var results = (CompileResultsAccessor) cir.getReturnValue();
        if (sections != null && region != null && !results.betterBlending$renderedLayers().isEmpty()) {
            sections.compile(SectionPos.of(origin.get()), region, results.betterBlending$visibility());
        }
    }
}
