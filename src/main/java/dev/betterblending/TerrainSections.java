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
        var position = new BlockPos.MutableBlockPos();
        var neighbor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < width; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            position.set(origin.getX() + x - radius, origin.getY() + y - radius, origin.getZ() + z - radius);
            var state = region.getBlockState(position);
            if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) continue;
            int exposed = 0;
            for (var face : FACES) {
                neighbor.setWithOffset(position, face);
                //? if >=26.1 {
                /*if (Block.shouldRenderFace(state, region.getBlockState(neighbor), face)) exposed |= 1 << face.get3DDataValue();
                *///?} else {
                if (Block.shouldRenderFace(state, region, position, face, neighbor)) exposed |= 1 << face.get3DDataValue();
                //?}
            }
            if (exposed == 0) continue; // Reuse Minecraft's face culling before model/tint work.
            long value = sample.applyAsLong(position);
            if ((value & 4095) == 0) continue;
            int index = (y * width + z) * width + x;
            blocks[index] = (int) value | exposed << 16;
            colors[index] = (int) (value >>> 32);
        }
        var result = new IntArrayList();
        for (int y = 0; y < width; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            int index = (y * width + z) * width + x, packed = blocks[index];
            if (packed == 0) continue;
            if (x >= radius && x < radius + 16 && y >= radius && y < radius + 16 && z >= radius && z < radius + 16) {
                if (radius > 0 && boundary(blocks, width, x, y, z, 1, packed)) packed |= 12288;
                else if (radius > 1 && boundary(blocks, width, x, y, z, radius, packed)) packed |= 8192;
            }
            result.add(index); result.add(packed); result.add(colors[index]);
        }
        return new Data(origin.getX(), origin.getY(), origin.getZ(), radius, result.toIntArray());
    }

    private static boolean boundary(int[] blocks, int width, int x, int y, int z, int radius, int packed) {
        int own = packed & 4095;
        for (var face : FACES) {
            int exposure = 1 << (16 + face.get3DDataValue());
            if ((packed & exposure) == 0) continue;
            for (int v = -radius; v <= radius; v++) for (int u = -radius; u <= radius; u++) for (int n = -1; n <= 1; n++) {
                int dx = face.getAxis() == Direction.Axis.X ? n : u;
                int dy = face.getAxis() == Direction.Axis.Y ? n : v;
                int dz = face.getAxis() == Direction.Axis.Z ? n : face.getAxis() == Direction.Axis.X ? u : v;
                int other = blocks[((y + dy) * width + z + dz) * width + x + dx];
                if ((other & exposure) != 0 && (other & 4095) != 0 && (other & 4095) != own) return true;
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
