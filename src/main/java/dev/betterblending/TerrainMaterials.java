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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
//? if >=26.1 {
/*import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
*///?} else {
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.BlockAndTintGetter;
//?}
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Discovers ordinary cube faces from any namespace; no block or texture-name allowlist. */
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

    // One palette lock serializes discovery and uploads; split staging only if profiling shows contention.
    synchronized int material(Minecraft minecraft, BlockAndTintGetter level, BlockPos position, BlockState state) {
        if (closed) return 0;
        int cached = states.getInt(state);
        if (cached >= 0) return cached;
        int id = 0;
        if (!BlendingConfig.INSTANCE.excluded_blocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
                && tintIndices.size() < LIMIT && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && state.getRenderShape() == RenderShape.MODEL && Block.isShapeFullBlock(state.getShape(level, position))
                && layerSupported(minecraft, state)) {
            var model = model(minecraft, state);
            BakedQuad[] faces;
            try {
                faces = faces(model, state);
            } catch (UnsupportedOperationException unsupported) {
                // Some custom renderers deliberately expose no vanilla quad adapter.
                states.put(state, 0);
                return 0;
            }
            if (java.util.Arrays.stream(faces, 0, 6).anyMatch(java.util.Objects::nonNull)) {
                id = tintIndices.size();
                int tint = -1;
                for (var face : faces) if (face != null && tinted(face)) { tint = tintIndex(face); break; }
                tintIndices.add(tint);
                for (Direction direction : Direction.values()) {
                    int index = direction.get3DDataValue();
                    var face = faces[index];
                    var overlay = faces[index + 6];
                    if (face == null || (tinted(face) && tintIndex(face) != tint)) continue;
                    if (overlay != null && tintIndex(overlay) != tint) continue;
                    boolean emissive = state.getLightEmission() > 0
                            || state.emissiveRendering(/*? if <26.1 {*/ level, position /*?}*/);
                    writeFace(id, index, sprite(face), tinted(face), emissive, overlay != null);
                    if (overlay != null) writeFace(id, index + 6, sprite(overlay), true, emissive, false);
                }
            }
        }
        states.put(state, id);
        return id;
    }

    //? if >=26.1 {
    /*static boolean layerSupported(Minecraft minecraft, BlockState state) {
        return supportsLayer(model(minecraft, state));
    }

    static BlockStateModel model(Minecraft minecraft, BlockState state) {
        return minecraft.getModelManager().getBlockStateModelSet().get(state);
    }

    // Translucent quads draw in the translucent layer, which blending never touches.
    static boolean supportsLayer(BlockStateModel model) {
        return !model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);
    }

    static BakedQuad[] faces(BlockStateModel model, BlockState state) {
        BakedQuad[] result = new BakedQuad[12];
        int[] counts = new int[6];
        var parts = new ArrayList<BlockStateModelPart>();
        model.collectParts(RandomSource.create(42), parts);
        for (Direction direction : Direction.values()) {
            for (var part : parts) for (var quad : part.getQuads(direction)) {
                collectFace(result, counts, direction.get3DDataValue(), quad);
            }
        }
        for (var part : parts) for (var quad : part.getQuads(null)) {
            collectFace(result, counts, quad.direction().get3DDataValue(), quad);
        }
        return pairLayers(result, counts);
    }

    private static Direction direction(BakedQuad quad) { return quad.direction(); }
    private static TextureAtlasSprite sprite(BakedQuad quad) { return quad.materialInfo().sprite(); }
    private static boolean tinted(BakedQuad quad) { return quad.materialInfo().isTinted(); }
    private static int tintIndex(BakedQuad quad) { return quad.materialInfo().tintIndex(); }
    private static boolean hasGeometry(BakedQuad quad) { return true; }
    private static float position(BakedQuad quad, int vertex, int axis) { return quad.position(vertex).get(axis); }
    private static float u(BakedQuad quad, int vertex) { return UVPair.unpackU(quad.packedUV(vertex)); }
    private static float v(BakedQuad quad, int vertex) { return UVPair.unpackV(quad.packedUV(vertex)); }
    *///?} else {
    static boolean layerSupported(Minecraft minecraft, BlockState state) {
        return supportsLayer(ItemBlockRenderTypes.getChunkRenderType(state));
    }

    static BakedModel model(Minecraft minecraft, BlockState state) {
        return minecraft.getBlockRenderer().getBlockModel(state);
    }

    static boolean supportsLayer(RenderType layer) {
        return layer == RenderType.solid() || layer == RenderType.cutoutMipped() || layer == RenderType.cutout();
    }

    static BakedQuad[] faces(BakedModel model, BlockState state) {
        BakedQuad[] result = new BakedQuad[12];
        int[] counts = new int[6];
        if (model.isCustomRenderer()) return result;
        var random = RandomSource.create(42);
        for (Direction direction : Direction.values()) {
            random.setSeed(42);
            for (var quad : model.getQuads(state, direction, random)) {
                int face = direction.get3DDataValue();
                collectFace(result, counts, face, quad);
            }
        }
        random.setSeed(42);
        for (var quad : model.getQuads(state, null, random)) {
            int face = quad.getDirection().get3DDataValue();
            collectFace(result, counts, face, quad);
        }
        return pairLayers(result, counts);
    }

    private static Direction direction(BakedQuad quad) { return quad.getDirection(); }
    private static TextureAtlasSprite sprite(BakedQuad quad) { return quad.getSprite(); }
    private static boolean tinted(BakedQuad quad) { return quad.isTinted(); }
    private static int tintIndex(BakedQuad quad) { return quad.getTintIndex(); }
    // Packed vertex data holds the position at 0..2 and the UV at 4..5 of each vertex.
    private static boolean hasGeometry(BakedQuad quad) { return quad.getVertices().length / 4 >= 6; }
    private static float position(BakedQuad quad, int vertex, int axis) { return attribute(quad, vertex, axis); }
    private static float u(BakedQuad quad, int vertex) { return attribute(quad, vertex, 4); }
    private static float v(BakedQuad quad, int vertex) { return attribute(quad, vertex, 5); }

    private static float attribute(BakedQuad quad, int vertex, int offset) {
        int[] vertices = quad.getVertices();
        return Float.intBitsToFloat(vertices[vertex * (vertices.length / 4) + offset]);
    }
    //?}

    /** Keeps a face's base and tinted overlay together when they share UVs; drops anything else layered. */
    private static BakedQuad[] pairLayers(BakedQuad[] result, int[] counts) {
        for (int face = 0; face < 6; face++) {
            if (counts[face] == 1) continue;
            if (counts[face] == 2 && result[face] != null && result[face + 6] != null
                    && tinted(result[face]) != tinted(result[face + 6])
                    && sameFaceUVs(result[face], result[face + 6])) {
                if (tinted(result[face])) {
                    var overlay = result[face];
                    result[face] = result[face + 6];
                    result[face + 6] = overlay;
                }
            } else {
                result[face] = result[face + 6] = null;
            }
        }
        return result;
    }

    private static void collectFace(BakedQuad[] faces, int[] counts, int face, BakedQuad quad) {
        int layer = counts[face]++;
        if (layer < 2 && fullFace(quad)) faces[face + layer * 6] = quad;
    }

    private static boolean sameFaceUVs(BakedQuad first, BakedQuad second) {
        var spriteA = sprite(first);
        var spriteB = sprite(second);
        if (spriteA == null || spriteB == null || spriteA == spriteB) return false;
        for (int i = 0; i < 4; i++) {
            boolean matched = false;
            for (int j = 0; j < 4; j++) {
                if (!samePosition(first, i, second, j)) continue;
                float uA = (u(first, i) - spriteA.getU0()) / (spriteA.getU1() - spriteA.getU0());
                float vA = (v(first, i) - spriteA.getV0()) / (spriteA.getV1() - spriteA.getV0());
                float uB = (u(second, j) - spriteB.getU0()) / (spriteB.getU1() - spriteB.getU0());
                float vB = (v(second, j) - spriteB.getV0()) / (spriteB.getV1() - spriteB.getV0());
                matched = Math.abs(uA - uB) < 0.001F && Math.abs(vA - vB) < 0.001F;
                break;
            }
            if (!matched) return false;
        }
        return true;
    }

    // Bit-exact, like the packed vertex comparison it replaces.
    private static boolean samePosition(BakedQuad first, int i, BakedQuad second, int j) {
        for (int axis = 0; axis < 3; axis++) {
            if (Float.floatToRawIntBits(position(first, i, axis)) != Float.floatToRawIntBits(position(second, j, axis))) {
                return false;
            }
        }
        return true;
    }

    static boolean fullFace(BakedQuad quad) {
        if (!hasGeometry(quad)) return false;
        Direction direction = direction(quad);
        int axis = direction.getAxis().ordinal();
        float plane = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        int corners = 0;
        for (int v = 0; v < 4; v++) {
            int corner = 0, bit = 0;
            for (int a = 0; a < 3; a++) {
                float coordinate = position(quad, v, a);
                if (a == axis) {
                    if (Math.abs(coordinate - plane) > 0.001F) return false;
                } else {
                    if (Math.min(Math.abs(coordinate), Math.abs(coordinate - 1)) > 0.001F) return false;
                    if (coordinate > 0.5F) corner |= 1 << bit;
                    bit++;
                }
            }
            corners |= 1 << corner;
        }
        return corners == 15;
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

    /** Start at the synchronized height and skip foliage locally, without a block allowlist. */
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
