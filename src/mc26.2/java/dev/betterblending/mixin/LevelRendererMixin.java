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

    // Once per frame, after culling has settled the visible sections and before any
    // terrain pass opens. GPU writes are refused inside a render pass on this era, so
    // this is the last point where the cache can be brought up to date. The hook sits at
    // the call rather than inside prepareChunkRenders because Sodium replaces that method.
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"))
    private void betterBlending$prepare(CallbackInfo ci) {
        var minecraft = Minecraft.getInstance();
        TerrainShader.begin(minecraft, minecraft.gameRenderer.mainCamera());
        TerrainShader.prepareDraw(visibleSections());
    }
}
