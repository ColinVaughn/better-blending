package dev.betterblending.mixin.compat;

import dev.betterblending.TerrainSections;
import dev.betterblending.TerrainShader;
import dev.betterblending.compat.CompiledTerrain;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;

@Pseudo
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumVisibilityMixin {
    @Shadow public abstract SortedRenderLists getRenderLists();

    @Inject(method = "renderLayer", at = @At("HEAD"))
    private void betterBlending$visible(CallbackInfo ci) {
        var generation = TerrainShader.sections();
        if (generation == null || !TerrainShader.needsRendererPrepare()) return;
        var ready = new ArrayList<TerrainSections.Data>();
        var lists = getRenderLists().iterator(false);
        while (lists.hasNext()) {
            var list = lists.next();
            var indices = list.sectionsWithGeometryIterator(false);
            if (indices == null) continue;
            while (indices.hasNext()) {
                var section = list.getRegion().getSection(indices.nextByteAsInt());
                if (section == null) continue;
                var data = generation.get(((CompiledTerrain) section).betterBlending$mesh());
                if (data != null) ready.add(data);
            }
        }
        TerrainShader.prepareRenderer(ready);
    }
}
