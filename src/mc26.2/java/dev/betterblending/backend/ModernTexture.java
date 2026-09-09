package dev.betterblending.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 {@link TerrainTexture} over a 26.x GPU texture. Texels live in a plain int array with
 the raw byte order the GLSL expects, and rectangles are staged into a packed buffer
 for the command encoder, which only accepts tightly packed rows.
 */
public final class ModernTexture implements TerrainTexture {
    private final int width, height;
    private final int[] texels;
    private final GpuTexture texture;
    private final GpuTextureView view;
    private final GpuSampler sampler;
    private final boolean linear;
    private ByteBuffer staging;

    ModernTexture(String label, int width, int height, boolean linear) {
        this.width = width;
        this.height = height;
        this.linear = linear;
        texels = new int[width * height];
        var device = RenderSystem.getDevice();
        // Created the way DynamicTexture creates its own: RGBA8, one layer, one mip.
        texture = device.createTexture(label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        view = device.createTextureView(texture);
        sampler = RenderSystem.getSamplerCache().getRepeat(linear ? FilterMode.LINEAR : FilterMode.NEAREST);
    }

    public static GpuTextureView view(TerrainTexture texture) {
        return ((ModernTexture) texture).view;
    }

    public static GpuSampler sampler(TerrainTexture texture) {
        return ((ModernTexture) texture).sampler;
    }

    /** The device texture, for a renderer that binds textures outside render passes. */
    public static GpuTexture texture(TerrainTexture texture) {
        return ((ModernTexture) texture).texture;
    }

    /** Whether {@link #sampler} filters linearly; it always repeats. */
    public static boolean linear(TerrainTexture texture) {
        return ((ModernTexture) texture).linear;
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override public int get(int x, int y) { return texels[y * width + x]; }

    @Override public void set(int x, int y, int value) { texels[y * width + x] = value; }

    @Override public void fill(int x, int y, int width, int height, int value) {
        for (int row = y; row < y + height; row++) {
            int start = row * this.width + x;
            Arrays.fill(texels, start, start + width, value);
        }
    }

    @Override public void upload() { upload(0, 0, width, height); }

    @Override public void upload(int x, int y, int width, int height) {
        int bytes = width * height * Integer.BYTES;
        if (staging == null || staging.capacity() < bytes) {
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc(bytes);
        }
        // Little-endian ints put the lowest byte first: red, green, blue, alpha.
        staging.clear().order(ByteOrder.LITTLE_ENDIAN);
        var ints = staging.asIntBuffer();
        for (int row = 0; row < height; row++) ints.put(texels, (y + row) * this.width + x, width);
        staging.limit(bytes);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, staging, 0, 0, x, y, width, height);
    }

    @Override public void close() {
        view.close();
        texture.close();
        if (staging != null) MemoryUtil.memFree(staging);
        staging = null;
    }
}
