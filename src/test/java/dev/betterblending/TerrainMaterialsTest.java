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

import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TerrainMaterialsTest {
    @Test
    void discoversMatchingBaseAndTintedOverlayInEitherQuadOrder() {
        var name = net.minecraft.resources.ResourceLocation.parse("example:layer");
        var size = new net.minecraft.client.resources.metadata.animation.FrameSize(16, 16);
        try (var baseImage = new net.minecraft.client.renderer.texture.SpriteContents(name, size,
                new com.mojang.blaze3d.platform.NativeImage(16, 16, false), net.minecraft.server.packs.resources.ResourceMetadata.EMPTY);
             var overlayImage = new net.minecraft.client.renderer.texture.SpriteContents(name, size,
                     new com.mojang.blaze3d.platform.NativeImage(16, 16, false), net.minecraft.server.packs.resources.ResourceMetadata.EMPTY)) {
            var baseSprite = new net.minecraft.client.renderer.texture.TextureAtlasSprite(name, baseImage, 32, 16, 0, 0) {};
            var overlaySprite = new net.minecraft.client.renderer.texture.TextureAtlasSprite(name, overlayImage, 32, 16, 16, 0) {};
            int[] baseVertices = new int[32], overlayVertices = new int[32];
            for (int v = 0; v < 4; v++) {
                float u = v == 1 || v == 2 ? 1 : 0, t = v >= 2 ? 1 : 0;
                for (int[] vertices : new int[][]{baseVertices, overlayVertices}) {
                    vertices[v * 8] = Float.floatToRawIntBits(u);
                    vertices[v * 8 + 1] = Float.floatToRawIntBits(1 - t);
                    vertices[v * 8 + 2] = Float.floatToRawIntBits(1);
                    vertices[v * 8 + 5] = Float.floatToRawIntBits(t);
                }
                baseVertices[v * 8 + 4] = Float.floatToRawIntBits(u / 2);
                overlayVertices[v * 8 + 4] = Float.floatToRawIntBits((1 + u) / 2);
            }
            var base = new BakedQuad(baseVertices, -1, Direction.SOUTH, baseSprite, true);
            var overlay = new BakedQuad(overlayVertices, 0, Direction.SOUTH, overlaySprite, true);
            var quads = new java.util.ArrayList<>(List.of(overlay, base));
            BakedModel model = (BakedModel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BakedModel.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isCustomRenderer" -> false;
                        case "getQuads" -> args[1] == Direction.SOUTH ? quads : List.of();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            for (int order = 0; order < 2; order++) {
                var faces = TerrainMaterials.faces(model, null);
                assertSame(base, faces[3]);
                assertSame(overlay, faces[9]);
                java.util.Collections.reverse(quads);
            }
            overlayVertices[4] = Float.floatToRawIntBits(0.75F);
            assertNull(TerrainMaterials.faces(model, null)[3], "Mismatched layer UVs must keep vanilla rendering");
            overlayVertices[4] = Float.floatToRawIntBits(0.5F);
            quads.add(overlay);
            assertNull(TerrainMaterials.faces(model, null)[3], "Unsupported extra layers must not be flattened");
        }
    }

    @Test
    void surfaceSamplingUsesClientHeightmapAndSkipsCanopiesWithoutSkippingWater() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        assertTrue(TerrainMaterials.SURFACE_HEIGHTMAP.sendToClient(), "Unsynchronized heightmaps sample air on real client chunks");
        BlockGetter column = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) {
                if (pos.getX() == 3) return Blocks.AIR.defaultBlockState();
                if (pos.getY() <= 64) return Blocks.SAND.defaultBlockState();
                if (pos.getY() == 72) return (pos.getX() == 1 ? Blocks.WATER : Blocks.OAK_LEAVES).defaultBlockState();
                return Blocks.AIR.defaultBlockState();
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };
        var pos = new BlockPos.MutableBlockPos();
        assertEquals(65, TerrainMaterials.surfaceHeight(column, pos, -17, -33, 65));
        assertEquals(65, TerrainMaterials.surfaceHeight(column, pos, 0, 0, 73));
        assertEquals(73, TerrainMaterials.surfaceHeight(column, pos, 1, 0, 73));
        assertEquals(-64, TerrainMaterials.surfaceHeight(column, pos, 3, 0, 73));
    }

    @Test
    void grassAndSolidGroundShareBlendingButTranslucentBlocksDoNot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var grassLayer = ItemBlockRenderTypes.getChunkRenderType(Blocks.GRASS_BLOCK.defaultBlockState());
        assertSame(RenderType.cutoutMipped(), grassLayer);
        assertTrue(TerrainMaterials.supportsLayer(grassLayer), "Grass was excluded by the old solid-only filter");
        assertTrue(TerrainMaterials.supportsLayer(ItemBlockRenderTypes.getChunkRenderType(Blocks.SAND.defaultBlockState())));
        assertTrue(TerrainMaterials.supportsLayer(RenderType.cutout()));
        assertFalse(TerrainMaterials.supportsLayer(RenderType.translucent()));
    }

    @Test
    void readsFacesFromModelsWithoutRegistryNamesAndRejectsPartialGeometry() {
        int[] vertices = new int[32];
        float[][] corners = {{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}};
        for (int v = 0; v < 4; v++) for (int axis = 0; axis < 3; axis++) vertices[v * 8 + axis] = Float.floatToRawIntBits(corners[v][axis]);
        BakedQuad quad = new BakedQuad(vertices, 0, Direction.UP, null, true);
        BakedModel modModel = (BakedModel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BakedModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isCustomRenderer" -> false;
                    case "getQuads" -> args[1] == Direction.UP ? List.of(quad) : List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertSame(quad, TerrainMaterials.faces(modModel, null)[Direction.UP.get3DDataValue()]);
        assertNull(TerrainMaterials.faces(modModel, null)[Direction.NORTH.get3DDataValue()]);
        BakedModel layeredModel = (BakedModel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BakedModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isCustomRenderer" -> false;
                    case "getQuads" -> args[1] == Direction.UP || args[1] == null ? List.of(quad) : List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertNull(TerrainMaterials.faces(layeredModel, null)[Direction.UP.get3DDataValue()],
                "Layered faces must retain both base and overlay, including unculled overlays");
        vertices[1] = Float.floatToRawIntBits(0.5F);
        assertFalse(TerrainMaterials.fullFace(quad), "Partial/custom geometry must not be repainted as a cube");
    }

    @Test
    void samplesNearbyNetherFloorsInsteadOfRoofAndBenchmarksColumnSearch() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockState air = Blocks.AIR.defaultBlockState(), rock = Blocks.NETHERRACK.defaultBlockState();
        BlockGetter fixture = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) {
                int floor = 64 + ((pos.getX() + pos.getZ()) & 3);
                return pos.getY() <= floor || pos.getY() == 88 || pos.getY() >= 120 ? rock : air;
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return 128; }
            @Override public int getMinBuildHeight() { return 0; }
        };
        var pos = new BlockPos.MutableBlockPos();
        assertEquals(65, TerrainMaterials.caveSurface(fixture, pos, 0, 0, 70));
        assertEquals(89, TerrainMaterials.caveSurface(fixture, pos, 0, 0, 92));
        if (!"1".equals(System.getenv("BB_SHADER_BENCHMARK"))) return;
        double[] timings = new double[40];
        long checksum = 0;
        for (int sample = -20; sample < timings.length; sample++) {
            long start = System.nanoTime();
            for (int z = 0; z < 256; z++) for (int x = 0; x < 256; x++) checksum += TerrainMaterials.caveSurface(fixture, pos, x, z, 70);
            if (sample >= 0) timings[sample] = (System.nanoTime() - start) / 1_000_000.0;
        }
        assertTrue(checksum > 0);
        Arrays.sort(timings);
        Path path = Path.of("build/shader-review/cpu-benchmark.txt");
        Files.createDirectories(path.getParent());
        Files.writeString(path, String.format(Locale.ROOT,
                "Synthetic Nether column search, 256x256 columns, up to 66 block-state reads per column; 20 warmups / 40 measurements.%nMedian %.3f ms; p95 %.3f ms per whole map.%nRuntime splits this work into soft 1 ms frame budgets; this fixture excludes real chunk lookup, model discovery, lighting and texture upload.%n",
                timings[20], timings[38]));
    }
}
