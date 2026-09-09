package dev.betterblending.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Before 1.20.2 the frustum-culled list holds these package-private wrappers, not chunks. */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer$RenderChunkInfo")
public interface RenderChunkInfoAccessor {
    @Accessor("chunk") ChunkRenderDispatcher.RenderChunk betterBlending$chunk();
}
