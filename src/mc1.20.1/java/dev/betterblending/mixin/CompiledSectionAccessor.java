package dev.betterblending.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/* Before 1.20.2 a compiled section is a ChunkRenderDispatcher.CompiledChunk. */
@Mixin(ChunkRenderDispatcher.CompiledChunk.class)
public interface CompiledSectionAccessor {
    @Accessor("visibilitySet") VisibilitySet betterBlending$visibility();
}
