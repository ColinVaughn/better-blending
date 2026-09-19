package dev.betterblending;

import net.minecraft.core.Direction;

/*
 Blocks that stand alone: none of their exposed faces has the configured number of blocks of the same
 material beside it, exposed the same way. Depending on the selected style, a placed block or
 pair can be treated as a feature of its own. Protected blocks keep their texture and lend it
 only to the blocks right beside them.
 */
final class LoneBlocks {
    private static final Direction[] FACES = Direction.values();

    private LoneBlocks() {}

    /* Whether the sampled block at a halo index stands alone; strides are TerrainSections' per-face steps. */
    static boolean lone(int[] blocks, int[][] strides, int index, int width, int surfaceNeighbors) {
        int packed = blocks[index];
        // The halo's outer shell lacks the neighbors to tell, so it never counts as lone.
        if ((packed & 4095) == 0 || onShell(index, width)) return false;
        for (var face : FACES) {
            int f = face.get3DDataValue();
            if (TerrainSections.exposedOn(packed, f) && sameNeighbors(blocks, strides[f], index, packed, f) >= surfaceNeighbors) {
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
