package dev.betterblending.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Pseudo
@Mixin(targets = {"net.caffeinemc.mods.sodium.client.world.LevelSlice",
        "org.embeddedt.embeddium.impl.world.WorldSlice"}, remap = false)
public abstract class RendererSliceMixin {
    // Six donor blocks plus the neighbor consulted by face culling. The existing
    // 3x3x3 section snapshot already contains this area; expand only its unpack bounds.
    @ModifyConstant(method = "prepare", constant = @Constant(intValue = 2), require = 6, allow = 6)
    private static int betterBlending$donorBounds(int original) {
        return dev.betterblending.TerrainShader.sections() == null ? original : 7;
    }
}
