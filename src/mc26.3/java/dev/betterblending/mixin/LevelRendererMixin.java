package dev.betterblending.mixin;

import dev.betterblending.TerrainShader;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow public abstract ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections();

    // Once per frame, after culling has settled the visible sections and before the frame
    // graph runs any terrain pass. GPU writes are refused inside a render pass on this
    // era, so this is the last point where the cache can be brought up to date. Terrain
    // is prepared for separate draws or for multidraw, whichever the device supports.
    // The hook sits at the calls rather than inside them because Sodium replaces one.
    @Inject(method = "render", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRendersIndirect(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;")})
    private void betterBlending$prepare(CallbackInfo ci) {
        var minecraft = Minecraft.getInstance();
        TerrainShader.begin(minecraft, minecraft.gameRenderer.mainCamera());
        TerrainShader.prepareDraw(visibleSections());
    }
}
