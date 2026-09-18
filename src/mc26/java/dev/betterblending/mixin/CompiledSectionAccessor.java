package dev.betterblending.mixin;

import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/*
 The mesh keeps the compile result's VisibilitySet, the same object the compile hook
 files blending data under, so it identifies that data without another field.
 */
@Mixin(CompiledSectionMesh.class)
public interface CompiledSectionAccessor {
    @Accessor("visibilitySet") VisibilitySet betterBlending$visibility();
}
