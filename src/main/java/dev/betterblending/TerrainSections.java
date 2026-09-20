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
    /*
     A baked section: voxels holds (halo index, packed block, tint) for every sampled block;
     hints holds one int per air or buried block of the section beside a surface, its halo
     index in bits 0-14 and its 12 probe-hint bits above (see compactHints).
     */
    public record Data(int x, int y, int z, int radius, int[] voxels, int[] hints) {
        interface VoxelSink { void accept(int x, int y, int z, int block, int color); }

        /* Every stored block in world coordinates: sampled blocks with their tint, then hint-only blocks. */
        void forEachVoxel(VoxelSink sink) {
            int width = 16 + radius * 2;
            for (int i = 0; i < voxels.length; i += 3) at(voxels[i], width, voxels[i + 1], voxels[i + 2], sink);
            for (int entry : hints) at(entry & 0x7FFF, width, expandHints(entry), 0, sink);
        }

        private void at(int index, int width, int block, int color, VoxelSink sink) {
            sink.accept(x + index % width - radius, y + index / (width * width) - radius, z + index / width % width - radius, block, color);
        }
    }
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
        boolean[] occluding = new boolean[length];
        sampleBlocks(region, origin, radius, sample, blocks, colors, occluding);
        return pack(origin, blocks, colors, occluding, width, radius);
    }

    private static void sampleBlocks(BlockGetter region, BlockPos origin, int radius, ToLongFunction<BlockPos> sample,
                                     int[] blocks, int[] colors, boolean[] occluding) {
        int width = 16 + radius * 2;
        boolean blendLeaves = BlendingConfig.INSTANCE.blendLeaves();
        var position = new BlockPos.MutableBlockPos();
        var neighbor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < width; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            position.set(origin.getX() + x - radius, origin.getY() + y - radius, origin.getZ() + z - radius);
            var state = region.getBlockState(position);
            long value = sampleExposed(region, position, neighbor, state, sample, blendLeaves);
            if (value == 0) continue;
            int index = (y * width + z) * width + x;
            blocks[index] = (int) value;
            colors[index] = (int) (value >>> 32);
            occluding[index] = state.canOcclude();
        }
    }

    /*
     Index, block and color of every sampled block, with boundary flags on the section's own blocks.
     A lone block's color has alpha 0: the shader then skips it as a regional donor.
     */
    private static Data pack(BlockPos origin, int[] blocks, int[] colors, boolean[] occluding, int width, int radius) {
        int[][] strides = strides(width);
        int surfaceNeighbors = BlendingConfig.INSTANCE.blendingStyle().surfaceNeighbors;
        var voxels = new IntArrayList();
        var hints = new IntArrayList();
        for (int y = 0; y < width; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            int index = (y * width + z) * width + x;
            boolean lone = LoneBlocks.lone(blocks, strides, index, width, surfaceNeighbors);
            int packed = inSection(x, y, z, radius)
                    ? ownEntry(blocks, occluding, strides, index, radius, lone) : blocks[index];
            store(voxels, hints, index, packed, lone ? colors[index] & 0xFFFFFF : colors[index]);
        }
        return new Data(origin.getX(), origin.getY(), origin.getZ(), radius, voxels.toIntArray(), hints.toIntArray());
    }

    private static void store(IntArrayList voxels, IntArrayList hints, int index, int packed, int color) {
        if ((packed & 4095) != 0) {
            voxels.add(index); voxels.add(packed); voxels.add(color);
        } else if (packed != 0) {
            hints.add(compactHints(index, packed));
        }
    }

    /* A hint-only block in one int: its halo index (under 28 cubed) in bits 0-14, its 12 hint bits above. */
    static int compactHints(int index, int packed) {
        return index | (packed >>> 22 | (packed >>> 14 & 3) << 10) << 15;
    }

    /* The hint bits of a compact entry, back where a packed block keeps them. */
    static int expandHints(int entry) {
        int hints = entry >>> 15;
        return (hints & 1023) << 22 | (hints >>> 10) << 14;
    }

    /*
     A block of the section itself, with boundary flags and probe hints; air and buried blocks carry hints alone.
     Lone blocks get no boundary flags, so they keep their own texture.
     */
    private static int ownEntry(int[] blocks, boolean[] occluding, int[][] strides, int index, int radius, boolean lone) {
        int packed = blocks[index];
        if (packed != 0 && !lone) packed |= boundaryBits(blocks, occluding, strides, index, radius, packed);
        return radius > 0 ? packed | probeHints(blocks, strides, index) : packed;
    }

    /*
     Probe hints share the packed block with its material (bits 0-11), boundary flags (12-13)
     and exposed faces (16-21): two bits per face, at 22 + 2 * face for faces 0-4 and at 14 for face 5.
     */
    static int hintShift(int face) {
        return face < 5 ? 22 + 2 * face : 14;
    }

    /*
     Per face, where a surface lookup that finds this block unexposed should read next: 1 when
     the block one step inward along the face's normal is exposed on that face, else 2 when the
     block one step outward is, else 0 for neither. The shader then reads one block, not three.
     */
    private static int probeHints(int[] blocks, int[][] strides, int index) {
        int hints = 0;
        for (var face : FACES) {
            int f = face.get3DDataValue();
            int step = strides[f][0] * face.getAxisDirection().getStep();
            int code = exposedOn(blocks[index - step], f) ? 1 : exposedOn(blocks[index + step], f) ? 2 : 0;
            hints |= code << hintShift(f);
        }
        return hints;
    }

    static boolean exposedOn(int packed, int face) {
        return (packed & 4095) != 0 && (packed >> (16 + face) & 1) != 0;
    }

    /* The sample with the block's exposed faces in bits 16 to 21, or 0 when there is nothing to blend. */
    private static long sampleExposed(BlockGetter region, BlockPos.MutableBlockPos position,
                                      BlockPos.MutableBlockPos neighbor, BlockState state,
                                      ToLongFunction<BlockPos> sample, boolean blendLeaves) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return 0;
        if (!takesPart(region, position, neighbor, state, blendLeaves)) return 0;
        int exposed = exposedFaces(region, position, neighbor, state);
        if (exposed == 0) return 0; // Reuse Minecraft's face culling before model/tint work.
        long value = sample.applyAsLong(position);
        if ((value & 4095) == 0) return 0;
        return value | (long) exposed << 16;
    }

    /*
     Blocks that do not occlude never cull each other, so a leaf cluster exposes every face it
     has, interior ones included, and the section keeps thousands of blocks the camera can
     barely see. Only the shell of such a cluster, the blocks that touch air, takes part in
     blending; the rest renders as it would without the mod, and blend_leaves drops the shell too.
     */
    private static boolean takesPart(BlockGetter region, BlockPos position, BlockPos.MutableBlockPos neighbor,
                                     BlockState state, boolean blendLeaves) {
        if (state.canOcclude()) return true;
        if (!blendLeaves) return false;
        for (var face : FACES) {
            neighbor.setWithOffset(position, face);
            if (region.getBlockState(neighbor).isAir()) return true;
        }
        return false;
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

    private static int boundaryBits(int[] blocks, boolean[] occluding, int[][] strides, int index, int radius, int packed) {
        if (radius > 0 && boundary(blocks, occluding, strides, index, 1, packed)) return 12288;
        if (radius > 1 && boundary(blocks, occluding, strides, index, radius, packed)) return 8192;
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

    private static boolean boundary(int[] blocks, boolean[] occluding, int[][] strides, int index, int radius, int packed) {
        for (var face : FACES) {
            int exposure = 1 << (16 + face.get3DDataValue());
            if ((packed & exposure) != 0 && boundaryOnFace(blocks, occluding,
                    strides[face.get3DDataValue()], index, radius, packed, exposure)) {
                return true;
            }
        }
        return false;
    }

    /*
     Leaves and solid terrain are never a boundary for each other, whichever side is asking: a
     trunk must not take on the canopy pressed against it, nor the canopy the trunk. Two blocks
     of the same kind still blend, so one species of leaf meets another as usual.
     */
    private static boolean boundaryOnFace(int[] blocks, boolean[] occluding, int[] stride, int index,
                                          int radius, int packed, int exposure) {
        int own = packed & 4095;
        boolean occludes = occluding[index];
        for (int v = -radius; v <= radius; v++) for (int u = -radius; u <= radius; u++) for (int n = -1; n <= 1; n++) {
            int neighbor = index + n * stride[0] + u * stride[1] + v * stride[2];
            int other = blocks[neighbor];
            if ((other & exposure) != 0 && (other & 4095) != 0 && (other & 4095) != own
                    && occluding[neighbor] == occludes) {
                return true;
            }
        }
        return false;
    }

    @Override public void close() {
        closed = true;
        compiled.clear();
        materials.close();
    }
}
