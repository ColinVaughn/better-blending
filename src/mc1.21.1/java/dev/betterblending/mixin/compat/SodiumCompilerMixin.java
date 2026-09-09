package dev.betterblending.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.betterblending.TerrainShader;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class SodiumCompilerMixin {
    @WrapMethod(method = "execute")
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
