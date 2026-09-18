package dev.betterblending;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.1 {
/*import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import java.util.ArrayList;
*///?} else {
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
//?}

/** Reads the full cube faces out of a block model, indexed by face and then by layer. */
final class TerrainFaces {
    private TerrainFaces() {}

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
    static TextureAtlasSprite sprite(BakedQuad quad) { return quad.materialInfo().sprite(); }
    static boolean tinted(BakedQuad quad) { return quad.materialInfo().isTinted(); }
    static int tintIndex(BakedQuad quad) { return quad.materialInfo().tintIndex(); }
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
    static TextureAtlasSprite sprite(BakedQuad quad) { return quad.getSprite(); }
    static boolean tinted(BakedQuad quad) { return quad.isTinted(); }
    static int tintIndex(BakedQuad quad) { return quad.getTintIndex(); }
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

    /* Keeps a face's base and tinted overlay together when they share UVs; drops anything else layered. */
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

    // Bit-exact on purpose: shared UVs only count when positions match exactly.
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
            int corner = corner(quad, v, axis, plane);
            if (corner < 0) return false;
            corners |= 1 << corner;
        }
        return corners == 15;
    }

    /* Which corner of the face a vertex sits on, or -1 when it is off the face's plane or corners. */
    private static int corner(BakedQuad quad, int vertex, int axis, float plane) {
        int corner = 0, bit = 0;
        for (int a = 0; a < 3; a++) {
            float coordinate = position(quad, vertex, a);
            if (a == axis) {
                if (Math.abs(coordinate - plane) > 0.001F) return -1;
                continue;
            }
            if (Math.min(Math.abs(coordinate), Math.abs(coordinate - 1)) > 0.001F) return -1;
            if (coordinate > 0.5F) corner |= 1 << bit;
            bit++;
        }
        return corner;
    }
}
