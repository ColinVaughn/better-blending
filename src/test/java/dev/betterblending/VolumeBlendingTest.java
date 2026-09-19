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

import dev.betterblending.backend.VanillaTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;

/*
 Player-scale scenes baked the way the game bakes them and drawn in volume mode, the mode
 every shipped build uses. Materials are flat colors (smooth stone red, dirt green), and each
 block's score is the share of its top face that shows the other material.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "BB_SHADER_GL_TEST", matches = "1")
class VolumeBlendingTest {
    static {
        // The block constants below need the registries.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final int SIZE = 512, VIEW = 56;
    private static final Path REVIEW = Path.of("build/shader-review/volume-blending");
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState(), STONE = Blocks.SMOOTH_STONE.defaultBlockState();

    interface Scene { BlockState at(int x, int y, int z); }

    private long window;
    private int program, atlas, table;

    /* A dirt floor, four blocks thick, with its top at y = 64. */
    private static BlockState floor(int x, int y, int z) {
        return y >= 60 && y <= 63 && x >= 32 && x < 96 && z >= 32 && z < 96 ? DIRT : Blocks.AIR.defaultBlockState();
    }

    private static Scene with(Scene base, Scene stone) {
        return (x, y, z) -> stone.at(x, y, z) != null ? stone.at(x, y, z) : base.at(x, y, z);
    }

    private static final Scene INSET = with(VolumeBlendingTest::floor, (x, y, z) -> x == 60 && y == 63 && z == 60 ? STONE : null);
    private static final Scene PLACED = with(VolumeBlendingTest::floor, (x, y, z) -> x == 60 && y == 64 && z == 60 ? STONE : null);
    private static final Scene PAIR = with(VolumeBlendingTest::floor, (x, y, z) -> (x == 60 || x == 61) && y == 63 && z == 60 ? STONE : null);
    // Regional samples fall on every fourth block; this one is sampled.
    private static final Scene ON_GRID = with(VolumeBlendingTest::floor, (x, y, z) -> x == 62 && y == 63 && z == 62 ? STONE : null);
    private static final Scene SPLIT = with(VolumeBlendingTest::floor, (x, y, z) -> x >= 64 && !floor(x, y, z).isAir() ? STONE : null);

    @BeforeAll
    void context() throws Exception {
        var shaders = new TerrainShaderTest();
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(SIZE, SIZE, "Better Blending volume review", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        org.lwjgl.opengl.GL.createCapabilities();
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        Files.createDirectories(REVIEW);
        program = TerrainShaderTest.program(shaders.resource("core/terrain.vsh"), shaders.resource("core/terrain.fsh"));
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE3);
        int output = TerrainShaderTest.texture(SIZE, SIZE, null);
        glBindFramebuffer(GL_FRAMEBUFFER, glGenFramebuffers());
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
        int depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        glEnable(GL_DEPTH_TEST);
        glViewport(0, 0, SIZE, SIZE);
        textures();
        uniforms();
    }

    @AfterAll
    void release() {
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Test
    void loneBlocksKeepTheirOwnTops() throws Exception {
        for (var scene : new Scene[]{INSET, PLACED}) {
            int[][] cover = render(scene, 0.45F, scene == PLACED ? "placed" : "inset");
            assertTrue(cover[60 - VIEW][60 - VIEW] <= 2, "A lone block's top must keep its texture: " + cover[60 - VIEW][60 - VIEW] + "%");
        }
    }

    @Test
    void adjacentBlocksBlendByDefault() throws Exception {
        int[][] cover = render(PAIR, 0.45F, "pair");
        assertTrue(cover[60 - VIEW][60 - VIEW] > 2 || cover[60 - VIEW][61 - VIEW] > 2,
                "At least one block of a pair must blend under the default style");
    }

    @Test
    void loneBlocksStillLendTheirNeighborsAnEdge() throws Exception {
        assertTrue(ring(render(INSET, 0, "inset-local"), 60, 60, 1) > 20, "Neighbors of a lone block must still blend with it");
    }

    @Test
    void neighborsOneStepDownBorrowLessThanLevelOnes() throws Exception {
        int level = ring(render(INSET, 0, "inset-local"), 60, 60, 1);
        int step = ring(render(PLACED, 0, "placed-local"), 60, 60, 1);
        assertTrue(step > 0, "A placed block must still lend the ground around it an edge");
        assertTrue(step < level * 0.75, "Ground one step below a block must borrow less of it than level ground: " + step + " vs " + level);
    }

    @Test
    void loneBlocksDoNotSeedRegionalPatches() throws Exception {
        int[][] cover = render(ON_GRID, 0.45F, "on-grid");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (Math.max(Math.abs(VIEW + x - 62), Math.abs(VIEW + z - 62)) < 2) continue;
            assertEquals(0, cover[z][x], "A lone block must not paint distant ground at " + (VIEW + x) + "," + (VIEW + z));
        }
    }

    @Test
    void regionalPatchesDoNotFillWholeBlocks() throws Exception {
        int[][] cover = render(SPLIT, 0.45F, "split");
        int reach = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (VIEW + x == 63 || VIEW + x == 64) continue;
            assertTrue(cover[z][x] <= 50, "A regional patch filled " + (VIEW + x) + "," + (VIEW + z) + ": " + cover[z][x] + "%");
            reach += cover[z][x];
        }
        assertTrue(reach > 0, "Regional blending must still reach past the boundary columns");
        for (int z = 0; z < 16; z++) {
            assertTrue(cover[z][63 - VIEW] > 0 || cover[z][64 - VIEW] > 0, "A straight boundary must blend along its length, row " + (VIEW + z));
        }
    }

    /*
     26.x samples terrain from a linearly filtered atlas and snaps to texels in the shader.
     Whatever blending samples itself (borrowed texels, and both layers of a layered face such
     as a grass side) has to come out as crisp as the host's own sample.
     */
    @Test
    void resampledTexturesStayCrispOnLinearAtlases() throws Exception {
        var shaders = new TerrainShaderTest();
        String fragment = shaders.resource("core/terrain.fsh");
        String host = fragment.replace("#define BB_SAMPLE_BASE(uv) texture(Sampler0, uv)", """
                vec4 hostNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
                    vec2 du = dFdx(uv), dv = dFdy(uv);
                    vec2 texelScreenSize = sqrt(du * du + dv * dv);
                    vec2 uvTexelCoords = uv / pixelSize;
                    vec2 texelCenter = round(uvTexelCoords) - 0.5;
                    vec2 texelOffset = clamp((uvTexelCoords - texelCenter - 0.5) * pixelSize / texelScreenSize + 0.5, 0.0, 1.0);
                    return textureGrad(source, (texelCenter + texelOffset) * pixelSize, du, dv);
                }
                #define BB_SAMPLE_BASE(uv) hostNearest(Sampler0, uv, 1.0 / vec2(textureSize(Sampler0, 0)))""");
        assertNotEquals(fragment, host, "The GL-era base sampler moved; update this host emulation");
        int shipped = program;
        program = TerrainShaderTest.program(shaders.resource("core/terrain.vsh"), host);
        glUseProgram(program);
        uniforms();
        linear(true);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, table);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 36, 4, GL_RGBA, GL_FLOAT, table(true));
        try {
            // Three blocks across the screen, about ten pixels to a texel.
            glUniformMatrix4fv(uniform("ProjMat"), false, new Matrix4f().ortho(-1.5F, 1.5F, -1.5F, 1.5F, 0.1F, 100).get(new float[16]));
            camera(52.5F);
            double plain = soft(frames(SPLIT, 0.45F, "linear-plain")[1]);
            camera(63.5F);
            double blended = soft(frames(SPLIT, 0.45F, "linear-boundary")[1]);
            assertTrue(plain < 0.3, "The host's own sample must be mostly crisp: " + plain);
            assertTrue(blended <= plain + 0.05, "Blended and layered faces must be as crisp as the host's own sample: "
                    + blended + " soft pixels vs " + plain);
        } finally {
            glActiveTexture(GL_TEXTURE4);
            glBindTexture(GL_TEXTURE_2D, table);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 36, 4, GL_RGBA, GL_FLOAT, table(false));
            linear(false);
            program = shipped;
            glUseProgram(program);
            uniforms();
        }
    }

    private void camera(float x) {
        glUniformMatrix4fv(uniform("ModelViewMat"), false, new Matrix4f().lookAt(x, 100, 60.5F, x, 60, 60.5F, 0, 0, -1).get(new float[16]));
    }

    private void linear(boolean linear) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    }

    /* The share of pixels that are no texel's color: filtered between texels, so soft. */
    private static double soft(float[] pixels) {
        var palette = new java.util.HashSet<Integer>();
        int low = Math.round(0.1F * 255);
        for (int k = 0; k < 5; k++) {
            int value = Math.round((0.55F + 0.35F * k / 4) * 255);
            palette.add(value << 16 | low << 8 | low);
            palette.add(low << 16 | value << 8 | low);
        }
        int soft = 0;
        for (int pixel = 0; pixel < SIZE * SIZE; pixel++) if (!palette.contains(TerrainShaderTest.rgb(pixels, pixel))) soft++;
        return soft / (double) (SIZE * SIZE);
    }

    /* Percent of each block's top in view (indexed [z][x]) that shows the other material. */
    private int[][] render(Scene scene, float regional, String name) throws Exception {
        float[][] frames = frames(scene, regional, name);
        return coverage(frames[0], frames[1]);
    }

    /* The scene drawn without blending and with it, from the current camera. */
    private float[][] frames(Scene scene, float regional, String name) throws Exception {
        glActiveTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(0);
        try (var volume = new TerrainVolume(128)) {
            volume.move(0, 0, 0);
            volume.prepare(bake(scene));
            glActiveTexture(GL_TEXTURE0);
            volume.upload();
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.blocks));
            glActiveTexture(GL_TEXTURE5);
            glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.colors));
            glActiveTexture(GL_TEXTURE7);
            glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.index));
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, atlas);
            int vertices = mesh(scene);
            glUniform1f(uniform("BiomeBlendStrength"), regional);
            glUniform1f(uniform("BlendStrength"), 0);
            float[] original = draw(vertices);
            glUniform1f(uniform("BlendStrength"), 1);
            float[] blended = draw(vertices);
            save(REVIEW.resolve(name + ".png"), blended);
            return new float[][]{original, blended};
        }
    }

    /* Every non-empty section around the scene, as the game would publish them. */
    private static ArrayList<TerrainSections.Data> bake(Scene scene) {
        BlockGetter level = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) { return scene.at(pos.getX(), pos.getY(), pos.getZ()); }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };
        var ready = new ArrayList<TerrainSections.Data>();
        for (int sy = 3; sy <= 4; sy++) for (int sz = 2; sz <= 5; sz++) for (int sx = 2; sx <= 5; sx++) {
            if (!occupied(scene, sx, sy, sz)) continue;
            ready.add(TerrainSections.bake(level, new BlockPos(sx * 16, sy * 16, sz * 16), 6, pos -> {
                int id = material(level.getBlockState(pos));
                return id == 0 ? 0 : 0xFFFFFFFF00000000L | id;
            }));
        }
        return ready;
    }

    private static boolean occupied(Scene scene, int sx, int sy, int sz) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (!scene.at(sx * 16 + x, sy * 16 + y, sz * 16 + z).isAir()) return true;
        }
        return false;
    }

    private static int material(BlockState state) {
        return state == STONE ? 1 : state == DIRT ? 2 : 0;
    }

    /* Summed coverage of the blocks at Chebyshev distance {@code distance} from a column. */
    private static int ring(int[][] cover, int cx, int cz, int distance) {
        int sum = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (Math.max(Math.abs(VIEW + x - cx), Math.abs(VIEW + z - cz)) == distance) sum += cover[z][x];
        }
        return sum;
    }

    private static int[][] coverage(float[] original, float[] blended) {
        int[][] changed = new int[16][16], total = new int[16][16];
        for (int py = 0; py < SIZE; py++) for (int px = 0; px < SIZE; px++) {
            int pixel = py * SIZE + px, x = px / 32, z = 15 - py / 32;
            total[z][x]++;
            if (red(original, pixel) != red(blended, pixel)) changed[z][x]++;
        }
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) changed[z][x] = Math.round(100F * changed[z][x] / total[z][x]);
        return changed;
    }

    private static boolean red(float[] pixels, int pixel) {
        return pixels[pixel * 4] > pixels[pixel * 4 + 1];
    }

    private int mesh(Scene scene) {
        var values = new ArrayList<Float>();
        for (int y = 58; y < 70; y++) for (int z = 50; z < 78; z++) for (int x = 50; x < 78; x++) {
            int id = material(scene.at(x, y, z));
            if (id == 0 || !scene.at(x, y + 1, z).isAir()) continue;
            face(values, id, new float[][]{{x, y + 1, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}});
        }
        return TerrainShaderTest.uploadMesh(program, values);
    }

    private static void face(ArrayList<Float> values, int id, float[][] corners) {
        float[][] uv = {{0.001F, 0.001F}, {0.999F, 0.001F}, {0.999F, 0.999F}, {0.001F, 0.999F}};
        for (int i : new int[]{0, 1, 2, 0, 2, 3}) {
            for (float coordinate : corners[i]) values.add(coordinate);
            values.add((id - 1 + uv[i][0]) / 3);
            values.add(uv[i][1]);
            values.add(0F); values.add(1F); values.add(0F);
            for (int c = 0; c < 4; c++) values.add(1F);
        }
    }

    private void textures() {
        // 16x16 sprites with some texel variation: red smooth stone, green dirt, and an empty
        // overlay that the crispness test layers over dirt.
        float[] pixels = new float[48 * 16 * 4];
        for (int sprite = 0; sprite < 2; sprite++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int pixel = (y * 48 + sprite * 16 + x) * 4;
            float value = 0.55F + 0.35F * ((x * 7 + y * 13) % 5) / 4;
            pixels[pixel] = sprite == 0 ? value : 0.1F;
            pixels[pixel + 1] = sprite == 1 ? value : 0.1F;
            pixels[pixel + 2] = 0.1F;
            pixels[pixel + 3] = 1;
        }
        glActiveTexture(GL_TEXTURE0);
        atlas = TerrainShaderTest.texture(48, 16, pixels);
        glActiveTexture(GL_TEXTURE4);
        table = TerrainShaderTest.texture(36, 4, table(false));
        float[] light = new float[16 * 16 * 4];
        java.util.Arrays.fill(light, 1);
        glActiveTexture(GL_TEXTURE2);
        TerrainShaderTest.texture(16, 16, light);
        float[] noise = new float[128 * 128 * 4];
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            noise[(y * 128 + x) * 4] = TerrainMaterials.noiseByte(x, y) / 255F;
            noise[(y * 128 + x) * 4 + 1] = TerrainMaterials.noiseByte(x + 37, y + 17) / 255F;
        }
        glActiveTexture(GL_TEXTURE6);
        TerrainShaderTest.texture(128, 128, noise);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }

    /* The material table, laid out the way TerrainMaterials.writeFace fills it. */
    private static float[] table(boolean layeredDirt) {
        float[] table = new float[36 * 4 * 4];
        for (int id = 1; id <= 2; id++) for (int face = 0; face < 6; face++) {
            rect(table, id, face, id - 1);
            if (id == 2 && layeredDirt) {
                table[(id * 36 + face * 3 + 2) * 4 + 2] = 1 / 255F;
                rect(table, id, face + 6, 2);
            }
        }
        return table;
    }

    private static void rect(float[] table, int id, int face, int sprite) {
        int pixel = (id * 36 + face * 3) * 4;
        table[pixel] = sprite * 16 / 255F;
        table[pixel + 4] = (sprite + 1) * 16 / 255F;
        table[pixel + 6] = 16 / 255F;
    }

    private void uniforms() {
        String[] samplers = {"Sampler0", "BiomeSampler", "Sampler2", "MaterialSampler", "SurfaceColors", "NoiseSampler", "VolumeSampler"};
        int[] units = {0, 1, 2, 4, 5, 6, 7};
        for (int i = 0; i < samplers.length; i++) glUniform1i(uniform(samplers[i]), units[i]);
        glUniform1f(uniform("VolumeMode"), 1);
        glUniform3f(uniform("VolumeOrigin"), 0, 0, 0);
        glUniform3f(uniform("ChunkOffset"), 0, 0, 0);
        glUniform3f(uniform("BiomeOffset"), 0, 0, 0);
        glUniform2f(uniform("NoiseOffset"), 0, 0);
        // The shipped defaults.
        glUniform1f(uniform("TextureAlignedBlending"), 1);
        glUniform1f(uniform("LocalBlendStrength"), 1);
        glUniform1f(uniform("SurfaceStrength"), 0);
        glUniform1f(uniform("AlphaCutoff"), 0);
        glUniform3f(uniform("SunDirection"), 0, 0.8F, 0.6F);
        glUniform4f(uniform("ColorModulator"), 1, 1, 1, 1);
        glUniform1f(uniform("FogStart"), 1000);
        glUniform1f(uniform("FogEnd"), 2000);
        glUniformMatrix4fv(uniform("ModelViewMat"), false,
                new Matrix4f().lookAt(64, 100, 64, 64, 60, 64, 0, 0, -1).get(new float[16]));
        glUniformMatrix4fv(uniform("ProjMat"), false, new Matrix4f().ortho(-8, 8, -8, 8, 0.1F, 100).get(new float[16]));
    }

    private int uniform(String name) {
        return glGetUniformLocation(program, name);
    }

    private static float[] draw(int vertices) {
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, vertices);
        float[] pixels = new float[SIZE * SIZE * 4];
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static void save(Path path, float[] pixels) throws Exception {
        var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) image.setRGB(x, SIZE - y - 1, TerrainShaderTest.rgb(pixels, y * SIZE + x));
        ImageIO.write(image, "png", path.toFile());
    }
}
