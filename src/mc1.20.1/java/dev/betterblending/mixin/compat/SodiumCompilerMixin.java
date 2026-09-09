package dev.betterblending.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.betterblending.TerrainShader;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class SodiumCompilerMixin {
    // The full descriptor passes over the bridge method that returns Object.
    @WrapMethod(method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput betterBlending$compile(ChunkBuildContext context, CancellationToken cancellation,
            Operation<ChunkBuildOutput> original) {
        var generation = TerrainShader.sections();
        var output = original.call(context, cancellation);
        if (generation != null && output != null && !output.meshes.isEmpty()) {
            var render = output.render;
            generation.compile(SectionPos.of(render.getChunkX(), render.getChunkY(), render.getChunkZ()),
                    context.cache.getWorldSlice(), output.info);
        }
        return output;
    }
}
