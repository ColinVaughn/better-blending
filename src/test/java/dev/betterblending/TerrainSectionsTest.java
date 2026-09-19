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

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainSectionsTest {
    private static final int RADIUS = 6, WIDTH = 16 + RADIUS * 2;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void blendingStylesProtectTheIntendedFeatureSizes() {
        int width = 5, center = (2 * width + 2) * width + 2;
        int[] blocks = new int[width * width * width];
        int[][] strides = new int[Direction.values().length][];
        int face = Direction.UP.get3DDataValue();
        strides[face] = new int[]{width * width, 1, width};
        blocks[center] = 1 | 1 << (16 + face);

        assertFalse(LoneBlocks.lone(blocks, strides, center, width, 0), "Full blending protects nothing");
        assertTrue(LoneBlocks.lone(blocks, strides, center, width, 1), "The default protects one block");
        blocks[center + 1] = blocks[center];
        assertFalse(LoneBlocks.lone(blocks, strides, center, width, 1), "Adjacent blocks blend by default");
        assertTrue(LoneBlocks.lone(blocks, strides, center, width, 2), "Small-feature mode also protects a pair");
    }

    /* Stone up to y = 3 west of x = 8 and up to y = 4 from there: one step, facing west. */
    private static final BlockGetter STEP = new BlockGetter() {
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return (pos.getY() < (pos.getX() < 8 ? 4 : 5) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    };

    @Test
    void probeHintsPointAtTheExposedNeighborAlongEachFace() {
        var data = TerrainSections.bake(STEP, BlockPos.ZERO, RADIUS, pos -> 0xFFFFFFFF00000000L | 1);
        var packed = new Int2IntOpenHashMap();
        // Through forEachVoxel, so compact hint entries are read back the way the volume reads them.
        data.forEachVoxel((x, y, z, block, color) -> packed.put(index(x, y, z), block));

        // Top faces: air over the low ground finds the surface one step inward (below),
        // stone under the high ground one step outward (above), deep stone and open air nothing.
        assertEquals(1, hint(packed, 3, 4, 3, Direction.UP));
        assertEquals(2, hint(packed, 10, 3, 3, Direction.UP));
        assertEquals(0, hint(packed, 10, 1, 3, Direction.UP));
        assertEquals(0, hint(packed, 3, 7, 3, Direction.UP));
        // The step's west-facing wall at x = 8, y = 4: the air in front of it looks inward
        // (east), the stone behind it outward (west).
        assertEquals(1, hint(packed, 7, 4, 3, Direction.WEST));
        assertEquals(2, hint(packed, 9, 4, 3, Direction.WEST));
        assertEquals(0, hint(packed, 7, 4, 3, Direction.EAST));
        // An exposed block keeps its own data alongside the hints.
        int surface = packed.get(index(10, 4, 3));
        assertEquals(1, surface & 4095);
        assertNotEquals(0, surface & (1 << (16 + Direction.UP.get3DDataValue())));
    }

    @Test
    void probeHintsAgreeWithTheExposedFacesTheyDescribe() {
        var data = TerrainSections.bake(STEP, BlockPos.ZERO, RADIUS, pos -> 0xFFFFFFFF00000000L | 1);
        var packed = new Int2IntOpenHashMap();
        // Through forEachVoxel, so compact hint entries are read back the way the volume reads them.
        data.forEachVoxel((x, y, z, block, color) -> packed.put(index(x, y, z), block));
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            for (var face : Direction.values()) {
                int inward = exposed(packed, x - face.getStepX(), y - face.getStepY(), z - face.getStepZ(), face) ? 1 : 0;
                int outward = exposed(packed, x + face.getStepX(), y + face.getStepY(), z + face.getStepZ(), face) ? 2 : 0;
                assertEquals(inward != 0 ? 1 : outward, hint(packed, x, y, z, face), x + "," + y + "," + z + " " + face);
            }
        }
    }

    /* A dirt floor with its top at y = 4, holding one smooth stone block and a 3x3 patch of it. */
    private static final BlockGetter FEATURES = new BlockGetter() {
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (pos.getY() > 3) return Blocks.AIR.defaultBlockState();
            boolean lone = pos.getX() == 4 && pos.getZ() == 4;
            boolean patch = pos.getX() >= 10 && pos.getX() <= 12 && pos.getZ() >= 10 && pos.getZ() <= 12;
            return (pos.getY() == 3 && (lone || patch) ? Blocks.SMOOTH_STONE : Blocks.DIRT).defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    };

    @Test
    void loneBlocksNeitherReceiveNorLendRegionally() {
        var data = TerrainSections.bake(FEATURES, BlockPos.ZERO, RADIUS,
                pos -> 0xFFFFFFFF00000000L | (FEATURES.getBlockState(pos).is(Blocks.SMOOTH_STONE) ? 1 : 2));
        var packed = new Int2IntOpenHashMap();
        var colors = new Int2IntOpenHashMap();
        data.forEachVoxel((x, y, z, block, color) -> {
            packed.put(index(x, y, z), block);
            colors.put(index(x, y, z), color);
        });
        int lone = index(4, 3, 4);
        assertEquals(0, packed.get(lone) & 12288, "A lone block must not receive blending");
        assertEquals(0, colors.get(lone) >>> 24, "A lone block must be marked so it lends no regional patches");
        assertEquals(1, packed.get(lone) & 4095, "A lone block keeps its material, so its neighbors still borrow from it");
        for (int[] block : new int[][]{{5, 3, 4}, {10, 3, 10}, {11, 3, 11}}) {
            int i = index(block[0], block[1], block[2]);
            assertNotEquals(0, packed.get(i) & 12288, "Blocks that are part of a surface still blend: " + java.util.Arrays.toString(block));
            assertEquals(0xFF, colors.get(i) >>> 24, "Blocks that are part of a surface still lend regionally");
        }
    }

    private static int index(int x, int y, int z) {
        return ((y + RADIUS) * WIDTH + z + RADIUS) * WIDTH + x + RADIUS;
    }

    private static int hint(Int2IntOpenHashMap packed, int x, int y, int z, Direction face) {
        return packed.get(index(x, y, z)) >>> TerrainSections.hintShift(face.get3DDataValue()) & 3;
    }

    private static boolean exposed(Int2IntOpenHashMap packed, int x, int y, int z, Direction face) {
        int value = packed.get(index(x, y, z));
        return (value & 4095) != 0 && (value & (1 << (16 + face.get3DDataValue()))) != 0;
    }
}
