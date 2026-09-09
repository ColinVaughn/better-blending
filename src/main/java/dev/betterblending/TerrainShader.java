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
import dev.betterblending.backend.TerrainProgram;
import dev.betterblending.backend.TerrainTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
//? if >=26.1 {
/*import net.minecraft.world.attribute.EnvironmentAttributes;
*///?}


/** Shared terrain materials and GPU cache for vanilla and optional chunk renderers. */
public final class TerrainShader {
    private static @Nullable TerrainProgram program;
    private static TerrainVolume volume;
    private static volatile TerrainSections sections;
    private static ClientLevel level;
    private static boolean active;
    public static boolean alternateRenderer;
    private static boolean rendererPrepared;

    public static TerrainSections sections() { return sections; }

    private TerrainShader() {
    }

    public static void setProgram(@Nullable TerrainProgram replacement) {
        clear();
        program = replacement;
    }

    /** The installed program whether or not a frame is in progress, for load-time work. */
    public static @Nullable TerrainProgram program() {
        return program;
    }

    public static @Nullable TerrainProgram currentProgram() {
        return currentProgram(0.0F);
    }

    public static @Nullable TerrainProgram currentProgram(float alphaCutoff) {
        if (!active || alternateRenderer) return null;
        program.uniform("AlphaCutoff", alphaCutoff);
        return program;
    }

    public static void begin(Minecraft minecraft, Camera camera) {
        active = false;
        rendererPrepared = false;
        if (program == null || minecraft.level == null) return;
        var config = BlendingConfig.INSTANCE;
        if (!config.dimensionEnabled(minecraft.level.dimension()) || config.surfaceShaderStrength() <= 0.0F) {
            if (sections != null) clear();
            return;
        }
        int requestedSize = Math.min(config.surfaceMapSize(), minecraft.options.getEffectiveRenderDistance() * 32 + 64) / 16 * 16;
        if (level != minecraft.level || sections == null) {
            clear();
            level = minecraft.level;
            sections = new TerrainSections();
            // Config/resource changes need fresh companion data for existing meshes too.
            //? if >=26.1 {
            /*minecraft.levelExtractor.allChanged();
            *///?} else {
            minecraft.levelRenderer.allChanged();
            //?}
        }
        if (volume == null || volume.size != requestedSize) {
            if (volume != null) volume.close();
            volume = new TerrainVolume(requestedSize);
        }
        Vec3 eye = camera./*? if >=26.1 {*/ /*position *//*?} else {*/ getPosition /*?}*/();
        volume.move((Math.floorDiv(Mth.floor(eye.x), 16) - volume.sections / 2) * 16,
                (Math.floorDiv(Mth.floor(eye.y), 16) - volume.sections / 2) * 16,
                (Math.floorDiv(Mth.floor(eye.z), 16) - volume.sections / 2) * 16);
        var materials = sections.materials;
        program.sampler("VolumeSampler", volume.index);
        program.sampler("BiomeSampler", volume.blocks);
        program.sampler("SurfaceColors", volume.colors);
        program.sampler("MaterialSampler", materials.texture);
        program.sampler("NoiseSampler", materials.noise);
        program.uniform("VolumeMode", 1.0F);
        program.uniform("VolumeOrigin", (float) volume.originX, (float) volume.originY, (float) volume.originZ);
        program.uniform("BiomeOffset", (float) (eye.x - volume.originX),
                (float) (eye.y - volume.originY), (float) (eye.z - volume.originZ));
        program.uniform("NoiseOffset", wrappedCoordinate(volume.originX), wrappedCoordinate(volume.originZ));
        program.uniform("BlendStrength", config.surfaceShaderStrength());
        program.uniform("BiomeBlendStrength", BlendingConfig.INSTANCE.terrainBiomeBlendStrength());
        program.uniform("LocalBlendStrength", config.localBlendStrength());
        program.uniform("TextureAlignedBlending", config.textureAlignedBlending() ? 1.0F : 0.0F);
        program.uniform("SurfaceStrength", 0.35F * config.surfaceShaderStrength() * config.surfaceDetailStrength());
        // The same sun the sky draws, in radians.
        //? if >=26.1 {
        /*float angle = camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE,
                minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        *///?} else {
        float angle = level.getTimeOfDay(
                /*? if <1.20.2 {*/ /*minecraft.getFrameTime() *//*?} else {*/ camera.getPartialTickTime() /*?}*/) * Mth.TWO_PI;
        //?}
        program.uniform("SunDirection", -Mth.sin(angle), level.dimensionType().hasSkyLight() ? Mth.cos(angle) : -1.0F, 0.0F);
        program.flush();
        active = true;
    }

    /** The list has already passed Minecraft's section occlusion graph and frustum culling. */
    //? if <1.20.2 {
    /*public static void prepareDraw(java.util.List<net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk> visible) {
    *///?} else {
    public static void prepareDraw(java.util.List<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection> visible) {
    //?}
        if (!active || alternateRenderer) return;
        var ready = new java.util.ArrayList<TerrainSections.Data>();
        for (var render : visible) {
            //? if >=26.1 {
            /*// Uncompiled and empty sections share placeholder meshes that carry no data.
            if (!(render.getSectionMesh() instanceof dev.betterblending.mixin.CompiledSectionAccessor compiled)
                    || !render.getSectionMesh().hasRenderableLayers()) continue;
            *///?} else {
            var compiled = render./*? if <1.20.2 {*/ /*getCompiledChunk *//*?} else {*/ getCompiled /*?}*/();
            if (compiled.hasNoRenderableLayers()) continue;
            //?}
            var visibility = ((dev.betterblending.mixin.CompiledSectionAccessor) compiled).betterBlending$visibility();
            var data = sections.get(visibility);
            if (data != null) {
                ready.add(data);
            }
        }
        prepareRenderer(ready);
    }

    public static boolean needsRendererPrepare() {
        return active && !rendererPrepared && !dev.betterblending.compat.IrisCompatibility.shadowPass();
    }

    public static void prepareRenderer(java.util.List<TerrainSections.Data> ready) {
        if (!active) return;
        volume.prepare(ready);
        // Publication is atomic with respect to drawing: no fragment sees half a section.
        sections.materials.upload();
        volume.upload();
        rendererPrepared = true;
    }

    /** The blending cache's textures, in the order the renderer-compat path expects. */
    private static TerrainTexture[] rendererTextures() {
        var materials = sections.materials;
        return new TerrainTexture[]{volume.index, volume.blocks, volume.colors,
                materials.texture, materials.noise};
    }

    /** Whether a chunk renderer's draws can blend now: this frame's cache is uploaded. */
    public static boolean rendererReady() {
        return active && rendererPrepared;
    }

    /** Adds only blend uniforms/textures to the renderer's currently bound program. */
    public static void bindRenderer() {
        boolean enabled = rendererReady();
        Backend.get().bindRendererProgram(program, enabled ? rendererTextures() : new TerrainTexture[0], enabled);
    }

    /** One of the cache's textures for a renderer that binds them itself, or null while unprepared. */
    public static @Nullable TerrainTexture rendererTexture(int index) {
        if (!rendererReady()) return null;
        var textures = rendererTextures();
        if (index < 0 || index >= textures.length) throw new IllegalArgumentException("Unknown terrain texture " + index);
        return textures[index];
    }

    public static void unbindRenderer() {
        // Config screens and shader reloads can reach this before the loader entry
        // point has installed a backend; there is nothing bound to restore then.
        if (Backend.installed()) Backend.get().unbindRendererProgram();
    }

    static float wrappedCoordinate(double coordinate) {
        // All noise octaves tile at 4096 blocks; wrap in double precision first.
        return (float) (coordinate - Math.floor(coordinate / 4096.0) * 4096.0);
    }

    public static void end() { unbindRenderer(); active = false; }

    public static void clear() {
        unbindRenderer();
        active = false;
        var previous = sections;
        sections = null;
        if (previous != null) previous.close();
        if (volume != null) volume.close();
        volume = null;
        level = null;
    }
}
