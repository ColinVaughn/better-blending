package dev.betterblending.backend;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** {@link TerrainTexture} over a pre-1.21.5 {@code DynamicTexture}, identified by its GL name. */
public final class VanillaTexture implements TerrainTexture {
    private final DynamicTexture texture;
    private final NativeImage pixels;

    VanillaTexture(int width, int height, boolean linear) {
        texture = new DynamicTexture(width, height, false);
        pixels = texture.getPixels();
        if (linear) texture.setFilter(true, false);
    }

    /** The GL texture name, for code that binds the texture directly. */
    public static int glId(TerrainTexture texture) {
        return ((VanillaTexture) texture).texture.getId();
    }

    @Override public int width() { return pixels.getWidth(); }

    @Override public int height() { return pixels.getHeight(); }

    // NativeImage's "RGBA" accessors store the int as-is, which is the raw byte order
    // TerrainTexture promises.
    @Override public int get(int x, int y) { return pixels.getPixelRGBA(x, y); }

    @Override public void set(int x, int y, int value) { pixels.setPixelRGBA(x, y, value); }

    @Override public void fill(int x, int y, int width, int height, int value) {
        pixels.fillRect(x, y, width, height, value);
    }

    @Override public void upload() { texture.upload(); }

    @Override public void upload(int x, int y, int width, int height) {
        texture.bind();
        pixels.upload(0, x, y, x, y, width, height, false, false);
    }

    @Override public void close() { texture.close(); }
}
