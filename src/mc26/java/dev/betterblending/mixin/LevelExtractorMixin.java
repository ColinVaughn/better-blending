package dev.betterblending.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.betterblending.BlendingConfig;
import dev.betterblending.TerrainShader;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/* Block invalidation moved from LevelRenderer to LevelExtractor in 26.1. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow public abstract void setBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

    // A changed block also changes what its neighbours borrow, as far as blending reaches.
    @Inject(method = {
            "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            "blockChanged(Lnet/minecraft/core/BlockPos;I)V"}, at = @At("TAIL"))
    private void betterBlending$invalidateDonors(CallbackInfo ci, @Local(argsOnly = true) BlockPos position) {
        if (TerrainShader.sections() != null) {
            int radius = BlendingConfig.INSTANCE.terrainBiomeBlendStrength() > 0 ? 6 : 1;
            setBlocksDirty(position.getX() - radius, position.getY() - radius, position.getZ() - radius,
                    position.getX() + radius, position.getY() + radius, position.getZ() + radius);
        }
    }

    // New resources mean a new atlas, so every cached sprite rectangle is stale. The next
    // frame rebuilds the cache and asks for every section to be recompiled with it.
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void betterBlending$reload(ResourceManager resources, CallbackInfo ci) {
        TerrainShader.clear();
    }
}
