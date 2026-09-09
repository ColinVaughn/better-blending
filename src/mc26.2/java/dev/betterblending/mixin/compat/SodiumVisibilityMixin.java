package dev.betterblending.mixin.compat;

import dev.betterblending.TerrainSections;
import dev.betterblending.TerrainShader;
import dev.betterblending.compat.RegionTerrain;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;

@Pseudo
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumVisibilityMixin {
    @Shadow private RenderSectionManager renderSectionManager;

    // The first terrain layer of the frame, once Sodium has settled what it will draw.
    // No render pass is open yet: Sodium writes its own uniform buffer right after this.
    @Inject(method = "renderLayer", at = @At("HEAD"))
    private void betterBlending$visible(CallbackInfo ci) {
        var generation = TerrainShader.sections();
        if (generation == null || !TerrainShader.needsRendererPrepare()) return;
        var ready = new ArrayList<TerrainSections.Data>();
        var lists = renderSectionManager.getRenderLists().iterator(false);
        while (lists.hasNext()) {
            var list = lists.next();
            var indices = list.sectionsWithGeometryIterator(false);
            if (indices == null) continue;
            var region = (RegionTerrain) list.getRegion();
            while (indices.hasNext()) {
                var data = generation.get(region.betterBlending$mesh(indices.nextByteAsInt()));
                if (data != null) ready.add(data);
            }
        }
        TerrainShader.prepareRenderer(ready);
    }
}
