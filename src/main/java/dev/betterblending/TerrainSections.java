package dev.betterblending;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
//? if >=26.1 {
/*import net.minecraft.client.renderer.block.BlockAndTintGetter;
*///?} else {
import net.minecraft.world.level.BlockAndTintGetter;
//?}
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.ToLongFunction;

/** Immutable blend data built from the same snapshot and on the same task as the section mesh. */
public final class TerrainSections implements AutoCloseable {
    private static final Direction[] FACES = Direction.values();
    public record Data(int x, int y, int z, int radius, int[] voxels) {}
    private final Map<Object, Data> compiled = Collections.synchronizedMap(new WeakHashMap<>());
    final TerrainMaterials materials = new TerrainMaterials();
    private final int radius = BlendingConfig.INSTANCE.terrainBiomeBlendStrength() > 0 ? 6
            : BlendingConfig.INSTANCE.localBlendStrength() > 0 ? 1 : 0;
    private volatile boolean closed;

    public void compile(SectionPos section, BlockAndTintGetter region, Object mesh) {
        if (closed) return;
        Data data = bake(region, section.origin(), radius, position -> {
            var state = region.getBlockState(position);
            var minecraft = Minecraft.getInstance();
            int id = materials.material(minecraft, region, position, state);
            if (id == 0) return 0;
            int color = materials.tint(minecraft, region, position, state, id);
            int tint = 0xFF000000 | ((color & 255) << 16) | (color & 0xFF00) | ((color >> 16) & 255);
            return (long) tint << 32 | id;
        });
        if (!closed) compiled.put(mesh, data);
    }

    public Data get(Object mesh) { return compiled.get(mesh); }

    static Data bake(BlockGetter region, BlockPos origin, int radius, ToLongFunction<BlockPos> sample) {
        int width = 16 + radius * 2, length = width * width * width;
        int[] blocks = new int[length], colors = new int[length];
        sampleBlocks(region, origin, radius, sample, blocks, colors);
        return new Data(origin.getX(), origin.getY(), origin.getZ(), radius, pack(blocks, colors, width, radius));
    }

    private static void sampleBlocks(BlockGetter region, BlockPos origin, int radius, ToLongFunction<BlockPos> sample,
                                     int[] blocks, int[] colors) {
        int width = 16 + radius * 2;
        var position = new BlockPos.MutableBlockPos();
        var neighbor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < width; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            position.set(origin.getX() + x - radius, origin.getY() + y - radius, origin.getZ() + z - radius);
            long value = sampleExposed(region, position, neighbor, sample);
            if (value == 0) continue;
            int index = (y * width + z) * width + x;
            blocks[index] = (int) value;
            colors[index] = (int) (value >>> 32);
        }
    }

    /* Index, block and color of every sampled block, with boundary flags on the section's own blocks. */
    private static int[] pack(int[] blocks, int[] colors, int width, int radius) {
        int[][] strides = strides(width);
        var result = new IntArrayList();
        for (int y = 0; y < width; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            int index = (y * width + z) * width + x, packed = blocks[index];
            if (packed == 0) continue;
            if (inSection(x, y, z, radius)) packed |= boundaryBits(blocks, strides, index, radius, packed);
            result.add(index); result.add(packed); result.add(colors[index]);
        }
        return result.toIntArray();
    }

    /* The sample with the block's exposed faces in bits 16 to 21, or 0 when there is nothing to blend. */
    private static long sampleExposed(BlockGetter region, BlockPos.MutableBlockPos position,
                                      BlockPos.MutableBlockPos neighbor, ToLongFunction<BlockPos> sample) {
        var state = region.getBlockState(position);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return 0;
        int exposed = exposedFaces(region, position, neighbor, state);
        if (exposed == 0) return 0; // Reuse Minecraft's face culling before model/tint work.
        long value = sample.applyAsLong(position);
        if ((value & 4095) == 0) return 0;
        return value | (long) exposed << 16;
    }

    private static int exposedFaces(BlockGetter region, BlockPos position, BlockPos.MutableBlockPos neighbor, BlockState state) {
        int exposed = 0;
        for (var face : FACES) {
            neighbor.setWithOffset(position, face);
            //? if >=26.1 {
            /*if (Block.shouldRenderFace(state, region.getBlockState(neighbor), face)) exposed |= 1 << face.get3DDataValue();
            *///?} else {
            if (Block.shouldRenderFace(state, region, position, face, neighbor)) exposed |= 1 << face.get3DDataValue();
            //?}
        }
        return exposed;
    }

    /* Whether a halo coordinate lies in the section itself rather than its neighbors. */
    private static boolean inSection(int x, int y, int z, int radius) {
        return inside(x, radius) && inside(y, radius) && inside(z, radius);
    }

    private static boolean inside(int coordinate, int radius) {
        return coordinate >= radius && coordinate < radius + 16;
    }

    private static int boundaryBits(int[] blocks, int[][] strides, int index, int radius, int packed) {
        if (radius > 0 && boundary(blocks, strides, index, 1, packed)) return 12288;
        if (radius > 1 && boundary(blocks, strides, index, radius, packed)) return 8192;
        return 0;
    }

    /* Per face, the index steps along its normal and then its two tangents, u and v. */
    private static int[][] strides(int width) {
        int[][] strides = new int[FACES.length][];
        for (var face : FACES) {
            int x = 1, y = width * width, z = width;
            strides[face.get3DDataValue()] = switch (face.getAxis()) {
                case X -> new int[]{x, z, y};
                case Y -> new int[]{y, x, z};
                case Z -> new int[]{z, x, y};
            };
        }
        return strides;
    }

    private static boolean boundary(int[] blocks, int[][] strides, int index, int radius, int packed) {
        for (var face : FACES) {
            int exposure = 1 << (16 + face.get3DDataValue());
            if ((packed & exposure) != 0
                    && boundaryOnFace(blocks, strides[face.get3DDataValue()], index, radius, packed, exposure)) {
                return true;
            }
        }
        return false;
    }

    private static boolean boundaryOnFace(int[] blocks, int[] stride, int index, int radius, int packed, int exposure) {
        int own = packed & 4095;
        for (int v = -radius; v <= radius; v++) for (int u = -radius; u <= radius; u++) for (int n = -1; n <= 1; n++) {
            int other = blocks[index + n * stride[0] + u * stride[1] + v * stride[2]];
            if ((other & exposure) != 0 && (other & 4095) != 0 && (other & 4095) != own) return true;
        }
        return false;
    }

    @Override public void close() {
        closed = true;
        compiled.clear();
        materials.close();
    }
}
