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
    // The full descriptor passes over the bridge method that returns the output's supertype.
    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput betterBlending$compile(ChunkBuildContext context, CancellationToken cancellation,
            Operation<ChunkBuildOutput> original) {
        var generation = TerrainShader.sections();
        var output = original.call(context, cancellation);
        if (generation != null && output != null && !output.meshes.isEmpty()) {
            var section = output.section;
            generation.compile(SectionPos.of(section.getChunkX(), section.getChunkY(), section.getChunkZ()),
                    context.cache.getWorldSlice(), output.info);
        }
        return output;
    }
}
