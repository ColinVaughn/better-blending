package dev.betterblending.mixin.compat;

import dev.betterblending.compat.CompiledTerrain;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = RenderSection.class, remap = false)
public abstract class SodiumSectionMixin implements CompiledTerrain {
    @Unique private Object betterBlending$mesh;

    // The populated-mesh path returns early; retain its key on every return.
    @Inject(method = "setInfo", at = @At("RETURN"))
    private void betterBlending$accept(BuiltSectionInfo info, CallbackInfo ci) {
        betterBlending$mesh = info;
    }

    @Override public Object betterBlending$mesh() { return betterBlending$mesh; }
}
