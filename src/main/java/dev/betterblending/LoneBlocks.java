package dev.betterblending;

import net.minecraft.core.Direction;

/*
 Blocks that stand alone: none of their exposed faces has SURFACE_NEIGHBORS blocks of the same
 material beside it, exposed the same way. A placed block or a pair of them is a feature of its
 own, not part of a surface that should fade into the next one. Lone blocks keep their texture
 and lend it only to the blocks right beside them.
 */
final class LoneBlocks {
    private static final Direction[] FACES = Direction.values();
    static final int SURFACE_NEIGHBORS = 2;

    private LoneBlocks() {}

    /* Whether the sampled block at a halo index stands alone; strides are TerrainSections' per-face steps. */
    static boolean lone(int[] blocks, int[][] strides, int index, int width) {
        int packed = blocks[index];
        // The halo's outer shell lacks the neighbors to tell, so it never counts as lone.
        if ((packed & 4095) == 0 || onShell(index, width)) return false;
        for (var face : FACES) {
            int f = face.get3DDataValue();
            if (TerrainSections.exposedOn(packed, f) && sameNeighbors(blocks, strides[f], index, packed, f) >= SURFACE_NEIGHBORS) {
                return false;
            }
        }
        return true;
    }

    private static int sameNeighbors(int[] blocks, int[] stride, int index, int packed, int face) {
        int count = 0;
        for (int v = -1; v <= 1; v++) for (int u = -1; u <= 1; u++) {
            int other = blocks[index + u * stride[1] + v * stride[2]];
            if ((u != 0 || v != 0) && TerrainSections.exposedOn(other, face) && (other & 4095) == (packed & 4095)) count++;
        }
        return count;
    }

    private static boolean onShell(int index, int width) {
        return edge(index % width, width) || edge(index / width % width, width) || edge(index / (width * width), width);
    }

    private static boolean edge(int coordinate, int width) {
        return coordinate == 0 || coordinate == width - 1;
    }
}
