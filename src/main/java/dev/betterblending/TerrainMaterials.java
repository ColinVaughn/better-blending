/*
 Copyright (c) 2026 Colin Vaughn

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.
 */

package dev.betterblending;

import dev.betterblending.backend.Backend;
import dev.betterblending.backend.TerrainTexture;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
//? if >=26.1 {
/*import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
*///?} else {
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.world.level.BlockAndTintGetter;
//?}
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Discovers ordinary cube faces from any namespace. */
final class TerrainMaterials implements AutoCloseable {
    static final int LIMIT = 4096;
    // NO_LEAVES exists on client chunks but is not populated by chunk packets.
    static final Heightmap.Types SURFACE_HEIGHTMAP = Heightmap.Types.MOTION_BLOCKING;
    final TerrainTexture texture = Backend.get().createTexture("better_blending:materials", 36, LIMIT, false);
    final TerrainTexture noise = Backend.get().createTexture("better_blending:noise", 128, 128, true);
    private final Object2IntOpenHashMap<BlockState> states = new Object2IntOpenHashMap<>();
    private final List<Integer> tintIndices = new ArrayList<>(List.of(-1));
    private boolean dirty, closed;

    TerrainMaterials() {
        states.defaultReturnValue(-1);
        texture.fill(0, 0, 36, LIMIT, 0);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            noise.set(x, y, noiseByte(x, y) | (noiseByte(x + 37, y + 17) << 8) | 0xFF000000);
        }
        noise.upload();
    }

    static int noiseByte(int x, int y) {
        int value = (x & 127) * 0x1f123bb5 ^ (y & 127) * 0x5f356495;
        value = (value ^ (value >>> 16)) * 0x45d9f3b;
        return (value ^ (value >>> 16)) & 255;
    }

    // One palette lock serializes discovery and uploads.
    synchronized int material(Minecraft minecraft, BlockAndTintGetter level, BlockPos position, BlockState state) {
        if (closed) return 0;
        int cached = states.getInt(state);
        if (cached >= 0) return cached;
        int id = eligible(minecraft, level, position, state) ? discover(minecraft, level, position, state) : 0;
        states.put(state, id);
        return id;
    }

    private boolean eligible(Minecraft minecraft, BlockAndTintGetter level, BlockPos position, BlockState state) {
        return !BlendingConfig.INSTANCE.excluded_blocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
                && tintIndices.size() < LIMIT && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && state.getRenderShape() == RenderShape.MODEL && Block.isShapeFullBlock(state.getShape(level, position))
                && TerrainFaces.layerSupported(minecraft, state);
    }

    /* Registers a new material from the block's cube faces, or returns 0 when it has none. */
    private int discover(Minecraft minecraft, BlockAndTintGetter level, BlockPos position, BlockState state) {
        BakedQuad[] faces;
        try {
            faces = TerrainFaces.faces(TerrainFaces.model(minecraft, state), state);
        } catch (UnsupportedOperationException unsupported) {
            // Some custom renderers deliberately expose no vanilla quad adapter.
            return 0;
        }
        if (java.util.Arrays.stream(faces, 0, 6).allMatch(java.util.Objects::isNull)) return 0;
        int id = tintIndices.size();
        int tint = baseTint(faces);
        tintIndices.add(tint);
        boolean emissive = state.getLightEmission() > 0
                || state.emissiveRendering(/*? if <26.1 {*/ level, position /*?}*/);
        for (int face = 0; face < 6; face++) writeLayers(id, face, faces[face], faces[face + 6], tint, emissive);
        return id;
    }

    private static int baseTint(BakedQuad[] faces) {
        for (var face : faces) if (face != null && TerrainFaces.tinted(face)) return TerrainFaces.tintIndex(face);
        return -1;
    }

    /* Writes a face and its overlay, unless either is tinted differently from the block. */
    private void writeLayers(int id, int index, BakedQuad face, BakedQuad overlay, int tint, boolean emissive) {
        if (face == null || (TerrainFaces.tinted(face) && TerrainFaces.tintIndex(face) != tint)) return;
        if (overlay != null && TerrainFaces.tintIndex(overlay) != tint) return;
        writeFace(id, index, TerrainFaces.sprite(face), TerrainFaces.tinted(face), emissive, overlay != null);
        if (overlay != null) writeFace(id, index + 6, TerrainFaces.sprite(overlay), true, emissive, false);
    }

    private void writeFace(int id, int face, TextureAtlasSprite sprite, boolean tinted, boolean emissive, boolean layered) {
        // The texels the sprite's UVs address. The 26.x atlases pad each sprite inside its
        // slot by at least 1 << mip level texels, so they start past getX() and getY().
        int width = sprite.contents().width(), height = sprite.contents().height();
        int x = Math.round(sprite.getU0() * width / (sprite.getU1() - sprite.getU0()));
        int y = Math.round(sprite.getV0() * height / (sprite.getV1() - sprite.getV0()));
        texture.set(face * 3, id, x | (y << 16));
        texture.set(face * 3 + 1, id, (x + width) | ((y + height) << 16));
        texture.set(face * 3 + 2, id, (tinted ? 1 : 0) | (emissive ? 256 : 0) | (layered ? 65536 : 0));
        dirty = true;
    }

    synchronized int tint(Minecraft minecraft, BlockAndTintGetter level, BlockPos position, BlockState state, int material) {
        int index = tintIndices.get(material);
        if (index < 0) return 0xFFFFFF;
        //? if >=26.1 {
        /*var source = minecraft.getBlockColors().getTintSource(state, index);
        return source == null ? 0xFFFFFF : source.colorInWorld(state, level, position);
        *///?} else {
        return minecraft.getBlockColors().getColor(state, level, position, index);
        //?}
    }

    // From 26.1 getMaxY is inclusive; the older getMaxBuildHeight was one past the top.
    //? if >=26.1 {
    /*private static int minY(BlockGetter blocks) { return blocks.getMinY(); }
    private static int maxYExclusive(BlockGetter blocks) { return blocks.getMaxY() + 1; }
    *///?} else {
    private static int minY(BlockGetter blocks) { return blocks.getMinBuildHeight(); }
    private static int maxYExclusive(BlockGetter blocks) { return blocks.getMaxBuildHeight(); }
    //?}

    /** Start at the synchronized height and skip foliage locally. */
    static int surfaceHeight(BlockGetter blocks, BlockPos.MutableBlockPos pos, int x, int z, int height) {
        while (height > minY(blocks)) {
            pos.set(x, height - 1, z);
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(blocks.getBlockState(pos))) break;
            height--;
        }
        return height;
    }

    /** Closest exposed floor in a bounded vertical band, never the distant Nether roof. */
    static int caveSurface(BlockGetter blocks, BlockPos.MutableBlockPos pos, int x, int z, int cameraY) {
        int min = Math.max(minY(blocks) + 1, cameraY - 48);
        int max = Math.min(maxYExclusive(blocks) - 1, cameraY + 16);
        int best = minY(blocks), distance = Integer.MAX_VALUE;
        pos.set(x, max, z);
        boolean open = blocks.getBlockState(pos).isAir();
        for (int y = max - 1; y >= min - 1; y--) {
            pos.setY(y);
            var state = blocks.getBlockState(pos);
            boolean air = state.isAir();
            if (open && !air && state.getFluidState().isEmpty() && Math.abs(y + 1 - cameraY) < distance) {
                best = y + 1;
                distance = Math.abs(best - cameraY);
            }
            open = air;
        }
        return best;
    }

    synchronized void upload() {
        if (dirty) texture.upload();
        dirty = false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        texture.close();
        noise.close();
    }
}
