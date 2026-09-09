package dev.betterblending.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.betterblending.BlendingConfig;
import dev.betterblending.TerrainShader;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;

@Mixin(LevelRenderer.class)
public abstract class TerrainVisibilityMixin {
    // Holds LevelRenderer.RenderChunkInfo, which is package-private; see RenderChunkInfoAccessor.
    @Shadow @Final private ObjectArrayList<?> renderChunksInFrustum;

    @Shadow public abstract void setBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

    @Inject(method = {
            "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V"}, at = @At("TAIL"))
    private void betterBlending$invalidateDonors(CallbackInfo ci,
            @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) BlockPos position) {
        if (TerrainShader.sections() != null) {
            int radius = BlendingConfig.INSTANCE.terrainBiomeBlendStrength() > 0 ? 6 : 1;
            setBlocksDirty(position.getX() - radius, position.getY() - radius, position.getZ() - radius,
                    position.getX() + radius, position.getY() + radius, position.getZ() + radius);
        }
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"))
    private void betterBlending$readyBeforeDraw(RenderType type, PoseStack pose, double x, double y, double z,
            Matrix4f projection, CallbackInfo ci) {
        if (type != RenderType.solid()) return;
        var chunks = new ArrayList<ChunkRenderDispatcher.RenderChunk>(renderChunksInFrustum.size());
        for (Object info : renderChunksInFrustum) chunks.add(((RenderChunkInfoAccessor) info).betterBlending$chunk());
        TerrainShader.prepareDraw(chunks);
    }
}
