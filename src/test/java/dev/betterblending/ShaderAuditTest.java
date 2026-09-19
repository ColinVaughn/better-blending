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

import com.mojang.blaze3d.systems.RenderSystem;
import dev.betterblending.backend.VanillaTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL32C.*;

/*
 Opt-in cost audit of the terrain fragment shader (BB_SHADER_AUDIT=1).

 Worlds go through the real section bake and volume cache, so boundary flags match the
 game. For each scene it records GPU time against vanilla and the reference shader at
 1080p and 4K, what each stage costs (the time saved by removing it), and texture fetches
 per pixel, with heatmaps. Every scene must still match the reference shader.
 Results land in build/shader-review/audit.
 */
class ShaderAuditTest {
    private static final Path REVIEW = Path.of("build/shader-review/audit");
    private static final int WIDTH = 1920, HEIGHT = 1080;
    private static final int[][] RESOLUTIONS = {{1920, 1080}, {3840, 2160}};
    // In-game defaults: surface strength 1, regional 0.45, local 1, detail 0.35, texture aligned.
    private static final String[] SETTINGS = {"BlendStrength", "BiomeBlendStrength", "LocalBlendStrength",
            "SurfaceStrength", "TextureAlignedBlending"};
    private static final float[] DEFAULTS = {1, 0.45F, 1, 0.35F, 1};
    private static final Map<String, String> SETTING_STAGES = Map.of(
            "regional scan", "BiomeBlendStrength", "local scan", "LocalBlendStrength",
            "texture alignment", "TextureAlignedBlending", "detail shading", "SurfaceStrength");
    // No visible change: up to 1% of covered pixels may differ from the reference by rounding,
    // but only 0.1% by more than VISIBLE_DIFFERENCE levels (of 255) in any channel. Trying a
    // change that trades quality for speed? Raise BB_AUDIT_MAX_CHANGED to see what it costs.
    private static final double MAX_CHANGED = Double.parseDouble(System.getenv().getOrDefault("BB_AUDIT_MAX_CHANGED", "0.01"));
    private static final double MAX_VISIBLE = 0.001;
    private static final int VISIBLE_DIFFERENCE = 8;
    // Per-scene ceilings on mean fetches per covered pixel, about 10% over the current shader.
    // The shader before this audit averaged 86, 89 and 10.
    private static final Map<String, Double> FETCH_BUDGETS = Map.of(
            "boundaries-closeup", 41.0, "boundaries-horizon", 48.0, "uniform-horizon", 11.0);

    private final TerrainShaderTest fixtures = new TerrainShaderTest();
    private final StringBuilder timings = new StringBuilder("scene,resolution,vanilla_ms,reference_ms,current_ms,current_vs_reference\n");
    private final StringBuilder stages = new StringBuilder("scene,stage,full_ms,without_stage_ms,stage_ms,share_of_blending\n");
    private final StringBuilder fetches = new StringBuilder("shader,scene,covered_pixels,mean,p95,max,volume_index,blocks,colors,materials,noise,atlas,size_queries\n");
    private final StringBuilder quality = new StringBuilder("scene,changed_pixels,visible_pixels,covered_pixels,"
            + "changed_fraction,visible_fraction,mean_difference,max_difference\n");
    private final StringBuilder work = new StringBuilder("scene,scanning_share,lookups,hinted_lookups,hit_same_height,hit_below,hit_above,"
            + "missed,regional_scans,local_scans,differing_neighbors,donor_colors,walk_steps\n");
    private final StringBuilder candidateTimings = new StringBuilder("scene,candidate,resolution,current_ms,candidate_ms,candidate_vs_current\n");
    private final Map<String, Integer> candidates = new LinkedHashMap<>();
    private final Map<String, Integer> candidateCounters = new LinkedHashMap<>();
    private final List<Integer> programs = new ArrayList<>();
    private final Map<String, Integer> ablations = new LinkedHashMap<>();
    private int vanilla, current, reference, currentCounter, referenceCounter, workCounter;
    private int byteTarget, byteColor, byteDepth, floatTarget;

    private record World(String name, IntBinaryOperator height, IntBinaryOperator material) {}
    private record Scene(String name, Vector3f eye, Vector3f target) {}

    @Test
    @EnabledIfEnvironmentVariable(named = "BB_SHADER_AUDIT", matches = "1")
    void auditTerrainShaderCost() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(64, 64, "Better Blending shader audit", 0, 0);
        assertNotEquals(0, window);
        boolean byteTextures = TerrainShaderTest.byteTextures;
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            // Once per JVM; the shader tests share it.
            if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            // Minecraft's own textures are bytes; float fixtures would hide filtering and bandwidth costs.
            TerrainShaderTest.byteTextures = true;
            Files.createDirectories(REVIEW);
            audit();
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            TerrainShaderTest.byteTextures = byteTextures;
            glfwDestroyWindow(window);
            glfwTerminate();
            GL.setCapabilities(null);
        }
    }

    private void audit() throws Exception {
        compilePrograms();
        glActiveTexture(GL_TEXTURE2);
        float[] light = new float[16 * 16 * 4];
        Arrays.fill(light, 1);
        TerrainShaderTest.texture(16, 16, light);
        byteTarget = glGenFramebuffers();
        byteColor = glGenTextures();
        byteDepth = glGenRenderbuffers();
        floatTarget = framebuffer(GL_RGBA32F, WIDTH, HEIGHT);
        glEnable(GL_DEPTH_TEST);

        var patchy = new World("patchy", ShaderAuditTest::rolling, ShaderAuditTest::patches);
        var uniform = new World("uniform", ShaderAuditTest::rolling, (x, z) -> 1);
        auditWorld(patchy, List.of(
                new Scene("boundaries-closeup", new Vector3f(46, rolling(46, 40) + 12, 40), new Vector3f(56, rolling(56, 54), 54)),
                new Scene("boundaries-horizon", eyeLevel(20, 20), new Vector3f(100, 56, 100))));
        auditWorld(uniform, List.of(new Scene("uniform-horizon", eyeLevel(20, 20), new Vector3f(100, 56, 100))));

        String header = "# GPU: " + glGetString(GL_RENDERER) + "; OpenGL: " + glGetString(GL_VERSION) + "\n";
        Files.writeString(REVIEW.resolve("timings.csv"), header + timings);
        Files.writeString(REVIEW.resolve("stage-costs.csv"), header + stages);
        Files.writeString(REVIEW.resolve("fetch-counts.csv"), header + fetches);
        Files.writeString(REVIEW.resolve("quality.csv"), header + quality);
        Files.writeString(REVIEW.resolve("candidates.csv"), header + candidateTimings);
        Files.writeString(REVIEW.resolve("work-counts.csv"), header + "# Means per pixel that runs a scan.\n" + work);
        System.out.print("\n" + header + timings + "\n" + stages + "\n" + fetches + "\n" + quality);
        for (int program : programs) glDeleteProgram(program);
    }

    private void compilePrograms() throws Exception {
        String vertex = fixtures.resource("core/terrain.vsh");
        // Substitution targets span lines; a Windows checkout must not change what they match.
        String source = fixtures.resource("core/terrain.fsh").replace("\r\n", "\n");
        String before = fixtures.reference();
        vanilla = link(fixtures.resource("vanilla/core/rendertype_solid.vsh"), fixtures.resource("vanilla/core/rendertype_solid.fsh"));
        current = link(vertex, source);
        reference = link(vertex, before);
        currentCounter = link(vertex, countFetches(source));
        referenceCounter = link(vertex, countFetches(before));
        // Candidates: variants under evaluation, checked and timed against the current shader.
        // Register one with candidates.put(name, link(vertex, variant)), and its fetch counter
        // with candidateCounters.put(name, link(vertex, countFetches(variant))).
        workCounter = link(vertex, countWork(source));
        // Each variant removes one stage; the time it saves is that stage's cost. The
        // substitutions fail loudly once the shader no longer contains their target.
        ablations.put("donor connectivity walk", link(vertex,
                substitute(source, "connectedSurface(cell, neighbor, height, data.y)", "true")));
        ablations.put("extra height probes", link(vertex, substitute(substitute(substitute(substitute(source,
                "if (hint == 1) return volumeSurface(planeBlock(cell, height - 1), float(height));", ""),
                "return hint == 2 ? volumeSurface(planeBlock(cell, height + 1), float(height + 2)) : vec3(0.0);", "return vec3(0.0);"),
                "if (data.x == 0.0) data = volumeSurface(planeBlock(cell, height - 1), float(height));", ""),
                "if (data.x == 0.0) data = volumeSurface(planeBlock(cell, height + 1), float(height + 2));", "")));
        // Probing every height instead of reading the hints; a negative cost is what hints save.
        ablations.put("probe hints", link(vertex, substitute(source, "if (pixel.x >= 0 && cachedHinted) {", "if (false) {")));
        ablations.put("choice noise", link(vertex,
                stub(source, "float choiceNoise(vec3 world, vec3 key, int scale)", "return 0.5;")));
        ablations.put("donor texture sampling", link(vertex,
                stub(source, "vec4 materialColor(int material, int face, vec2 uv, vec2 gradX, vec2 gradY, vec3 tint)",
                        "return vec4(0.5, 0.5, 0.5, 1.0);")));
        ablations.put("tint lookups", link(vertex, stub(source, "vec4 surfaceColor(ivec2 cell, float height)", "return vec4(1.0);")));
        // Where no fragment runs the scans (the uniform scene), what this saves is only the
        // scan code's presence: registers it reserves limit how many fragments run at once.
        ablations.put("scan code", link(vertex, stub(source, """
                vec4 blendTerrain(vec4 source, int ownMaterial, ivec2 cell, float height, int face,
                                  vec2 uv, vec2 gradX, vec2 gradY, vec3 world, vec3 blendPosition,
                                  float footprint, int scale, float amount)""".stripIndent().strip(), "return source;")));
        // The same question for each part of the scan code: which one costs registers.
        ablations.put("regional scan code", link(vertex, substitute(source, "if (blendRegional)", "if (false)")));
        ablations.put("local scan code", link(vertex, substitute(source, "if (blendLocal) albedo", "if (false) albedo")));
        ablations.put("distance average code", link(vertex, substitute(source,
                "vec4 color = donorColor(source, ownMaterial, ownTint, cell, neighbor, data, height, face, uv, gradX, gradY, scale);",
                "vec4 color = source;")));
        for (int program : programs) {
            glUseProgram(program);
            fixtures.uniforms(program);
            glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
            glUniform1i(glGetUniformLocation(program, "BiomeSampler"), 1);
            glUniform1i(glGetUniformLocation(program, "SurfaceColors"), 5);
            glUniform1i(glGetUniformLocation(program, "VolumeSampler"), 7);
            glUniform1f(glGetUniformLocation(program, "VolumeMode"), 1);
            glUniform3f(glGetUniformLocation(program, "VolumeOrigin"), 0, 0, 0);
            glUniform2f(glGetUniformLocation(program, "NoiseOffset"), 0, 0);
            for (int i = 0; i < SETTINGS.length; i++) glUniform1f(glGetUniformLocation(program, SETTINGS[i]), DEFAULTS[i]);
        }
    }

    private int link(String vertex, String fragment) {
        int program = TerrainShaderTest.program(vertex, fragment);
        programs.add(program);
        return program;
    }

    private void auditWorld(World world, List<Scene> scenes) throws Exception {
        // Texture creation goes through RenderSystem's unit 0; restore the atlas afterwards.
        glActiveTexture(GL_TEXTURE0);
        RenderSystem.activeTexture(GL_TEXTURE0);
        RenderSystem.bindTexture(0);
        try (var volume = bake(world)) {
            int vertices = mesh(world);
            fixtures.atlas();
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.blocks));
            glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.colors));
            glActiveTexture(GL_TEXTURE7); glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(volume.index));
            for (var scene : scenes) auditScene(scene, vertices);
        }
    }

    private void auditScene(Scene scene, int vertices) throws Exception {
        System.out.println("Auditing " + scene.name());
        camera(scene);
        resizeByteTarget(WIDTH, HEIGHT);
        checkQuality(scene, vertices, current, "current");
        for (var candidate : candidates.entrySet()) checkQuality(scene, vertices, candidate.getValue(), candidate.getKey());
        measureFetches(scene, vertices);
        measureWork(scene, vertices);
        double blending = 0;
        for (int[] resolution : RESOLUTIONS) {
            resizeByteTarget(resolution[0], resolution[1]);
            String tag = scene.name() + "-" + resolution[0] + "x" + resolution[1];
            int samples = resolution[0] > WIDTH ? 30 : 60;
            double[] overVanilla = ShaderBenchmark.measure("audit-" + tag + "-vanilla-vs-current", draw(vanilla, vertices), draw(current, vertices), samples);
            double[] overReference = ShaderBenchmark.measure("audit-" + tag + "-reference-vs-current", draw(reference, vertices), draw(current, vertices), samples);
            double now = (overVanilla[1] + overReference[1]) / 2;
            timings.append(String.format(Locale.ROOT, "%s,%dx%d,%.4f,%.4f,%.4f,%.3f%n", scene.name(), resolution[0], resolution[1],
                    overVanilla[0], overReference[0], now, overReference[2]));
            if (resolution[0] == WIDTH) blending = overVanilla[3];
            for (var candidate : candidates.entrySet()) {
                double[] times = ShaderBenchmark.measure("audit-" + tag + "-current-vs-" + candidate.getKey().replace(' ', '-'),
                        draw(current, vertices), draw(candidate.getValue(), vertices), samples);
                candidateTimings.append(String.format(Locale.ROOT, "%s,%s,%dx%d,%.4f,%.4f,%.3f%n", scene.name(), candidate.getKey(),
                        resolution[0], resolution[1], times[0], times[1], times[2]));
            }
        }
        resizeByteTarget(WIDTH, HEIGHT);
        for (var ablation : ablations.entrySet()) {
            stage(scene, ablation.getKey(), draw(ablation.getValue(), vertices), vertices, blending);
        }
        for (var setting : SETTING_STAGES.entrySet()) {
            stage(scene, setting.getKey(), without(setting.getValue(), vertices), vertices, blending);
        }
    }

    private void stage(Scene scene, String name, Runnable withoutStage, int vertices, double blending) throws Exception {
        double[] times = ShaderBenchmark.measure("audit-" + scene.name() + "-without-" + name.replace(' ', '-'),
                withoutStage, draw(current, vertices), 40);
        double cost = times[3];
        stages.append(String.format(Locale.ROOT, "%s,%s,%.4f,%.4f,%.4f,%.3f%n", scene.name(), name, times[1], times[0], cost,
                blending > 0 ? cost / blending : 0));
    }

    /* The optimized shader must render what the reference renders, apart from rare rounding flips. */
    private void checkQuality(Scene scene, int vertices, int program, String label) throws Exception {
        int[] before = capture(reference, vertices), after = capture(program, vertices);
        int covered = 0, changed = 0, visible = 0, largest = 0;
        long total = 0;
        // Brightest differences in red, scaled up so one-level rounding still shows.
        int[] difference = new int[before.length];
        for (int i = 0; i < before.length; i++) {
            if (before[i] >>> 24 != 0) covered++;
            int d = 0;
            for (int shift = 0; shift < 24; shift += 8) d = Math.max(d, Math.abs((before[i] >> shift & 0xFF) - (after[i] >> shift & 0xFF)));
            if (d > 0) changed++;
            if (d > VISIBLE_DIFFERENCE) visible++;
            largest = Math.max(largest, d);
            total += d;
            difference[i] = 0xFF000000 | Math.min(255, d * 16);
        }
        quality.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%.6f,%.6f,%.4f,%d%n", scene.name() + (label.equals("current") ? "" : " (" + label + ")"), changed, visible, covered,
                changed / (double) covered, visible / (double) covered, total / (double) covered, largest));
        saveBytes(REVIEW.resolve(scene.name() + "-reference.png"), before);
        String name = scene.name() + "-" + label.replace(' ', '-');
        saveBytes(REVIEW.resolve(name + ".png"), after);
        if (changed > 0) saveBytes(REVIEW.resolve(name + "-difference.png"), difference);
        assertTrue(covered > WIDTH * HEIGHT / 4, scene.name() + " must cover the frame with terrain");
        assertTrue(changed <= covered * MAX_CHANGED, scene.name() + ": optimization changed " + changed + " of " + covered + " pixels");
        assertTrue(visible <= covered * MAX_VISIBLE, scene.name() + ": optimization visibly changed " + visible + " of " + covered
                + " pixels (largest difference " + largest + " levels)");
    }

    /* Mean work per pixel that runs at least one scan, from the work counter's three pages. */
    private void measureWork(Scene scene, int vertices) {
        float[][] pages = new float[3][];
        for (int page = 0; page < 3; page++) {
            glBindFramebuffer(GL_FRAMEBUFFER, floatTarget);
            glViewport(0, 0, WIDTH, HEIGHT);
            glUseProgram(workCounter);
            glUniform1i(glGetUniformLocation(workCounter, "bb_CountPage"), page);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, vertices);
            pages[page] = new float[WIDTH * HEIGHT * 4];
            glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_FLOAT, pages[page]);
        }
        double[] sums = new double[10];
        int covered = 0, scanning = 0;
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            if (pages[2][i * 4 + 3] < 0.5F) continue;
            covered++;
            if (pages[1][i * 4] + pages[1][i * 4 + 1] == 0) continue;
            scanning++;
            for (int k = 0; k < 10; k++) sums[k] += pages[k / 4][i * 4 + k % 4];
        }
        var row = new StringBuilder(String.format(Locale.ROOT, "%s,%.4f", scene.name(), scanning / (double) covered));
        double lookups = sums[0], hits = sums[1] + sums[2] + sums[3];
        for (double value : new double[]{lookups, sums[9], sums[1], sums[2], sums[3], lookups - hits, sums[4], sums[5], sums[8], sums[6], sums[7]}) {
            row.append(String.format(Locale.ROOT, ",%.2f", value / Math.max(scanning, 1)));
        }
        work.append(row).append('\n');
    }

    private void measureFetches(Scene scene, int vertices) throws Exception {
        var counters = new LinkedHashMap<String, Integer>();
        counters.put("reference", referenceCounter);
        counters.put("current", currentCounter);
        counters.putAll(candidateCounters);
        for (var counter : counters.entrySet()) {
            String shader = counter.getKey();
            int program = counter.getValue();
            float[][] pages = new float[2][];
            for (int page = 0; page < 2; page++) {
                glBindFramebuffer(GL_FRAMEBUFFER, floatTarget);
                glViewport(0, 0, WIDTH, HEIGHT);
                glUseProgram(program);
                glUniform1i(glGetUniformLocation(program, "bb_CountPage"), page);
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, vertices);
                pages[page] = new float[WIDTH * HEIGHT * 4];
                glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_FLOAT, pages[page]);
            }
            double[] samplers = new double[7];
            var totals = new ArrayList<Float>();
            float[] heat = new float[WIDTH * HEIGHT];
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                if (pages[1][i * 4 + 3] < 0.5F) continue;
                float total = 0;
                for (int s = 0; s < 7; s++) {
                    float count = pages[s / 4][i * 4 + s % 4];
                    samplers[s] += count;
                    if (s < 6) total += count;
                }
                totals.add(total);
                heat[i] = total;
            }
            totals.sort(null);
            int covered = totals.size();
            double mean = totals.stream().mapToDouble(Float::doubleValue).sum() / covered;
            var row = new StringBuilder(String.format(Locale.ROOT, "%s,%s,%d,%.2f,%.0f,%.0f", shader, scene.name(), covered, mean,
                    totals.get(covered * 95 / 100), totals.get(covered - 1)));
            for (double sum : samplers) row.append(String.format(Locale.ROOT, ",%.2f", sum / covered));
            fetches.append(row).append('\n');
            saveHeatmap(REVIEW.resolve(scene.name() + "-fetches-" + shader + ".png"), heat);
            Double budget = FETCH_BUDGETS.get(scene.name());
            if (program == currentCounter && budget != null) {
                assertTrue(mean <= budget, scene.name() + " averages " + mean + " texture fetches per pixel, over its budget of " + budget);
            }
        }
    }

    /*
     Wraps every texture read in a per-sampler counter and writes the counts instead of the
     color: page 0 holds the volume index, block, color and material reads; page 1 holds
     noise and atlas reads and size queries (a sampler round trip on some GPUs), with alpha
     marking covered pixels.
     */
    static String countFetches(String source) {
        String counted = source;
        String[][] sites = {
                {"texelFetch(VolumeSampler,", "bb_texelFetch(0, VolumeSampler,"},
                {"texelFetch(BiomeSampler,", "bb_texelFetch(1, BiomeSampler,"},
                {"texelFetch(SurfaceColors,", "bb_texelFetch(2, SurfaceColors,"},
                {"texelFetch(MaterialSampler,", "bb_texelFetch(3, MaterialSampler,"},
                {"textureLod(NoiseSampler,", "bb_textureLod(4, NoiseSampler,"},
                {"textureGrad(Sampler0,", "bb_textureGrad(5, Sampler0,"},
                {"textureLod(Sampler0,", "bb_textureLod(5, Sampler0,"},
                {"texture(Sampler0,", "bb_texture(5, Sampler0,"},
                {"textureSize(", "bb_textureSize("},
        };
        for (String[] site : sites) counted = counted.replace(site[0], site[1]);
        var unwrapped = Pattern.compile("(?<!\\w)(texelFetch|textureLod|textureGrad|texture|textureSize)\\s*\\(").matcher(counted);
        if (unwrapped.find()) fail("The fetch counter does not cover this read: "
                + counted.substring(unwrapped.start(), Math.min(counted.length(), unwrapped.start() + 60)));
        counted = substitute(counted, "fragColor = BB_FOG(color);", "fragColor = bb_CountPage == 0"
                + " ? vec4(bb_count0, bb_count1, bb_count2, bb_count3) : vec4(bb_count4, bb_count5, bb_count6, 1.0);");
        String counters = """
                uniform int bb_CountPage;
                float bb_count0 = 0.0, bb_count1 = 0.0, bb_count2 = 0.0, bb_count3 = 0.0, bb_count4 = 0.0, bb_count5 = 0.0, bb_count6 = 0.0;
                void bb_count(int k) {
                    if (k == 0) bb_count0 += 1.0; else if (k == 1) bb_count1 += 1.0; else if (k == 2) bb_count2 += 1.0;
                    else if (k == 3) bb_count3 += 1.0; else if (k == 4) bb_count4 += 1.0; else bb_count5 += 1.0;
                }
                vec4 bb_texelFetch(int k, sampler2D s, ivec2 p, int lod) { bb_count(k); return texelFetch(s, p, lod); }
                vec4 bb_textureLod(int k, sampler2D s, vec2 uv, float lod) { bb_count(k); return textureLod(s, uv, lod); }
                vec4 bb_textureGrad(int k, sampler2D s, vec2 uv, vec2 dx, vec2 dy) { bb_count(k); return textureGrad(s, uv, dx, dy); }
                vec4 bb_texture(int k, sampler2D s, vec2 uv) { bb_count(k); return texture(s, uv); }
                ivec2 bb_textureSize(sampler2D s, int lod) { bb_count6 += 1.0; return textureSize(s, lod); }
                """;
        return counted.replaceFirst("#version 150\\R", "#version 150\n" + counters);
    }

    /*
     Counts the scans' work instead of drawing: surface lookups, how many read probe hints and
     at which height each found a surface, scans run, neighbors that could donate, donor colors
     resolved and connectivity walk steps.
     Pages of four counters each, like the fetch counter; page 2's alpha marks covered pixels.
     */
    static String countWork(String source) {
        // Every lookup, classified by the height it returns, whichever path found it.
        String counted = substitute(source, "vec3 surfaceAt(ivec2 cell) {", String.join("\n",
                "vec3 bb_surfaceAt(ivec2 cell);",
                "vec3 surfaceAt(ivec2 cell) {",
                "    vec3 found = bb_surfaceAt(cell);",
                "    if (VolumeMode > 0.5) {",
                "        bb_work0 += 1.0;",
                "        float step = found.y - floor(samplePosition.y - 0.001) - 1.0;",
                "        if (found.x > 0.0) { if (step == 0.0) bb_work1 += 1.0; else if (step < 0.0) bb_work2 += 1.0; else bb_work3 += 1.0; }",
                "    }",
                "    return found;",
                "}",
                "vec3 bb_surfaceAt(ivec2 cell) {"));
        counted = substitute(counted, "if (pixel.x >= 0 && cachedHinted) {", "if (pixel.x >= 0 && cachedHinted) { bb_work9 += 1.0;");
        counted = substitute(counted, "if (min(ownTint.r, min(ownTint.g, ownTint.b)) < 0.01) return source;",
                "if (min(ownTint.r, min(ownTint.g, ownTint.b)) < 0.01) return source;\n    if (scale > 1) bb_work4 += 1.0; else bb_work5 += 1.0;");
        counted = substitute(counted, "float height, int face, vec2 uv, vec2 gradX, vec2 gradY, int scale) {",
                "float height, int face, vec2 uv, vec2 gradX, vec2 gradY, int scale) { bb_work6 += 1.0;");
        counted = substitute(counted, "for (int step = 1; step < steps; step++) {", "for (int step = 1; step < steps; step++) { bb_work7 += 1.0;");
        counted = substitute(counted, "total += weight;", "total += weight; if (int(data.x) != ownMaterial) bb_work8 += 1.0;");
        counted = substitute(counted, "fragColor = BB_FOG(color);", "fragColor = bb_CountPage == 0 ? vec4(bb_work0, bb_work1, bb_work2, bb_work3)"
                + " : bb_CountPage == 1 ? vec4(bb_work4, bb_work5, bb_work6, bb_work7) : vec4(bb_work8, bb_work9, 0.0, 1.0);");
        return counted.replaceFirst("#version 150\\R", "#version 150\nuniform int bb_CountPage;\n"
                + "float bb_work0 = 0.0, bb_work1 = 0.0, bb_work2 = 0.0, bb_work3 = 0.0, bb_work4 = 0.0,"
                + " bb_work5 = 0.0, bb_work6 = 0.0, bb_work7 = 0.0, bb_work8 = 0.0, bb_work9 = 0.0;\n");
    }

    static String substitute(String source, String target, String replacement) {
        assertTrue(source.contains(target), "The audit's target is no longer in the shader: " + target);
        return source.replace(target, replacement);
    }

    /* Replaces a function's body, keeping the original under another name so the source still parses. */
    static String stub(String source, String signature, String body) {
        String renamed = signature.replaceFirst("(\\w+)\\(", "bb_unused_$1(");
        return substitute(source, signature + " {", signature + " { " + body + " }\n" + renamed + " {");
    }

    private Runnable draw(int program, int vertices) {
        return () -> {
            glUseProgram(program);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, vertices);
        };
    }

    /* The current shader with one setting at zero, restored after the draw. */
    private Runnable without(String setting, int vertices) {
        int location = glGetUniformLocation(current, setting);
        float restore = DEFAULTS[Arrays.asList(SETTINGS).indexOf(setting)];
        return () -> {
            glUseProgram(current);
            glUniform1f(location, 0);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, vertices);
            glUniform1f(location, restore);
        };
    }

    private int[] capture(int program, int vertices) {
        glBindFramebuffer(GL_FRAMEBUFFER, byteTarget);
        glViewport(0, 0, WIDTH, HEIGHT);
        glUseProgram(program);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, vertices);
        int[] pixels = new int[WIDTH * HEIGHT];
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private void camera(Scene scene) {
        // Camera-relative like the game: chunk offsets cancel the eye, the volume keeps world blocks.
        var view = new Matrix4f().lookAt(scene.eye(), scene.target(), new Vector3f(0, 1, 0)).setTranslation(0, 0, 0);
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), WIDTH / (float) HEIGHT, 0.05F, 400);
        for (int program : programs) {
            glUseProgram(program);
            glUniformMatrix4fv(glGetUniformLocation(program, "ModelViewMat"), false, view.get(new float[16]));
            glUniformMatrix4fv(glGetUniformLocation(program, "ProjMat"), false, projection.get(new float[16]));
            glUniform3f(glGetUniformLocation(program, "ChunkOffset"), -scene.eye().x, -scene.eye().y, -scene.eye().z);
            glUniform3f(glGetUniformLocation(program, "BiomeOffset"), scene.eye().x, scene.eye().y, scene.eye().z);
        }
    }

    private void resizeByteTarget(int width, int height) {
        glBindFramebuffer(GL_FRAMEBUFFER, byteTarget);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, byteColor);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, byteColor, 0);
        glBindRenderbuffer(GL_RENDERBUFFER, byteDepth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, byteDepth);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glViewport(0, 0, width, height);
    }

    private static int framebuffer(int format, int width, int height) {
        int target = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, target);
        glActiveTexture(GL_TEXTURE3);
        int color = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, color);
        glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, GL_RGBA, GL_FLOAT, (java.nio.FloatBuffer) null);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        int depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        return target;
    }

    private static int rolling(int x, int z) {
        return 60 + (int) Math.round(5 * Math.sin(x * 0.11) + 4 * Math.cos(z * 0.09) + 3 * Math.sin((x + z) * 0.05));
    }

    /* Irregular patches about twenty blocks across, like surface-rule seams, with scattered single blocks. */
    private static int patches(int x, int z) {
        if (Math.floorMod(x * 31 + z * 17, 97) == 0) return 3;
        int cellX = Math.floorDiv(x + (int) Math.round(3 * Math.sin(z * 0.4)), 20);
        int cellZ = Math.floorDiv(z + (int) Math.round(3 * Math.cos(x * 0.37)), 20);
        return 1 + Math.floorMod(cellX * 7 + cellZ * 13 + cellX * cellZ, 7);
    }

    private static Vector3f eyeLevel(int x, int z) {
        return new Vector3f(x + 0.5F, rolling(x, z) + 1.62F, z + 0.5F);
    }

    /* Surface blocks carry the world's material; everything under them is stone (material 1). */
    private static TerrainVolume bake(World world) {
        var level = new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) {
                return (pos.getY() < world.height().applyAsInt(pos.getX(), pos.getZ()) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };
        var baked = new ArrayList<TerrainSections.Data>();
        for (int y = 2; y <= 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
            baked.add(TerrainSections.bake(level, new BlockPos(x * 16, y * 16, z * 16), 6, pos -> {
                boolean surface = pos.getY() == world.height().applyAsInt(pos.getX(), pos.getZ()) - 1;
                return 0xFFFFFFFF00000000L | (surface ? world.material().applyAsInt(pos.getX(), pos.getZ()) : 1);
            }));
        }
        // Baked sections stay in memory for every visible mesh, so their size is worth watching.
        long blocks = 0, hints = 0;
        for (var data : baked) {
            blocks += data.voxels().length / 3;
            hints += data.hints().length;
        }
        System.out.printf(Locale.ROOT, "Bake (%s): %d sections; per section %.0f blocks and %.0f hints, %.0f bytes (%.1f%% for hints)%n",
                world.name(), baked.size(), blocks / (double) baked.size(), hints / (double) baked.size(),
                (blocks * 12.0 + hints * 4.0) / baked.size(), 100.0 * hints * 4 / (blocks * 12.0 + hints * 4.0));
        var volume = new TerrainVolume(128);
        volume.prepare(baked);
        volume.upload();
        return volume;
    }

    private int mesh(World world) {
        var values = new ArrayList<Float>();
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            int h = world.height().applyAsInt(x, z), top = world.material().applyAsInt(x, z);
            fixtures.quad(values, top, new float[][]{{x, h, z}, {x + 1, h, z}, {x + 1, h, z + 1}, {x, h, z + 1}}, new float[]{0, 1, 0}, 1);
            for (int[] side : sides) {
                for (int y = world.height().applyAsInt(x + side[0], z + side[1]); y < h; y++) {
                    fixtures.quad(values, y == h - 1 ? top : 1, sideQuad(x, y, z, side),
                            new float[]{side[0], 0, side[1]}, side[0] != 0 ? 0.8F : 0.7F);
                }
            }
        }
        return TerrainShaderTest.uploadMesh(current, values);
    }

    /* Corners from top-left, clockwise as seen from outside, so v runs down the wall. */
    private static float[][] sideQuad(int x, int y, int z, int[] side) {
        if (side[0] > 0) return new float[][]{{x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}, {x + 1, y, z}};
        if (side[0] < 0) return new float[][]{{x, y + 1, z + 1}, {x, y + 1, z}, {x, y, z}, {x, y, z + 1}};
        if (side[1] > 0) return new float[][]{{x, y + 1, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}, {x, y, z + 1}};
        return new float[][]{{x + 1, y + 1, z}, {x, y + 1, z}, {x, y, z}, {x + 1, y, z}};
    }

    private static void saveBytes(Path path, int[] pixels) throws Exception {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int abgr = pixels[y * WIDTH + x];
            image.setRGB(x, HEIGHT - y - 1, (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16) & 0xFF);
        }
        ImageIO.write(image, "png", path.toFile());
    }

    /* Black at zero fetches, through blue and red, to white at 128 or more. Fixed so runs compare. */
    private static void saveHeatmap(Path path, float[] counts) throws Exception {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            float t = Math.min(counts[y * WIDTH + x] / 128.0F, 1.0F);
            int r = Math.round(255 * Math.clamp(t * 3 - 1, 0, 1)), g = Math.round(255 * Math.clamp(t * 3 - 2, 0, 1));
            int b = Math.round(255 * Math.clamp(t < 1 / 3.0F ? t * 3 : 2 - t * 3, 0, 1));
            if (counts[y * WIDTH + x] > 0 && r + g + b == 0) b = 40;
            image.setRGB(x, HEIGHT - y - 1, r << 16 | g << 8 | b);
        }
        ImageIO.write(image, "png", path.toFile());
    }
}
