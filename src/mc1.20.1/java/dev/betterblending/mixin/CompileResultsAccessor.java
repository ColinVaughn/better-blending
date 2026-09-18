package dev.betterblending.mixin;

import net.minecraft.client.renderer.chunk.VisibilitySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

/* Reads a rebuild's results, whose class is package-private before 1.20.2. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask$CompileResults")
public interface CompileResultsAccessor {
    @Accessor("visibilitySet") VisibilitySet betterBlending$visibility();

    @Accessor("renderedLayers") Map<?, ?> betterBlending$renderedLayers();
}
