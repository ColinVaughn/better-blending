package dev.betterblending.mixin.compat;

import dev.betterblending.compat.RegionTerrain;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = RenderRegion.class, remap = false)
public abstract class SodiumRegionMixin implements RegionTerrain {
    // Render lists name sections by their index in a region, so the key a section is
    // drawn from is kept the same way, next to the region's own per-section state.
    @Unique private final Object[] betterBlending$meshes = new Object[RenderRegion.REGION_SIZE];

    @Inject(method = "setSectionRenderState", at = @At("HEAD"))
    private void betterBlending$accept(int section, BuiltSectionInfo info, CallbackInfo ci) {
        betterBlending$meshes[section] = info;
    }

    @Inject(method = "clearSectionRenderState", at = @At("HEAD"))
    private void betterBlending$clear(int section, CallbackInfo ci) {
        betterBlending$meshes[section] = null;
    }

    @Override public Object betterBlending$mesh(int section) { return betterBlending$meshes[section]; }
}
