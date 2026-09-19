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
import dev.betterblending.backend.VanillaBackend;
import dev.betterblending.backend.VanillaTexture;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;

class TerrainShaderTest {
    static {
        Backend.install(new VanillaBackend());
    }

    private static final int SIZE = 512;
    static boolean byteTextures = "1".equals(System.getenv("BB_TERRAIN_PROFILE"));
    private Fixture fixture = Fixture.MIXED;
    private boolean uniformScene;
    private final Set<Integer> palette = new HashSet<>();

    @Test
    @EnabledIfEnvironmentVariable(named = "BB_SHADER_GL_TEST", matches = "1")
    void rendererFragmentsMatchVanillaBlendingAndCanBeDisabled() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(SIZE, SIZE, "Renderer blend parity", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glViewport(0, 0, SIZE, SIZE);
            glEnable(GL_DEPTH_TEST);
            float[] reference = null;
            var renderers = new java.util.ArrayList<String>();
            renderers.add("vanilla");
            renderers.addAll(RendererShaderTest.RENDERERS.keySet());
            renderers.add("iris");
            for (String renderer : renderers) {
                String vertex = resource("core/terrain.vsh");
                String fragment = resource("core/terrain.fsh");
                if (renderer.equals("iris")) {
                    vertex = vertex.replace("biomePosition", "bb_biomePosition");
                    fragment = dev.betterblending.compat.IrisShaders.fragment("""
                            #version 150
                            uniform sampler2D gtexture;
                            in vec2 texCoord0;
                            in vec4 vertexColor;
                            out vec4 fragColor;
                            void main() { fragColor = texture(gtexture, texCoord0) * vertexColor; }
                            """);
                } else if (!renderer.equals("vanilla")) {
                    vertex = vertex.replace("#version 150", "#version 330 core")
                            .replace("vertexColor", "v_Color").replace("vertexDistance", "v_FragDistance")
                            .replace("texCoord0", "v_TexCoord").replace("void main()", "void fixture_main()");
                    // Rubidium's fragment stage takes tint and shade as one vec3 instead.
                    vertex += "\nout float v_MaterialMipBias; out float v_MaterialAlphaCutoff; out vec3 v_ColorModulator;\n"
                            + "void main() { fixture_main(); v_MaterialMipBias = 0.0; v_MaterialAlphaCutoff = 0.0;"
                            + " v_ColorModulator = v_Color.rgb * v_Color.a; }\n";
                    fragment = RendererShaderTest.patched(renderer, "fsh").replace("#version 330 core",
                            "#version 330 core\n#define USE_FOG\n#define USE_VANILLA_COLOR_FORMAT");
                }
                int program = program(vertex, fragment);
                glUseProgram(program);
                uniforms(program);
                if (renderer.equals("iris")) {
                    glUniform1f(glGetUniformLocation(program, "bb_LocalBlendStrength"), 1);
                    glUniform1f(glGetUniformLocation(program, "bb_BlendStrength"), 1);
                    glUniform1i(glGetUniformLocation(program, "bb_BiomeSampler"), 1);
                    glUniform1i(glGetUniformLocation(program, "bb_MaterialSampler"), 4);
                    glUniform1i(glGetUniformLocation(program, "bb_SurfaceColors"), 5);
                    glUniform1i(glGetUniformLocation(program, "bb_NoiseSampler"), 6);
                }
                glUniform1i(glGetUniformLocation(program, "bb_Enabled"), 1);
                glUniform1i(glGetUniformLocation(program, "u_BlockTex"), 0);
                glUniform1f(glGetUniformLocation(program, "u_FogStart"), 1000);
                glUniform1f(glGetUniformLocation(program, "u_FogEnd"), 2000);
                atlas();
                glActiveTexture(GL_TEXTURE2);
                float[] light = new float[16 * 16 * 4];
                java.util.Arrays.fill(light, 1);
                texture(16, 16, light);
                glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
                glActiveTexture(GL_TEXTURE1);
                texture(128, 128, surfaceMap(false, false));
                glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
                camera(program, false);
                int vertices = mesh(program, false, false);
                float[] blended = draw(vertices);
                if (reference == null) reference = blended;
                else {
                    int different = 0;
                    for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                        if (rgb(reference, pixel) != rgb(blended, pixel)) different++;
                    }
                    assertTrue(different < SIZE * SIZE / 100, renderer + " differs in " + different + " pixels");
                    glUniform1i(glGetUniformLocation(program, "bb_Enabled"), 0);
                    float[] ordinary = draw(vertices);
                    int changed = 0;
                    for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                        if (rgb(ordinary, pixel) != rgb(blended, pixel)) changed++;
                    }
                    assertTrue(changed > 100, renderer + " must visibly blend terrain");
                }
                glDeleteProgram(program);
            }
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    @Test
    void allDimensionsDefaultOnAndRespectExclusions() {
        var config = new BlendingConfig();
        var custom = net.minecraft.resources.ResourceKey.<net.minecraft.world.level.Level>create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse("example:custom"));
        assertTrue(config.dimensionEnabled(net.minecraft.world.level.Level.OVERWORLD));
        assertTrue(config.dimensionEnabled(net.minecraft.world.level.Level.NETHER));
        assertTrue(config.dimensionEnabled(net.minecraft.world.level.Level.END));
        assertTrue(config.dimensionEnabled(custom));
        config.disabled_dimensions.add("example:custom");
        assertFalse(config.dimensionEnabled(custom));
        config.vanilla_terrain_shader_enabled = false;
        assertFalse(config.dimensionEnabled(net.minecraft.world.level.Level.OVERWORLD));
        assertFalse(config.dimensionEnabled(net.minecraft.world.level.Level.NETHER));
        assertTrue(config.dimensionEnabled(net.minecraft.world.level.Level.END));
        config.enabled = false;
        assertFalse(config.dimensionEnabled(net.minecraft.world.level.Level.END));
        config.disabled_dimensions.clear();
        assertFalse(config.dimensionEnabled(custom));
    }

    /* Render the shipped shader stages with real atlas textures and block geometry. */
    @Test
    @EnabledIfEnvironmentVariable(named = "BB_SHADER_GL_TEST", matches = "1")
    void actualSurfaceBoundaryKeepsCrispTexturesAndDoesNotRepaintInteriors() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(SIZE, SIZE, "Better Blending material review", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            // Once per JVM; the shader audit shares it.
            if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            var previousConfig = BlendingConfig.INSTANCE;
            try {
                BlendingConfig.INSTANCE = new BlendingConfig();
                BlendingConfig.INSTANCE.excluded_blocks.add("minecraft:stone");
                try (var materials = new TerrainMaterials()) {
                    for (String id : BlendingConfig.INSTANCE.excluded_blocks) {
                        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                net.minecraft.resources.ResourceLocation.parse(id));
                        for (var state : block.getStateDefinition().getPossibleStates()) {
                            assertEquals(0, materials.material(null, null, net.minecraft.core.BlockPos.ZERO, state),
                                    id + " must not enter the surface map as a receiver or donor");
                        }
                    }
                }
            } finally {
                BlendingConfig.INSTANCE = previousConfig;
            }
            int program = program(resource("core/terrain.vsh"), resource("core/terrain.fsh"));
            glUseProgram(program);
            uniforms(program);
            atlas();
            glActiveTexture(GL_TEXTURE2);
            float[] light = new float[16 * 16 * 4];
            java.util.Arrays.fill(light, 1);
            texture(16, 16, light);
            glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
            glActiveTexture(GL_TEXTURE1);
            int map = texture(128, 128, surfaceMap(false, false));
            glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
            glActiveTexture(GL_TEXTURE3);
            int output = texture(SIZE, SIZE, null);
            glBindFramebuffer(GL_FRAMEBUFFER, glGenFramebuffers());
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
            int depth = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
            glEnable(GL_DEPTH_TEST);
            glViewport(0, 0, SIZE, SIZE);
            if ("1".equals(System.getenv("BB_TERRAIN_PROFILE"))) {
                profileTerrain(program, output, depth);
                return;
            }
            int vertices = mesh(program, false, false);
            camera(program, false);
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
            float[] original = draw(vertices);
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
            float[] blended = draw(vertices);
            glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), 0);
            assertArrayEquals(original, draw(vertices), "Local strength zero must bypass local material transitions");
            glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), 1);
            Path review = Path.of("build/shader-review");
            Files.createDirectories(review);
            save(review.resolve("original.png"), original);
            save(review.resolve("revised.png"), blended);
            int changed = 0;
            int[] changedMaterials = new int[8];
            int[] stoneBesideIce = new int[2];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int pixel = y * SIZE + x;
                    assertTrue(palette.contains(rgb(blended, pixel)), "Close-up blending must select real texture colors, without a pale crossfade");
                    boolean different = rgb(original, pixel) != rgb(blended, pixel);
                    if (different) changed++;
                    int worldX = 24 + x / 32;
                    int worldZ = 39 - y / 32;
                    int ownMaterial = material(worldX, worldZ);
                    if (different) changedMaterials[ownMaterial]++;
                    boolean interior = true;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int neighbor = material(worldX + dx, worldZ + dz);
                            interior &= ownMaterial == neighbor;
                            if (different && ownMaterial == 1 && neighbor >= 6) stoneBesideIce[neighbor - 6]++;
                        }
                    }
                    if (interior) assertFalse(different, "Uniform surface interiors must remain exact, including isolated material patches");
                }
            }
            assertTrue(changed > 500 && changed < SIZE * SIZE / 4, "Only a narrow part of the real material boundary should change");
            for (int ice = 6; ice <= 7; ice++) {
                assertTrue(changedMaterials[ice] > 0, "Stone must blend onto ice material " + ice);
                assertTrue(stoneBesideIce[ice - 6] > 0, "Ice material " + ice + " must blend onto neighboring stone");
            }

            vertices = mesh(program, false, true);
            assertArrayEquals(original, draw(vertices), 0.00001F, "Undersides must retain their material");
            vertices = mesh(program, false, false);

            // A stale/missing column or a face well below the surface cannot be painted.
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, map);
            float[] missing = new float[128 * 128 * 4];
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, missing);
            assertArrayEquals(original, draw(vertices), 0.00001F, "Unloaded columns must retain their source");
            float[] high = surfaceMap(false, false);
            for (int i = 2; i < high.length; i += 4) high[i] = 100 / 255.0F;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, high);
            assertArrayEquals(original, draw(vertices), 0.00001F, "Caves must retain their material");

            // Recenter by one chunk: both the samples and patch pattern must stay fixed.
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, true));
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 16, 0, 0);
            glUniform2f(glGetUniformLocation(program, "NoiseOffset"), TerrainShader.wrappedCoordinate(-16), 0);
            assertArrayEquals(blended, draw(vertices), 0.001F, "Camera chunk crossings must not move the pattern");
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 0, 0);
            glUniform2f(glGetUniformLocation(program, "NoiseOffset"), 0, 0);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, false));
            // A reconstructed horizontal face can straddle its exact integer
            // height by one float ULP during movement. Its grain must stay fixed.
            for (float error : new float[]{-0.0001F, -0.00001F, 0.00001F, 0.0001F}) {
                glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, error, 0);
                assertArrayEquals(blended, draw(vertices), 0.001F, "Camera rounding must not change the surface grain slice");
            }
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 0, 0);

            glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), 0.45F);
            float[] regional = draw(vertices);
            int extended = 0;
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                int pixel = y * SIZE + x;
                assertTrue(palette.contains(rgb(regional, pixel)), "Wide transitions must still use intact texture colors");
                int wx = 24 + x / 32, wz = 39 - y / 32, own = material(wx, wz);
                boolean localEdge = false, wideEdge = false;
                for (int dz = -7; dz <= 7; dz++) for (int dx = -7; dx <= 7; dx++) {
                    if (material(wx + dx, wz + dz) == own) continue;
                    wideEdge = true;
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) localEdge = true;
                }
                if (!wideEdge) assertEquals(rgb(original, pixel), rgb(regional, pixel), "Distant interiors must stay authored");
                if (!localEdge && rgb(blended, pixel) != rgb(regional, pixel)) extended++;
            }
            assertTrue(extended > 500, "The regional layer must reach beyond immediate block neighbors");
            save(review.resolve("combined-blending.png"), regional);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, true));
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 16, -0.0001F, 0);
            glUniform2f(glGetUniformLocation(program, "NoiseOffset"), TerrainShader.wrappedCoordinate(-16), 0);
            assertArrayEquals(regional, draw(vertices), 0.001F, "Regional patches must survive recentering and camera rounding");
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 0, 0);
            glUniform2f(glGetUniformLocation(program, "NoiseOffset"), 0, 0);
            glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), 0);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, false));
            assertArrayEquals(blended, draw(vertices), 0.001F, "Disabling the regional layer must restore the block-only version");

            // View the side of a single step directly; changes must not run down it as stripes.
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, false));
            vertices = wall(program);
            glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false,
                    new Matrix4f().lookAt(32, 79.5F, 40, 32, 79.5F, 31, 0, 1, 0).get(new float[16]));
            glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false,
                    new Matrix4f().ortho(-3, 3, -0.5F, 0.5F, 0.1F, 100).get(new float[16]));
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
            float[] wallOriginal = draw(vertices);
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
            float[] wallBlend = draw(vertices);
            save(review.resolve("original-wall.png"), wallOriginal);
            save(review.resolve("revised-wall.png"), wallBlend);
            int brokenColumns = 0;
            for (int x = 0; x < SIZE; x++) {
                boolean wasChanged = false;
                int breaks = 0;
                for (int y = 0; y < SIZE; y++) {
                    int pixel = y * SIZE + x;
                    boolean isChanged = rgb(wallOriginal, pixel) != rgb(wallBlend, pixel);
                    if (y < SIZE / 2) assertFalse(isChanged, "The lower wall must keep its rock texture at " + x + "," + y);
                    if (y > SIZE / 2 && isChanged != wasChanged) breaks++;
                    wasChanged = isChanged;
                }
                if (breaks > 2) brokenColumns++;
            }
            assertTrue(brokenColumns > 0, "Side-face grain must vary vertically instead of forming stripes");

            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(true, false));
            vertices = mesh(program, true, false);
            camera(program, true);
            save(review.resolve("revised-steps.png"), draw(vertices));
            glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), 1);
            save(review.resolve("aligned-stone-steps.png"), draw(vertices));
            glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), 0);
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
            save(review.resolve("original-steps.png"), draw(vertices));

            for (Fixture target : Fixture.values()) {
                if (target == Fixture.MIXED) continue;
                fixture = target;
                atlas();
                uniforms(program);
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, map);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, false));
                vertices = mesh(program, false, false);
                camera(program, false);
                glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
                float[] authored = draw(vertices);
                glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
                float[] blend = draw(vertices);
                int[] altered = new int[fixture.blocks.length];
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int pixel = y * SIZE + x;
                        assertTrue(palette.contains(rgb(blend, pixel)), fixture + " must retain crisp texture colors");
                        if (rgb(authored, pixel) != rgb(blend, pixel)) altered[material(24 + x / 32, 39 - y / 32) - 1]++;
                    }
                }
                for (int count : altered) assertTrue(count > 0, fixture + " must blend every registered surface material");
                glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), 0.45F);
                float[] combined = draw(vertices);
                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                    assertTrue(palette.contains(rgb(combined, pixel)), fixture + " regional blending must preserve textures and biome tint");
                }
                save(review.resolve(fixture.name().toLowerCase(java.util.Locale.ROOT) + "-combined.png"), combined);
                glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), 0);
                glUniform1f(glGetUniformLocation(program, "SurfaceStrength"), 0.35F);
                float[] shaded = draw(vertices);
                int shadedPixels = 0;
                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                    float base = blend[pixel * 4] + blend[pixel * 4 + 1] + blend[pixel * 4 + 2];
                    float lit = shaded[pixel * 4] + shaded[pixel * 4 + 1] + shaded[pixel * 4 + 2];
                    assertTrue(Float.isFinite(lit) && lit >= 0);
                    // At strength 0.35 the detail model's minimum multiplier is 0.8544.
                    // Tint compensation may exceed 1 before vertex tint is applied; it is not overexposure.
                    assertTrue(lit >= base * 0.85F - 0.0001F,
                            fixture + " detail must not turn tint-compensated sand into a dark overlay");
                    if (Math.abs(base - lit) > 0.001F) shadedPixels++;
                    for (int c = 0; c < 3; c++) {
                        assertTrue(shaded[pixel * 4 + c] <= 1.0F, fixture + " highlights must remain in range");
                        assertEquals(blend[pixel * 4 + c] / Math.max(base, 0.001F),
                                shaded[pixel * 4 + c] / Math.max(lit, 0.001F), 0.0001F, fixture + " shading must preserve material hue");
                    }
                }
                assertTrue(shadedPixels > 1000, fixture + " must receive surface shading as well as blending");
                String name = fixture.name().toLowerCase(java.util.Locale.ROOT);
                save(review.resolve(name + "-original.png"), authored);
                save(review.resolve(name + "-shaded.png"), shaded);
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, map);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(true, false));
                vertices = mesh(program, true, false);
                camera(program, true);
                save(review.resolve(name + "-steps.png"), draw(vertices));
            }
            // IDs above one byte prove that discovery is not limited to eight/255 materials.
            vertices = mesh(program, false, false);
            camera(program, false);
            glUniform1f(glGetUniformLocation(program, "SurfaceStrength"), 0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, map);
            float[] normalIds = surfaceMap(false, false);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, normalIds);
            float[] reference = draw(vertices);
            glActiveTexture(GL_TEXTURE4);
            float[] table = new float[18 * TerrainMaterials.LIMIT * 4];
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, table);
            System.arraycopy(table, 18 * 4, table, 257 * 18 * 4, fixture.blocks.length * 18 * 4);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 18, TerrainMaterials.LIMIT, GL_RGBA, GL_FLOAT, table);
            for (int i = 1; i < normalIds.length; i += 4) normalIds[i] += 1 / 255.0F;
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, map);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, normalIds);
            assertArrayEquals(reference, draw(vertices), 0.00001F, "High material IDs must address the same discovered textures");
            checkCutoutCoverage(program);
            checkDisconnectedSurfaces(program, map, review);
            checkWallDirections(program, map, review);
            checkTextureAlignedBlending(program, map, review);
            checkLayeredWalls(program, map, review);
            checkBlendOrder(program, map, review);
            if ("1".equals(System.getenv("BB_SHADER_BENCHMARK"))) {
                benchmark(program, map, output, depth);
            }
            checkVolume(program, review);
            checkCacheCapacity(review);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
            GL.setCapabilities(null);
        }
    }

    @Test
    void sectionPreparationCullsInteriorsAndPreparesOffscreenDonors() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var data = TerrainSections.bake(halfSpace(), new net.minecraft.core.BlockPos(-16, -16, -16), 6, position -> {
            assertEquals(-1, position.getY(), "Opaque interior blocks must never reach material/tint sampling");
            calls.incrementAndGet();
            return 0xffffffff00000000L | (position.getX() < 0 ? 1 : 2);
        });
        assertEquals(28 * 28, calls.get(), "Only exposed faces are sampled, independently of camera direction");
        assertEquals(28 * 28 * 3, data.voxels().length, "Keep the sparse exposed surface, not the entire volume");
        // Probe hints add only the section's own blocks beside a surface: here the stone just under it.
        assertEquals(16 * 16, data.hints().length, "Probe hints must stay next to surfaces");
        int boundary = ((21 * 28 + 14) * 28 + 21);
        for (int i = 0; i < data.voxels().length; i += 3) if (data.voxels()[i] == boundary)
            assertEquals(12288, data.voxels()[i + 1] & 12288, "The neighboring section's donor must be ready before drawing");
    }

    private static net.minecraft.world.level.BlockGetter halfSpace() {
        return new net.minecraft.world.level.BlockGetter() {
            @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) { return null; }
            @Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) {
                return (pos.getY() < 0 ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState();
            }
            @Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };
    }

    private void checkVolume(int program, Path review) throws Exception {
        fixture = Fixture.MIXED;
        glUseProgram(program);
        uniforms(program);
        atlas();
        glUniform1f(glGetUniformLocation(program, "VolumeMode"), 1);
        glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), 1);
        glUniform3f(glGetUniformLocation(program, "ChunkOffset"), 0, 0, 0);
        glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 0, 0);
        glUniform3f(glGetUniformLocation(program, "VolumeOrigin"), -64, -64, -64);
        glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false,
                new Matrix4f().ortho(-4, 4, -4, 4, 0.1F, 100).get(new float[16]));
        glActiveTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(0);
        try (var volume = new TerrainVolume(128)) {
            volume.move(-64, -64, -64);
            volume.set(-32, -32, -32, 1 | (63 << 16), -1);
            assertEquals(1, volume.get(-32, -32, -32) & 4095);
            volume.addFlags(-32, -32, -32, 4096, true);
            volume.addFlags(-32, -32, -32, 8192, false);
            assertEquals(12288, volume.get(-32, -32, -32) & 12288, "Visible faces combine their boundaries");
            volume.addFlags(-32, -32, -32, 0, true);
            assertEquals(0, volume.get(-32, -32, -32) & 12288, "Refresh removes obsolete boundaries");
            assertEquals(-1, volume.colors.get(0, 0), "White tint is valid data");
            volume.move(-80, -80, -80);
            assertEquals(1, volume.get(-32, -32, -32) & 4095, "Recentring retains absolute block entries");
            volume.move(-64, -64, -64);
            for (var face : net.minecraft.core.Direction.values()) {
                for (int height : new int[]{32, 48}) {
                    // Parallel surfaces in the same volume must not alias each other.
                    for (int u = 25; u < 39; u++) for (int v = 25; v < 39; v++) {
                        int x = face.getAxis() == net.minecraft.core.Direction.Axis.X ? height : u;
                        int y = face.getAxis() == net.minecraft.core.Direction.Axis.Y ? height : v;
                        int z = face.getAxis() == net.minecraft.core.Direction.Axis.Z ? height : face.getAxis() == net.minecraft.core.Direction.Axis.X ? u : v;
                        int id = height == 48 ? 1 : u < 32 ? 1 : 2;
                        volume.set(x - 64, y - 64, z - 64, id | 12288 | (1 << (16 + face.get3DDataValue())), -1);
                    }
                }
                glActiveTexture(GL_TEXTURE0);
                volume.upload();
                glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.blocks));
                glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
                glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.colors));
                glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.index));
                glUniform1i(glGetUniformLocation(program, "VolumeSampler"), 7);
                atlas();
                float[] normal = {face.getStepX(), face.getStepY(), face.getStepZ()};
                for (int height : new int[]{32, 48}) {
                    var values = new ArrayList<Float>();
                    for (int u = 28; u < 36; u++) for (int v = 28; v < 36; v++) {
                        float[][] points = new float[4][3];
                        int i = 0;
                        for (int[] corner : new int[][]{{0,0},{1,0},{1,1},{0,1}}) {
                            float h = height + (face.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE ? 1 : 0);
                            points[i][0] = face.getAxis() == net.minecraft.core.Direction.Axis.X ? h : u + corner[0];
                            points[i][1] = face.getAxis() == net.minecraft.core.Direction.Axis.Y ? h : v + corner[1];
                            points[i][2] = face.getAxis() == net.minecraft.core.Direction.Axis.Z ? h : face.getAxis() == net.minecraft.core.Direction.Axis.X ? u + corner[0] : v + corner[1];
                            i++;
                        }
                        quad(values, height == 48 || u < 32 ? 1 : 2, points, normal, 1);
                    }
                    int vertices = uploadMesh(program, values);
                    float cx = face.getAxis() == net.minecraft.core.Direction.Axis.X ? height + 0.5F : 32;
                    float cy = face.getAxis() == net.minecraft.core.Direction.Axis.Y ? height + 0.5F : 32;
                    float cz = face.getAxis() == net.minecraft.core.Direction.Axis.Z ? height + 0.5F : 32;
                    var view = new Matrix4f().lookAt(cx + normal[0] * 12, cy + normal[1] * 12, cz + normal[2] * 12,
                            cx, cy, cz, 0, face.getAxis() == net.minecraft.core.Direction.Axis.Y ? 0 : 1,
                            face.getAxis() == net.minecraft.core.Direction.Axis.Y ? -1 : 0);
                    glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false, view.get(new float[16]));
                    glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
                    float[] original = draw(vertices);
                    glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
                    float[] blended = draw(vertices);
                    if (height == 48) assertArrayEquals(original, blended, 0.00001F, face + " stacked uniform surface must remain independent");
                    else {
                        int changed = 0;
                        for (int pixel = 0; pixel < SIZE * SIZE; pixel++) if (rgb(original, pixel) != rgb(blended, pixel)) changed++;
                        assertTrue(changed > 50, face + " must blend at cave height across a section boundary: " + changed);
                        save(review.resolve("volume-" + face.getName() + ".png"), blended);
                    }
                    if (face == net.minecraft.core.Direction.UP && height == 32) {
                        checkFirstFrame(program, vertices, review);
                        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.blocks));
                        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.colors));
                        glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.index));
                    }
                }
            }
        }
        glUniform1f(glGetUniformLocation(program, "VolumeMode"), 0);
    }

    private void checkFirstFrame(int program, int vertices, Path review) throws Exception {
        // Same geometry and camera, with data arriving after the first frame.
        glActiveTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(0);
        try (var cold = new TerrainVolume(128)) {
            // Use a section-aligned volume origin and map the fixture's y=33 face to the y=0 world plane.
            cold.move(-32, -48, -32);
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 15, 0);
            glUniform3f(glGetUniformLocation(program, "VolumeOrigin"), -32, -48, -32);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(cold.blocks));
            glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(cold.colors));
            glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(cold.index));
            atlas();
            float[] missing = draw(vertices);
            var a = TerrainSections.bake(halfSpace(), new net.minecraft.core.BlockPos(-16, -16, -16), 6,
                    pos -> 0xffffffff00000000L | (pos.getX() < 0 ? 1 : 2));
            var b = TerrainSections.bake(halfSpace(), new net.minecraft.core.BlockPos(0, -16, -16), 6,
                    pos -> 0xffffffff00000000L | (pos.getX() < 0 ? 1 : 2));
            var c = TerrainSections.bake(halfSpace(), new net.minecraft.core.BlockPos(-16, -16, 0), 6,
                    pos -> 0xffffffff00000000L | (pos.getX() < 0 ? 1 : 2));
            var d = TerrainSections.bake(halfSpace(), new net.minecraft.core.BlockPos(0, -16, 0), 6,
                    pos -> 0xffffffff00000000L | (pos.getX() < 0 ? 1 : 2));
            var ready = java.util.List.of(a,b,c,d);
            glActiveTexture(GL_TEXTURE0);
            cold.beginPublication();
            for (var data : ready) cold.publish(data);
            cold.upload();
            atlas();
            float[] settled = draw(vertices);
            int changed = 0;
            for (int i = 0; i < SIZE * SIZE; i++) if (rgb(missing, i) != rgb(settled, i)) changed++;
            assertTrue(changed > 50, "Reproduce visible repaint when data arrives AFTER the first frame");
            System.out.println("Readiness reproduction: " + changed + " pixels repainted after late publication; prepared frames remain identical.");
            save(review.resolve("readiness-old-first-frame.png"), missing);
            save(review.resolve("readiness-prepared-first-frame.png"), settled);
            // Publishing before each draw is complete and idempotent across frames.
            for (int frame = 0; frame < 5; frame++) {
                cold.beginPublication();
                for (var data : ready) cold.publish(data);
                glActiveTexture(GL_TEXTURE0); cold.upload(); atlas();
                assertArrayEquals(settled, draw(vertices), 0.00001F, "Prepared data must not paint in across subsequent frames");
            }
            cold.move(-48, -48, -48);
            cold.move(-32, -48, -32);
            cold.beginPublication();
            for (var data : ready) cold.publish(data);
            glActiveTexture(GL_TEXTURE0); cold.upload(); atlas();
            assertArrayEquals(settled, draw(vertices), 0.00001F, "Turning back must use the retained compiled snapshot");
            cold.publish(TerrainSections.bake(halfSpace(), new net.minecraft.core.BlockPos(16, -16, 0), 6,
                    pos -> 0xffffffff00000002L));
            assertEquals(2, cold.get(16, -1, 0) & 4095, "Previously visible neighbor owns its donor page");
            var edited = new java.util.ArrayList<TerrainSections.Data>();
            for (var data : ready) edited.add(TerrainSections.bake(halfSpace(),
                    new net.minecraft.core.BlockPos(data.x(), data.y(), data.z()), 6,
                    pos -> pos.getX() < 0 ? 0xffffffff00000001L : 0));
            assertTrue(cold.changed(edited.getFirst()));
            cold.beginPublication();
            cold.invalidateDonors(edited);
            for (var data : edited) cold.publish(data);
            assertEquals(0, cold.get(0, -1, 0), "New snapshots clear removed receivers");
            assertEquals(0, cold.get(16, -1, 0), "Removed donor material must not survive outside the section");
            assertEquals(0, cold.get(-1, -1, 0) & 12288, "Obsolete boundary flags must be cleared atomically");
        }
        glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 0, 0);
        glUniform3f(glGetUniformLocation(program, "VolumeOrigin"), -64, -64, -64);
    }

    private void checkBlendOrder(int shader, int map, Path review) throws Exception {
        fixture = Fixture.OVERWORLD;
        atlas();
        String fragment = resource("core/terrain.fsh");
        int reversed = program(resource("core/terrain.vsh"), fragment
                .replace("for (int z = -1; z <= 1; z++)", "for (int z = 1; z >= -1; z--)")
                .replace("for (int x = -1; x <= 1; x++)", "for (int x = 1; x >= -1; x--)"));
        try {
            for (float regional : new float[]{0, 0.45F}) {
                float[][] renders = new float[2][];
                int index = 0;
                for (int program : new int[]{shader, reversed}) {
                    glUseProgram(program);
                    uniforms(program);
                    glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
                    glUniform2f(glGetUniformLocation(program, "NoiseOffset"), 0, 0);
                    glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
                    glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), regional);
                    glActiveTexture(GL_TEXTURE1);
                    glBindTexture(GL_TEXTURE_2D, map);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, false));
                    int vertices = mesh(program, false, false);
                    camera(program, false);
                    renders[index++] = draw(vertices);
                }
                save(review.resolve("order-independent-" + regional + ".png"), renders[0]);
                int changed = 0;
                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                    if (rgb(renders[0], pixel) != rgb(renders[1], pixel)) changed++;
                }
                // Floating-point summation can move a pixel exactly on a decision boundary.
                assertTrue(changed < SIZE * SIZE / 1000,
                        "Neighbor order must not assign noise contour bands to textures: " + changed + " pixels, regional=" + regional);
            }
        } finally {
            glDeleteProgram(reversed);
            glUseProgram(shader);
        }
    }

    private void checkLayeredWalls(int program, int map, Path review) throws Exception {
        fixture = Fixture.WOODED;
        uniforms(program);
        glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
        glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
        glUniform1f(glGetUniformLocation(program, "AlphaCutoff"), 0.5F);
        glUniform3f(glGetUniformLocation(program, "ChunkOffset"), 0, 0, 0);
        glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 0, 0);
        glUniform2f(glGetUniformLocation(program, "NoiseOffset"), 0, 0);
        float[] atlas = new float[256 * 32 * 4];
        String[] sprites = {"grass_block_side", "grass_block_side_overlay", "stone"};
        for (int sprite = 0; sprite < sprites.length; sprite++) {
            try (var stream = getClass().getResourceAsStream("/assets/minecraft/textures/block/" + sprites[sprite] + ".png")) {
                assertNotNull(stream);
                var image = ImageIO.read(stream);
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    int pixel = (y * 256 + sprite * 16 + x) * 4, color = image.getRGB(x, y);
                    for (int c = 0; c < 3; c++) atlas[pixel + c] = ((color >> (16 - c * 8)) & 255) / 255.0F;
                    atlas[pixel + 3] = ((color >>> 24) & 255) / 255.0F;
                }
            }
        }
        glActiveTexture(GL_TEXTURE0);
        texture(256, 32, atlas);
        float[] table = new float[36 * 3 * 4];
        for (int face = 0; face < 6; face++) for (int layer = 0; layer < 3; layer++) {
            int id = layer == 2 ? 2 : 1, slot = face + (layer == 1 ? 6 : 0);
            int pixel = (id * 36 + slot * 3) * 4;
            table[pixel] = layer * 16 / 255.0F;
            table[pixel + 4] = (layer + 1) * 16 / 255.0F;
            table[pixel + 6] = 16 / 255.0F;
            table[pixel + 8] = layer == 1 ? 1 / 255.0F : 0;
            table[pixel + 10] = layer == 0 ? 1 / 255.0F : 0;
        }
        glActiveTexture(GL_TEXTURE4);
        texture(36, 3, table);
        float[] tints = new float[128 * 128 * 4];
        for (int i = 0; i < tints.length; i += 4) {
            tints[i] = 0.5F; tints[i + 1] = 0.75F; tints[i + 2] = 0.25F; tints[i + 3] = 1;
        }
        glActiveTexture(GL_TEXTURE5);
        texture(128, 128, tints);
        glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false,
                new Matrix4f().ortho(-0.5F, 0.5F, -0.5F, 0.5F, 0.1F, 100).get(new float[16]));
        glDepthFunc(GL_LEQUAL); // Vanilla's coplanar grass overlay pass.
        for (int[] normal : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
            int nx = normal[0], nz = normal[1], tx = nz, tz = -nx;
            float cx = 30.5F + nx * 0.5F, cz = 30.5F + nz * 0.5F;
            float[][] positions = {{cx - tx * 0.5F, 80, cz - tz * 0.5F}, {cx + tx * 0.5F, 80, cz + tz * 0.5F},
                    {cx + tx * 0.5F, 79, cz + tz * 0.5F}, {cx - tx * 0.5F, 79, cz - tz * 0.5F}};
            var grassMesh = new ArrayList<Float>();
            quad(grassMesh, 1, positions, new float[]{nx, 0, nz}, 1);
            quad(grassMesh, 2, positions, new float[]{nx, 0, nz}, 1);
            for (int i = 6 * 12; i < grassMesh.size(); i += 12) {
                grassMesh.set(i + 8, 0.5F); grassMesh.set(i + 9, 0.75F); grassMesh.set(i + 10, 0.25F);
            }
            var stoneMesh = new ArrayList<Float>();
            quad(stoneMesh, 3, positions, new float[]{nx, 0, nz}, 1);
            glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false,
                    new Matrix4f().lookAt(cx + nx * 10, 79.5F, cz + nz * 10, cx, 79.5F, cz, 0, 1, 0).get(new float[16]));
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
            float[] grass = draw(uploadMesh(program, grassMesh));
            float[] stone = draw(uploadMesh(program, stoneMesh));
            for (boolean receiverGrass : new boolean[]{true, false}) {
                float[] data = new float[128 * 128 * 4];
                for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
                    int pixel = (z * 128 + x) * 4;
                    boolean own = (x - 30) * tx + (z - 30) * tz == 0;
                    data[pixel] = (own == receiverGrass ? 1 : 2) / 255.0F;
                    data[pixel + 1] = 48 / 255.0F;
                    data[pixel + 2] = 80 / 255.0F;
                    data[pixel + 3] = 128 / 255.0F;
                }
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, map);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, data);
                int vertices = uploadMesh(program, receiverGrass ? grassMesh : stoneMesh);
                float[] original = receiverGrass ? grass : stone, donor = receiverGrass ? stone : grass;
                glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
                for (float aligned : new float[]{0, 1}) for (float regional : new float[]{0, 0.45F, 1}) {
                    glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), aligned);
                    glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), regional == 1 ? 0 : 1);
                    glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), regional);
                    float[] blended = draw(vertices);
                    if (receiverGrass) assertArrayEquals(blended, draw(6), 0.00001F, "The old overlay must not repaint borrowed stone");
                    int changed = 0;
                    for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                        if (pixel < SIZE * SIZE / 2) assertEquals(rgb(original, pixel), rgb(blended, pixel), "Lower walls retain their base and overlay");
                        if (rgb(original, pixel) != rgb(blended, pixel)) {
                            changed++;
                            assertEquals(rgb(donor, pixel), rgb(blended, pixel), "Borrowed grass must include the correctly tinted overlay");
                        }
                        assertEquals(1, blended[pixel * 4 + 3], 0.00001F, "Layered blending must not create holes");
                    }
                    assertTrue(changed > 100, "Grass sides must both receive and supply blending on every wall orientation");
                    if (nx == 0 && nz == 1 && aligned == 1 && regional == 0.45F)
                        save(review.resolve(receiverGrass ? "grass-side-blended.png" : "grass-side-donor.png"), blended);
                }
                glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
                assertArrayEquals(original, draw(vertices), 0.00001F, "Disabling blending must restore both original layers");
                if (receiverGrass && nx == 0 && nz == 1) {
                    // A custom pack may put an opaque overlay over a hole in its base.
                    glActiveTexture(GL_TEXTURE0);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 8, 12, 1, 1, GL_RGBA, GL_FLOAT, new float[]{0, 0, 0, 0});
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 24, 12, 1, 1, GL_RGBA, GL_FLOAT, new float[]{1, 1, 1, 1});
                    float[] cutout = draw(vertices);
                    glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
                    float[] composite = draw(vertices);
                    assertArrayEquals(java.util.Arrays.copyOf(cutout, SIZE * SIZE * 2),
                            java.util.Arrays.copyOf(composite, SIZE * SIZE * 2), 0.00001F,
                            "Overlay pixels over base cutout holes must remain visible");
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 256, 32, GL_RGBA, GL_FLOAT, atlas);
                }
            }
        }
        glDepthFunc(GL_LESS);
    }

    private void checkTextureAlignedBlending(int program, int map, Path review) throws Exception {
        fixture = Fixture.WOODED;
        for (int resolution : new int[]{8, 16, 32}) {
            uniforms(program);
            glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
            glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
            glUniform3f(glGetUniformLocation(program, "ChunkOffset"), 0, 0, 0);
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 0, 0);
            glUniform2f(glGetUniformLocation(program, "NoiseOffset"), 0, 0);
            float[] grid = new float[resolution * 16 * resolution * 2 * 4];
            for (int y = 0; y < resolution; y++) for (int x = 0; x < resolution * 2; x++) {
                int pixel = (y * resolution * 16 + x) * 4;
                grid[pixel] = (x % resolution) / (float) (resolution - 1);
                grid[pixel + 1] = y / (float) (resolution - 1);
                grid[pixel + 2] = x < resolution ? 0 : 1;
                grid[pixel + 3] = 1;
            }
            glActiveTexture(GL_TEXTURE0);
            texture(resolution * 16, resolution * 2, grid);
            float[] table = new float[18 * 3 * 4];
            for (int id = 1; id <= 2; id++) for (int face = 0; face < 6; face++) {
                int pixel = (id * 18 + face * 3) * 4;
                table[pixel] = (id - 1) * resolution / 255.0F;
                table[pixel + 4] = id * resolution / 255.0F;
                table[pixel + 6] = resolution / 255.0F;
            }
            glActiveTexture(GL_TEXTURE4);
            texture(18, 3, table);
            float[] data = new float[128 * 128 * 4];
            for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
                int pixel = (z * 128 + x) * 4;
                data[pixel] = (x == 30 ? 1 : 2) / 255.0F;
                data[pixel + 1] = 48 / 255.0F;
                data[pixel + 2] = 80 / 255.0F;
                data[pixel + 3] = 128 / 255.0F;
            }
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, map);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, data);
            glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false,
                    new Matrix4f().ortho(-0.5F, 0.5F, -0.5F, 0.5F, 0.1F, 100).get(new float[16]));
            for (boolean wall : new boolean[]{false, true}) for (boolean rotated : new boolean[]{false, true})
                    for (boolean angled : new boolean[]{false, true}) {
                var values = new ArrayList<Float>();
                quad(values, 1, wall
                        ? new float[][]{{30, 80, 31}, {31, 80, 31}, {31, 79, 31}, {30, 79, 31}}
                        : new float[][]{{30, 80, 30}, {31, 80, 30}, {31, 80, 31}, {30, 80, 31}},
                        wall ? new float[]{0, 0, 1} : new float[]{0, 1, 0}, 1);
                if (rotated) for (int i = 0; i < values.size(); i += 12) {
                    float u = values.get(i + 3) * 16;
                    values.set(i + 3, values.get(i + 4) / 8);
                    values.set(i + 4, (1 - u) / 2);
                }
                int vertices = uploadMesh(program, values);
                glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false, (wall
                        ? new Matrix4f().lookAt(30.5F, 79.5F, 41, 30.5F, 79.5F, 31, 0, 1, 0)
                        : new Matrix4f().lookAt(30.5F, 100, 30.5F, 30.5F, 80, 30.5F, 0, 0, -1)).get(new float[16]));
                glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false, (angled
                        ? new Matrix4f().perspective((float) Math.toRadians(60), 1, 0.1F, 100)
                        : new Matrix4f().ortho(-0.5F, 0.5F, -0.5F, 0.5F, 0.1F, 100)).get(new float[16]));
                if (angled) glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false, (wall
                        ? new Matrix4f().lookAt(31.1F, 79.8F, 32.3F, 30.5F, 79.5F, 31, 0, 1, 0)
                        : new Matrix4f().lookAt(31.1F, 81.3F, 31.2F, 30.5F, 80, 30.5F, 0, 0, -1)).get(new float[16]));
                glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
                float[] original = draw(vertices);
                glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
                for (float regional : new float[]{0, 0.45F, 1}) {
                    glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), regional == 1 ? 0 : 1);
                    glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), regional);
                    glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), 0);
                    float[] legacy = draw(vertices);
                    glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), 1);
                    float[] aligned = draw(vertices);
                    float[] choices = new float[resolution * resolution];
                    java.util.Arrays.fill(choices, Float.NaN);
                    String context = "A texture pixel must have one material: resolution=" + resolution + ", wall=" + wall
                            + ", rotated=" + rotated + ", angled=" + angled + ", regional=" + regional;
                    int changed = 0;
                    for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                        if (original[pixel * 4 + 2] > 0.001F) continue; // Outside the face.
                        // At oblique texel edges the sampler's subtexel rounding can differ
                        // from GLSL floor. Compare interiors, as in the wall regression.
                        if (angled && (pixel < SIZE || pixel >= SIZE * (SIZE - 1) || pixel % SIZE == 0
                                || pixel % SIZE == SIZE - 1 || rgb(original, pixel) != rgb(original, pixel - 1)
                                || rgb(original, pixel) != rgb(original, pixel + 1)
                                || rgb(original, pixel) != rgb(original, pixel - SIZE)
                                || rgb(original, pixel) != rgb(original, pixel + SIZE))) continue;
                        int texel = Math.round(original[pixel * 4] * (resolution - 1))
                                + resolution * Math.round(original[pixel * 4 + 1] * (resolution - 1));
                        float donor = aligned[pixel * 4 + 2];
                        if (Float.isNaN(choices[texel])) choices[texel] = donor;
                        if (Math.abs(choices[texel] - donor) > 0.00001F)
                            fail(context + ", pixel=" + pixel % SIZE + "," + pixel / SIZE + ", texel=" + texel
                                    + ", expected=" + choices[texel] + ", actual=" + donor);
                        if (donor > 0.5F) {
                            changed++;
                            assertEquals(rotated ? 1 - original[pixel * 4 + 1] : original[pixel * 4],
                                    aligned[pixel * 4], 0.00001F, "Borrowed texture U must not inherit the receiver's random rotation");
                            assertEquals(rotated ? original[pixel * 4] : original[pixel * 4 + 1],
                                    aligned[pixel * 4 + 1], 0.00001F, "Borrowed texture V must retain its face-plane orientation");
                        }
                    }
                    assertTrue(changed > 0, "Aligned mode must still blend");
                    glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), 0);
                    assertArrayEquals(legacy, draw(vertices), "Disabling alignment must restore the original style");
                    if (!rotated && regional == 0) save(review.resolve("aligned-" + resolution
                            + (wall ? "-wall" : "-top") + (angled ? "-angled" : "") + ".png"), aligned);
                }
            }
        }
    }

    private void checkWallDirections(int program, int map, Path review) throws Exception {
        fixture = Fixture.WOODED;
        atlas();
        // Matching UV ramps expose stretching; blue identifies the selected material.
        float[] grid = new float[256 * 32 * 4];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int pixel = (y * 256 + x) * 4;
            grid[pixel] = (x % 16) / 15.0F;
            grid[pixel + 1] = (y % 16) / 15.0F;
            grid[pixel + 2] = x < 16 ? 0 : 1;
            grid[pixel + 3] = 1;
        }
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 256, 32, GL_RGBA, GL_FLOAT, grid);
        glGenerateMipmap(GL_TEXTURE_2D);
        uniforms(program);
        glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
        glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
        glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false,
                new Matrix4f().ortho(-0.5F, 0.5F, -0.5F, 0.5F, 0.1F, 100).get(new float[16]));
        for (int[] normal : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
            int nx = normal[0], nz = normal[1], tx = -nz, tz = nx;
            float cx = 30.5F + nx * 0.5F, cz = 30.5F + nz * 0.5F;
            var values = new ArrayList<Float>();
            quad(values, 1, new float[][]{
                    {cx - tx * 0.5F, 80, cz - tz * 0.5F}, {cx + tx * 0.5F, 80, cz + tz * 0.5F},
                    {cx + tx * 0.5F, 79, cz + tz * 0.5F}, {cx - tx * 0.5F, 79, cz - tz * 0.5F}},
                    new float[]{nx, 0, nz}, 1);
            int vertices = uploadMesh(program, values);
            glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false,
                    new Matrix4f().lookAt(cx + nx * 10, 79.5F, cz + nz * 10, cx, 79.5F, cz, 0, 1, 0).get(new float[16]));
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
            float[] original = draw(vertices);
            assertTrue(Math.abs(original[(16 * SIZE + 256) * 4 + 1] - original[(496 * SIZE + 256) * 4 + 1]) > 0.9F,
                    "The reference wall must display the full texture height");
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
            for (boolean regional : new boolean[]{false, true}) for (boolean along : new boolean[]{false, true}) {
                glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), regional ? 0 : 1);
                glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), regional ? 1 : 0);
                float[] data = new float[128 * 128 * 4];
                for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
                    int pixel = (z * 128 + x) * 4;
                    int offset = (x - 30) * (along ? tx : nx) + (z - 30) * (along ? tz : nz);
                    data[pixel] = (offset == 0 ? 1 : 2) / 255.0F;
                    data[pixel + 1] = 48 / 255.0F;
                    data[pixel + 2] = 80 / 255.0F;
                    data[pixel + 3] = 128 / 255.0F;
                }
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, map);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, data);
                float[] blended = draw(vertices);
                if (!along) assertArrayEquals(original, blended, 0.00001F, "Walls must not borrow from in front or behind");
                else {
                    assertFalse(java.util.Arrays.equals(original, blended), "Blending must stay active along every wall orientation");
                    for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                        assertEquals(original[pixel * 4], blended[pixel * 4], 0.00001F, "Donor U must retain the original texel scale");
                        assertEquals(original[pixel * 4 + 1], blended[pixel * 4 + 1], 0.00001F, "Donor V must retain the original texel scale");
                    }
                    // Material choices must cover whole texels, not taper into thin triangular slices.
                    for (int y = SIZE / 2; y < SIZE; y += 32) for (int x = 0; x < SIZE; x += 32) {
                        float donor = blended[((y + 16) * SIZE + x + 16) * 4 + 2];
                        for (int dy = 2; dy < 30; dy++) for (int dx = 2; dx < 30; dx++)
                            assertEquals(donor, blended[((y + dy) * SIZE + x + dx) * 4 + 2], 0.00001F,
                                    "Side transitions must not squeeze a texel into a sliver");
                    }
                    for (int pixel = 0; pixel < SIZE * SIZE / 2; pixel++)
                        assertEquals(rgb(original, pixel), rgb(blended, pixel), "Lower walls must retain their own material");
                }
                if (nx == 0 && nz == 1) save(review.resolve("wall-" + (regional ? "regional" : "local") + (along ? "-along" : "-across") + ".png"), blended);
            }
        }
    }

    private void checkDisconnectedSurfaces(int program, int map, Path review) throws Exception {
        fixture = Fixture.WOODED;
        glUseProgram(program);
        atlas();
        uniforms(program);
        glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
        glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
        glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), 0);
        glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), 1);
        glUniform3f(glGetUniformLocation(program, "ChunkOffset"), 0, 0, 0);
        glUniform3f(glGetUniformLocation(program, "BiomeOffset"), 0, 0, 0);
        glUniform2f(glGetUniformLocation(program, "NoiseOffset"), 0, 0);
        var values = new ArrayList<Float>();
        quad(values, 1, new float[][]{{30, 80, 30}, {31, 80, 30}, {31, 80, 31}, {30, 80, 31}},
                new float[]{0, 1, 0}, 1);
        int vertices = uploadMesh(program, values);
        glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false,
                new Matrix4f().lookAt(30.5F, 100, 30.5F, 30.5F, 80, 30.5F, 0, 0, -1).get(new float[16]));
        // Close-up selection and distant filtered blending must both reject disconnected donors.
        for (float extent : new float[]{0.5F, 64}) {
            glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false,
                    new Matrix4f().ortho(-extent, extent, -extent, extent, 0.1F, 100).get(new float[16]));
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
            float[] original = draw(vertices);
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
            for (int gap : new int[]{0, 1, 2, 3}) {
                float[] data = new float[128 * 128 * 4];
                for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
                    int pixel = (z * 128 + x) * 4;
                    int distance = Math.max(Math.abs(x - 30), Math.abs(z - 30));
                    boolean between = distance > 0 && distance < 4;
                    data[pixel] = (between && gap == 1 ? 0 : distance >= 4 ? 2 : 1) / 255.0F;
                    data[pixel + 1] = 8192 / 256 / 255.0F;
                    data[pixel + 2] = (between && gap == 2 ? 60 : between && gap == 3 ? 90 : 80) / 255.0F;
                    data[pixel + 3] = 128 / 255.0F;
                }
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, map);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, data);
                float[] blended = draw(vertices);
                if (extent == 0.5F) save(review.resolve("birch-gap-" + gap + ".png"), blended);
                if (gap == 0) assertFalse(java.util.Arrays.equals(original, blended), "Connected regional transitions must remain active at extent " + extent);
                else assertArrayEquals(original, blended, 0.00001F,
                        "Birch across missing terrain, a ravine or a ridge must not repaint stone (gap " + gap + ", extent " + extent + ")");
            }
        }
    }

    private void checkCutoutCoverage(int shader) throws Exception {
        glActiveTexture(GL_TEXTURE0);
        float[] pixels = new float[256 * 32 * 4];
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, pixels);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 256; x++) {
            pixels[(y * 256 + x) * 4 + 3] = new float[]{0, 0.25F, 0.75F, 1}[x % 16 / 4];
        }
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 256, 32, GL_RGBA, GL_FLOAT, pixels);
        glGenerateMipmap(GL_TEXTURE_2D);
        int vertices = mesh(shader, false, false);
        for (String layer : new String[]{"cutout", "cutout_mipped"}) {
            int vanilla = program(resource("vanilla/core/rendertype_" + layer + ".vsh"), resource("vanilla/core/rendertype_" + layer + ".fsh"));
            for (int program : new int[]{vanilla, shader}) {
                glUseProgram(program);
                uniforms(program);
                camera(program, false);
                glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
                glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
                glUniform1f(glGetUniformLocation(program, "BlendStrength"), 0);
                glUniform1f(glGetUniformLocation(program, "SurfaceStrength"), 0);
                glUniform1f(glGetUniformLocation(program, "AlphaCutoff"), layer.equals("cutout_mipped") ? 0.5F : 0.1F);
            }
            glUseProgram(vanilla);
            float[] reference = draw(vertices);
            float[] expectedDepth = new float[SIZE * SIZE];
            glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, expectedDepth);
            glUseProgram(shader);
            assertArrayEquals(reference, draw(vertices), 0.00001F, layer + " alpha coverage must match the actual vanilla shader");
            float[] actualDepth = new float[SIZE * SIZE];
            glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, actualDepth);
            assertArrayEquals(expectedDepth, actualDepth, 0.00001F, "Discarded cutout texels must not write depth");
        }
        glUniform1f(glGetUniformLocation(shader, "AlphaCutoff"), 0);
    }

    private void profileTerrain(int shader, int output, int depth) throws Exception {
        Path review = Path.of("build/shader-review");
        Files.createDirectories(review);
        int before = program(resource("core/terrain.vsh"), reference());
        int vanilla = program(resource("vanilla/core/rendertype_solid.vsh"), resource("vanilla/core/rendertype_solid.fsh"));
        fixture = Fixture.MIXED;
        glUseProgram(shader); atlas();
        glActiveTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(0);
        try (var volume = new TerrainVolume(128)) {
            var values = new ArrayList<Float>();
            for (int x = 8; x < 120; x++) for (int z = 8; z < 120; z++) {
                int id = material(x, z);
                volume.set(x, 80, z, id | 12288 | (1 << 17), -1);
                quad(values, id, new float[][]{{x,81,z},{x+1,81,z},{x+1,81,z+1},{x,81,z+1}}, new float[]{0,1,0}, 1);
            }
            volume.upload();
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.blocks));
            glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.colors));
            glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.index));
            int vertices = uploadMesh(shader, values);
            for (int program : new int[]{before, shader, vanilla}) {
                glUseProgram(program); uniforms(program); camera(program, false);
                glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
                glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
                glUniform1i(glGetUniformLocation(program, "SurfaceColors"), 5);
                glUniform1i(glGetUniformLocation(program, "VolumeSampler"), 7);
                glUniform1i(glGetUniformLocation(program, "MaterialSampler"), 4);
                glUniform1i(glGetUniformLocation(program, "NoiseSampler"), 6);
                glUniform1f(glGetUniformLocation(program, "VolumeMode"), 1);
                glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
                glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), 0.51F);
                glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), 1);
                glUniform1f(glGetUniformLocation(program, "SurfaceStrength"), 0.35F);
                glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), 1);
            }
            // DynamicTexture creation/upload and uniforms() change texture bindings.
            // Restore the actual atlas and all volume textures after those operations.
            atlas();
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.blocks));
            glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.colors));
            glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.index));
            glUseProgram(before); float[] expected = draw(vertices);
            glUseProgram(shader); float[] actual = draw(vertices);
            var renderedColors = new HashSet<Integer>();
            int litPixels = 0;
            for (int i = 0; i < SIZE * SIZE; i++) {
                renderedColors.add(rgb(actual, i));
                if (actual[i * 4] + actual[i * 4 + 1] + actual[i * 4 + 2] > 0.15F) litPixels++;
            }
            assertTrue(renderedColors.size() > 32 && litPixels > SIZE * SIZE / 2, "Benchmark must render textured terrain, not a black/unbound atlas");
            glUniform1f(glGetUniformLocation(shader, "BlendStrength"), 0);
            float[] unblended = draw(vertices);
            glUniform1f(glGetUniformLocation(shader, "BlendStrength"), 1);
            int blendedPixels = 0;
            for (int i = 0; i < SIZE * SIZE; i++) if (rgb(actual, i) != rgb(unblended, i)) blendedPixels++;
            assertTrue(blendedPixels > 1000, "Benchmark must exercise blending");
            int changed = 0;
            for (int i = 0; i < SIZE * SIZE; i++) if (rgb(expected, i) != rgb(actual, i)) changed++;
            System.out.println("3D shader reference comparison: " + changed + " changed pixels / " + SIZE * SIZE);
            save(review.resolve("performance-before.png"), expected);
            save(review.resolve("performance-after.png"), actual);
            assertTrue(changed < SIZE * SIZE / 1000, "Optimization must preserve the reference appearance");
            glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, output);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1920, 1080, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            glBindRenderbuffer(GL_RENDERBUFFER, depth); glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, 1920, 1080);
            glViewport(0, 0, 1920, 1080);
            ShaderBenchmark.measure("volume-vs-vanilla-1080p", () -> benchmarkDraw(vanilla, vertices), () -> benchmarkDraw(shader, vertices), 40);
            ShaderBenchmark.measure("volume-before-after-1080p", () -> benchmarkDraw(before, vertices), () -> benchmarkDraw(shader, vertices), 40);
            ShaderBenchmark.measure("profile-metadata-vs-detail", () -> profileDraw(shader, vertices, 0, 0, 0), () -> profileDraw(shader, vertices, 0, 0, 0.35F), 24);
            ShaderBenchmark.measure("profile-detail-vs-local", () -> profileDraw(shader, vertices, 0, 0, 0.35F), () -> profileDraw(shader, vertices, 1, 0, 0.35F), 24);
            ShaderBenchmark.measure("profile-local-vs-regional", () -> profileDraw(shader, vertices, 1, 0, 0.35F), () -> profileDraw(shader, vertices, 1, 0.51F, 0.35F), 24);
            profileDraw(shader, vertices, 1, 0.51F, 0.35F);
            for (int program : new int[]{before, shader}) {
                glUseProgram(program);
                glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false,
                        new Matrix4f().ortho(-32, 32, -32, 32, 0.1F, 100).get(new float[16]));
            }
            ShaderBenchmark.measure("volume-distant-before-after-1080p", () -> benchmarkDraw(before, vertices), () -> benchmarkDraw(shader, vertices), 40);
        }
        glDeleteProgram(before); glDeleteProgram(vanilla);
        checkCacheCapacity(review);
    }

    private void checkCacheCapacity(Path review) throws Exception {
        glActiveTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(GL_TEXTURE0);
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(0);
        int[] entries = new int[28 * 28 * 3];
        for (int z = 0, i = 0; z < 28; z++) for (int x = 0; x < 28; x++) {
            entries[i++] = (14 * 28 + z) * 28 + x; entries[i++] = 1 | (1 << 17); entries[i++] = -1;
        }
        var ready = new ArrayList<TerrainSections.Data>();
        for (int y = -4; y <= 4; y++) for (int z = -5; z <= 5; z++) for (int x = -5; x <= 5; x++)
            ready.add(new TerrainSections.Data(x * 16, y * 16, z * 16, 6, entries, new int[0]));
        try (var volume = new TerrainVolume(256)) {
            volume.move(-128, -128, -128);
            for (int frame = 0; frame < 4; frame++) {
                long start = System.nanoTime();
                volume.prepare(ready);
                double millis = (System.nanoTime() - start) / 1_000_000.0;
                int uploads = volume.pendingUploads();
                String row = "cache-publication," + frame + "," + millis + "," + uploads + "\n";
                System.out.print(row);
                Files.writeString(review.resolve("cache-profile.csv"), row, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                if (frame >= 2) assertEquals(0, uploads, "A stationary saturated cache must stop republishing pages");
                volume.upload();
            }
            var changed = ready.getFirst();
            ready.set(0, new TerrainSections.Data(changed.x(), changed.y(), changed.z(), changed.radius(), changed.voxels(), changed.hints()));
            volume.prepare(ready);
            assertTrue(volume.pendingUploads() <= 27, "One section rebuild must not dirty unrelated donor pages across the entire cache");
            volume.upload();
        }
        long[] bakeTimes = new long[20];
        for (int i = 0; i < bakeTimes.length; i++) {
            long start = System.nanoTime();
            TerrainSections.bake(halfSpace(), new net.minecraft.core.BlockPos(-16,-16,-16), 6,
                    pos -> 0xffffffff00000000L | (pos.getX() < 0 ? 1 : 2));
            bakeTimes[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(bakeTimes);
        System.out.println("Exposed-section bake median ms: " + bakeTimes[bakeTimes.length / 2] / 1_000_000.0);
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(0);
        try (var volume = new TerrainVolume(64)) {
            int[] voxel = {0, 1 | (1 << 17), -1};
            volume.prepare(new ArrayList<>(java.util.List.of(new TerrainSections.Data(0, 0, 0, 0, voxel, new int[0]))));
            volume.move(64, 0, 0);
            var outsideEdit = new TerrainSections.Data(0, 0, 0, 0, voxel, new int[0]);
            var inside = new TerrainSections.Data(64, 0, 0, 0, voxel, new int[0]);
            for (int frame = 0; frame < 3; frame++) {
                volume.prepare(new ArrayList<>(java.util.List.of(outsideEdit, inside)));
                if (frame > 0) assertEquals(0, volume.pendingUploads(), "An edited section outside cache coverage must not invalidate visible data every frame");
                volume.upload();
            }
        }
    }

    private void benchmark(int shader, int map, int output, int depth) throws Exception {
        fixture = Fixture.OVERWORLD;
        atlas();
        int vanilla = program(resource("vanilla/core/rendertype_solid.vsh"), resource("vanilla/core/rendertype_solid.fsh"));
        for (int program : new int[]{vanilla, shader}) {
            glUseProgram(program);
            uniforms(program);
            camera(program, false);
            glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
            glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
            glUniform1f(glGetUniformLocation(program, "SurfaceStrength"), 0.35F);
            glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
        }
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, map);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, false));
        for (int[] resolution : new int[][]{{1920, 1080}, {2560, 1440}}) {
            int vertices = mesh(shader, false, false);
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, output);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, resolution[0], resolution[1], 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, resolution[0], resolution[1]);
            glViewport(0, 0, resolution[0], resolution[1]);
            ShaderBenchmark.measure("terrain-boundaries-" + resolution[0] + "x" + resolution[1],
                    () -> benchmarkDraw(vanilla, vertices), () -> benchmarkDraw(shader, vertices));
            ShaderBenchmark.measure("regional-vs-local-boundaries-" + resolution[0] + "x" + resolution[1],
                    () -> benchmarkRegional(shader, vertices, 0), () -> benchmarkRegional(shader, vertices, 0.45F));
            glUniform1f(glGetUniformLocation(shader, "BiomeBlendStrength"), 0);
            // A uniform surface measures the fast path without any texture boundary.
            float[] uniform = surfaceMap(false, false);
            for (int i = 0; i < uniform.length; i += 4) { uniform[i] = 1 / 255.0F; uniform[i + 1] = 0; }
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, map);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, uniform);
            uniformScene = true;
            int uniformVertices = mesh(shader, false, false);
            uniformScene = false;
            ShaderBenchmark.measure("terrain-uniform-" + resolution[0] + "x" + resolution[1],
                    () -> benchmarkDraw(vanilla, uniformVertices), () -> benchmarkDraw(shader, uniformVertices));
            ShaderBenchmark.measure("regional-vs-local-uniform-" + resolution[0] + "x" + resolution[1],
                    () -> benchmarkRegional(shader, uniformVertices, 0), () -> benchmarkRegional(shader, uniformVertices, 0.45F));
            glUniform1f(glGetUniformLocation(shader, "BiomeBlendStrength"), 0);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL_RGBA, GL_FLOAT, surfaceMap(false, false));
        }
    }

    private static void benchmarkDraw(int program, int vertices) {
        glUseProgram(program);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, vertices);
    }

    private static void profileDraw(int program, int vertices, float local, float regional, float detail) {
        glUseProgram(program);
        glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), local);
        glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), regional);
        glUniform1f(glGetUniformLocation(program, "SurfaceStrength"), detail);
        benchmarkDraw(program, vertices);
    }

    private static void benchmarkRegional(int program, int vertices, float strength) {
        glUseProgram(program);
        glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), strength);
        benchmarkDraw(program, vertices);
    }

    private int material(int x, int z) {
        if (uniformScene) return 1;
        if (fixture != Fixture.MIXED) {
            return Math.floorMod(Math.floorDiv(x - 24, 4) + 4 * Math.floorDiv(z - 24, 8), fixture.blocks.length) + 1;
        }
        // Jagged biome seam plus a narrow authored rock inclusion.
        if ((x - 36) * (x - 36) + (z - 26) * (z - 26) <= 6) return 6;
        if ((x - 36) * (x - 36) + (z - 36) * (z - 36) <= 6) return 7;
        if (x < 27 && z >= 35) return 5;
        if (x < 27 && z < 27) return 4;
        if (x >= 35 && x <= 36 && z >= 29 && z <= 33) return 2;
        return x < 32 + (Math.floorDiv(z, 3) % 3 - 1) ? 3 : 1;
    }

    private static int height(int x, int z, boolean steps) {
        return steps ? 80 + Math.floorDiv(z - 24, 4) + (x == 30 && z == 30 ? 5 : 0) : 80;
    }

    /* Local edges and a world-aligned four-block sampling grid for wider transitions. */
    static int boundaryFlags(com.mojang.blaze3d.platform.NativeImage map, int x, int z, int originX, int originZ) {
        int own = map.getPixelRGBA(x, z) & 4095;
        if (own == 0) return 0;
        int flags = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (differentMaterial(map, x + dx, z + dz, own)) flags |= 4096;
        }
        int baseX = Math.floorDiv(originX + x, 4) * 4 + 2 - originX;
        int baseZ = Math.floorDiv(originZ + z, 4) * 4 + 2 - originZ;
        // The shader grid changes halfway through a boundary block; cover both cells.
        for (int dz = -2; dz <= 1; dz++) for (int dx = -2; dx <= 1; dx++) {
            if (differentMaterial(map, baseX + dx * 4, baseZ + dz * 4, own)) return flags | 8192;
        }
        return flags;
    }

    private static boolean differentMaterial(com.mojang.blaze3d.platform.NativeImage map, int x, int z, int own) {
        if (x < 0 || z < 0 || x >= map.getWidth() || z >= map.getHeight()) return false;
        int other = map.getPixelRGBA(x, z) & 4095;
        return other != 0 && other != own;
    }

    private float[] surfaceMap(boolean steps, boolean shifted) {
        float[] data = new float[128 * 128 * 4];
        try (var map = new com.mojang.blaze3d.platform.NativeImage(128, 128, false)) {
            for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
                map.setPixelRGBA(x, z, material(shifted ? x - 16 : x, z));
            }
            for (int z = 0; z < 128; z++) {
                for (int x = 0; x < 128; x++) {
                    int pixel = (z * 128 + x) * 4;
                    int worldX = shifted ? x - 16 : x;
                    data[pixel] = material(worldX, z) / 255.0F;
                    data[pixel + 1] = (boundaryFlags(map, x, z, shifted ? -16 : 0, 0) >> 8) / 255.0F;
                    data[pixel + 2] = height(worldX, z, steps) / 255.0F;
                    data[pixel + 3] = 128 / 255.0F;
                }
            }
        }
        return data;
    }

    void atlas() throws Exception {
        palette.clear();
        float[] data = new float[256 * 32 * 4];
        for (int sprite = 0; sprite < fixture.textures.length; sprite++) {
            if (fixture.textures[sprite] == null) continue;
            String[] location = fixture.textures[sprite].split(":");
            try (var stream = getClass().getResourceAsStream("/assets/" + location[0] + "/textures/" + location[1] + ".png")) {
                assertNotNull(stream);
                BufferedImage image = ImageIO.read(stream);
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 16; x++) {
                        int color = image.getRGB(x, y % 16);
                        if (fixture == Fixture.OVERWORLD && sprite == 0) {
                            int tinted = Math.round(((color >> 16) & 255) * 0.5F) << 16
                                    | Math.round(((color >> 8) & 255) * 0.75F) << 8 | Math.round((color & 255) * 0.25F);
                            // Float tint multiplication can round half-byte values either way.
                            for (int r = -1; r <= 1; r++) for (int g = -1; g <= 1; g++) for (int b = -1; b <= 1; b++) {
                                palette.add((Math.clamp((tinted >> 16) + r, 0, 255) << 16)
                                        | (Math.clamp(((tinted >> 8) & 255) + g, 0, 255) << 8)
                                        | Math.clamp((tinted & 255) + b, 0, 255));
                            }
                        } else palette.add(color & 0xFFFFFF);
                        int pixel = (y * 256 + sprite * 16 + x) * 4;
                        for (int c = 0; c < 3; c++) data[pixel + c] = ((color >> (16 - c * 8)) & 255) / 255.0F;
                        data[pixel + 3] = 1;
                    }
                }
            }
        }
        glActiveTexture(GL_TEXTURE0);
        texture(256, 32, data);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 4);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
    }

    private int mesh(int program, boolean steps, boolean underside) {
        var values = new ArrayList<Float>();
        for (int z = 24; z < 40; z++) {
            for (int x = 24; x < 40; x++) {
                int h = height(x, z, steps);
                int material = material(x, z);
                quad(values, material, new float[][]{{x, h, z}, {x + 1, h, z}, {x + 1, h, z + 1}, {x, h, z + 1}},
                        new float[]{0, underside ? -1 : 1, 0}, 1);
                if (steps) {
                    int side = material == fixture.sideMaterial ? 9 : material == fixture.secondSideMaterial ? 10 : material;
                    for (int y = 78; y < h; y++) {
                        if (z == 39 || y >= height(x, z + 1, true)) {
                            quad(values, side, new float[][]{{x, y + 1, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}, {x, y, z + 1}},
                                    new float[]{0, 0, 1}, 0.7F);
                        }
                        if (x == 39 || y >= height(x + 1, z, true)) {
                            quad(values, side, new float[][]{{x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}, {x + 1, y, z}},
                                    new float[]{1, 0, 0}, 0.8F);
                        }
                    }
                }
            }
        }
        return uploadMesh(program, values);
    }

    private int wall(int program) {
        var values = new ArrayList<Float>();
        for (int x = 29; x < 35; x++) {
            int material = material(x, 30);
            quad(values, material == fixture.sideMaterial ? 9 : material,
                    new float[][]{{x, 80, 31}, {x + 1, 80, 31}, {x + 1, 79, 31}, {x, 79, 31}},
                    new float[]{0, 0, 1}, 1);
        }
        return uploadMesh(program, values);
    }

    static int uploadMesh(int program, ArrayList<Float> values) {
        float[] vertices = new float[values.size()];
        for (int i = 0; i < vertices.length; i++) vertices[i] = values.get(i);
        glBindVertexArray(glGenVertexArrays());
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers());
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        String[] names = {"Position", "UV0", "Normal", "Color"};
        int[] sizes = {3, 2, 3, 4};
        int offset = 0;
        for (int i = 0; i < names.length; i++) {
            int location = glGetAttribLocation(program, names[i]);
            if (location >= 0) {
                glEnableVertexAttribArray(location);
                glVertexAttribPointer(location, sizes[i], GL_FLOAT, false, 12 * 4, offset * 4L);
            }
            offset += sizes[i];
        }
        int light = glGetAttribLocation(program, "UV2");
        if (light >= 0) glVertexAttribI2i(light, 240, 240);
        return vertices.length / 12;
    }

    void quad(ArrayList<Float> values, int sprite, float[][] positions, float[] normal, float shade) {
        for (int index : new int[]{0, 1, 2, 0, 2, 3}) {
            for (float coordinate : positions[index]) values.add(coordinate);
            float u = index == 1 || index == 2 ? 0.999F : 0.001F;
            float v = index >= 2 ? 0.999F : 0.001F;
            values.add((sprite - 1 + u) / 16.0F);
            values.add(v / 2);
            for (float coordinate : normal) values.add(coordinate);
            boolean tinted = fixture == Fixture.OVERWORLD && sprite == 1;
            values.add(shade * (tinted ? 0.5F : 1));
            values.add(shade * (tinted ? 0.75F : 1));
            values.add(shade * (tinted ? 0.25F : 1));
            values.add(1.0F);
        }
    }

    void uniforms(int program) {
        glUniform1f(glGetUniformLocation(program, "TextureAlignedBlending"), 0);
        glUniform1f(glGetUniformLocation(program, "LocalBlendStrength"), 1);
        float[] table = new float[18 * TerrainMaterials.LIMIT * 4];
        for (int id = 1; id <= fixture.blocks.length; id++) {
            for (int face = 0; face < 6; face++) {
                int sprite = face < 2 ? id : id == fixture.sideMaterial ? 9 : id == fixture.secondSideMaterial ? 10 : id;
                int pixel = (id * 18 + face * 3) * 4;
                table[pixel] = (sprite - 1) * 16 / 255.0F;
                table[pixel + 4] = sprite * 16 % 256 / 255.0F;
                table[pixel + 5] = sprite * 16 / 256 / 255.0F;
                table[pixel + 6] = 16 / 255.0F;
                table[pixel + 8] = fixture == Fixture.OVERWORLD && id == 1 && face < 2 ? 1 / 255.0F : 0;
                table[pixel + 9] = (fixture == Fixture.NETHER) && id == 7 ? 1 / 255.0F : 0;
            }
        }
        glActiveTexture(GL_TEXTURE4);
        texture(18, TerrainMaterials.LIMIT, table);
        glUniform1i(glGetUniformLocation(program, "MaterialSampler"), 4);
        float[] colors = new float[128 * 128 * 4];
        java.util.Arrays.fill(colors, 1);
        if (fixture == Fixture.OVERWORLD) {
            for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
                if (material(x, z) != 1) continue;
                int pixel = (z * 128 + x) * 4;
                colors[pixel] = 0.5F;
                colors[pixel + 1] = 0.75F;
                colors[pixel + 2] = 0.25F;
            }
        }
        glActiveTexture(GL_TEXTURE5);
        texture(128, 128, colors);
        glUniform1i(glGetUniformLocation(program, "SurfaceColors"), 5);
        float[] noise = new float[128 * 128 * 4];
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            int pixel = (y * 128 + x) * 4;
            noise[pixel] = TerrainMaterials.noiseByte(x, y) / 255.0F;
            noise[pixel + 1] = TerrainMaterials.noiseByte(x + 37, y + 17) / 255.0F;
        }
        glActiveTexture(GL_TEXTURE6);
        texture(128, 128, noise);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glUniform1i(glGetUniformLocation(program, "NoiseSampler"), 6);
        glUniform1f(glGetUniformLocation(program, "AlphaCutoff"), fixture == Fixture.OVERWORLD ? 0.5F : 0);
        glUniform1f(glGetUniformLocation(program, "SurfaceStrength"), 0);
        glUniform3f(glGetUniformLocation(program, "SunDirection"), 0, 0.8F, 0.6F);
        glUniform4f(glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
        glUniform1f(glGetUniformLocation(program, "FogStart"), 1000);
        glUniform1f(glGetUniformLocation(program, "FogEnd"), 2000);
        glUniform1f(glGetUniformLocation(program, "BlendStrength"), 1);
        glUniform1f(glGetUniformLocation(program, "BiomeBlendStrength"), 0);
        glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
    }

    private void camera(int program, boolean angled) {
        Matrix4f view = angled ? new Matrix4f().lookAt(44, 98, 50, 32, 81, 32, 0, 1, 0)
                : new Matrix4f().lookAt(32, 120, 32, 32, 80, 32, 0, 0, -1);
        if (fixture != Fixture.MIXED) {
            glUniform3f(glGetUniformLocation(program, "ChunkOffset"), angled ? -44 : -32, angled ? -98 : -120, angled ? -50 : -32);
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), angled ? 44 : 32, angled ? 98 : 120, angled ? 50 : 32);
            view.setTranslation(0, 0, 0);
        }
        Matrix4f projection = new Matrix4f().ortho(-8, 8, -8, 8, 0.1F, 100);
        glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false, view.get(new float[16]));
        glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false, projection.get(new float[16]));
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
        return shader;
    }

    static int program(String vertex, String fragment) {
        int program = glCreateProgram();
        glAttachShader(program, compile(GL_VERTEX_SHADER, vertex));
        glAttachShader(program, compile(GL_FRAGMENT_SHADER, fragment));
        String[] attributes = {"Position", "UV0", "Normal", "Color", "UV2"};
        for (int i = 0; i < attributes.length; i++) glBindAttribLocation(program, i, attributes[i]);
        glBindFragDataLocation(program, 0, "fragColor");
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        return program;
    }

    static int texture(int width, int height, float[] data) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        // Precision fixtures use floats; profiling must match Minecraft's byte textures.
        int format = byteTextures ? GL_RGBA8 : GL_RGBA32F;
        glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, GL_RGBA, GL_FLOAT, data);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    private static float[] draw(int vertices) {
        glClearColor(0.04F, 0.04F, 0.04F, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, vertices);
        float[] pixels = new float[SIZE * SIZE * 4];
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    static int rgb(float[] pixels, int pixel) {
        int color = 0;
        for (int c = 0; c < 3; c++) color = (color << 8) | Math.clamp(Math.round(pixels[pixel * 4 + c] * 255), 0, 255);
        return color;
    }

    private static void save(Path path, float[] pixels) throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) image.setRGB(x, SIZE - y - 1, rgb(pixels, y * SIZE + x));
        }
        ImageIO.write(image, "png", path.toFile());
    }

    String resource(String path) throws Exception {
        String prefix = path.startsWith("include/") || path.startsWith("vanilla/") ? "/assets/minecraft/shaders/" : "/assets/better_blending/shaders/";
        path = path.replace("vanilla/", "");
        try (var stream = getClass().getResourceAsStream(prefix + path)) {
            assertNotNull(stream, path);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (path.startsWith("include/")) source = source.replaceFirst("#version[^\\r\\n]*", "");
            return imports(source);
        }
    }

    /* The shader as it looked before performance work. Replace it only when the look changes on purpose. */
    String reference() throws Exception {
        try (var stream = getClass().getResourceAsStream("/reference/terrain-reference.fsh")) {
            assertNotNull(stream, "reference/terrain-reference.fsh");
            return imports(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    String imports(String source) throws Exception {
        for (String include : new String[]{"light.glsl", "fog.glsl"}) {
            String directive = "#moj_import <" + include + ">";
            if (source.contains(directive)) source = source.replace(directive, resource("include/" + include));
        }
        return source;
    }

    private enum Fixture {
        MIXED(0, 0, "stone", "dirt", "gravel", "andesite", "moss_block", "packed_ice", "blue_ice"),
        OVERWORLD(1, 0, "grass_block", "dirt", "sand", "gravel", "clay", "packed_ice"),
        END(0, 0, "end_stone", "obsidian", "purpur_block", "end_stone_bricks"),
        WOODED(0, 0, "stone", "birch_log"),
        NETHER(6, 5, "netherrack", "soul_sand", "soul_soil", "gravel", "blackstone", "basalt", "magma_block");

        final String[] blocks;
        final String[] textures = new String[10];
        final int sideMaterial, secondSideMaterial;

        Fixture(int sideMaterial, int secondSideMaterial, String... blocks) {
            this.blocks = blocks;
            this.sideMaterial = sideMaterial;
            this.secondSideMaterial = secondSideMaterial;
            for (int i = 0; i < blocks.length; i++) {
                textures[i] = "minecraft:block/" + switch (blocks[i]) {
                    case "grass_block" -> "grass_block_top";
                    case "basalt" -> "basalt_top";
                    case "blackstone" -> "blackstone_top";
                    case "magma_block" -> "magma";
                    default -> blocks[i];
                };
            }
            textures[8] = sideMaterial == 1 ? "minecraft:block/grass_block_side" : sideMaterial == 6 ? "minecraft:block/basalt_side" : null;
            textures[9] = secondSideMaterial == 5 ? "minecraft:block/blackstone" : null;
        }
    }
}
