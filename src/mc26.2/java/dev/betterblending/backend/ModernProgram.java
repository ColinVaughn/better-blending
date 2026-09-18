package dev.betterblending.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.betterblending.BlendingConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 {@link TerrainProgram} for 26.x, where terrain draws through render pipelines and
 blending's uniforms live in the {@code BlendParams} uniform block. Our pipelines are
 derived from vanilla's terrain pipelines at runtime rather than rebuilt by hand: every
 piece of state is copied from the vanilla pipeline, and only the shaders change and one
 bind group is added. A patch release that tunes terrain state is therefore picked up
 without a code change.
 */
public final class ModernProgram implements TerrainProgram {
    /* std140 size of BlendParams. The linked GL program reports the same 80 bytes. */
    private static final int PARAMS_SIZE = 80;
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("better_blending", "core/terrain");
    private static final BindGroupLayout LAYOUT = layout();

    private final Map<String, float[]> uniforms = new HashMap<>();
    private final Map<String, TerrainTexture> samplers = new LinkedHashMap<>();
    /* A chunk renderer's pipelines and our copies of them; empty where a copy failed to compile. */
    private final Map<RenderPipeline, Optional<RenderPipeline>> rendererPipelines = new IdentityHashMap<>();
    private @Nullable RenderPipeline solid, cutout;
    private @Nullable GpuBuffer params;
    private boolean usable = true;

    private static BindGroupLayout layout() {
        var builder = BindGroupLayout.builder().withUniform("BlendParams", UniformType.UNIFORM_BUFFER);
        for (String sampler : TerrainUniforms.SAMPLERS) builder.withSampler(sampler);
        return builder.build();
    }

    @Override
    public void sampler(String name, TerrainTexture texture) {
        samplers.put(name, texture);
    }

    @Override
    public void uniform(String name, float... values) {
        uniforms.put(name, values.clone());
    }

    @Override
    public float @Nullable [] uniformValues(String name) {
        return uniforms.get(name);
    }

    /** Writes BlendParams. Must run outside a render pass, so it runs as the frame begins. */
    @Override
    public void flush() {
        var device = RenderSystem.getDevice();
        if (params == null) {
            params = device.createBuffer(() -> "better_blending:blend_params",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, PARAMS_SIZE);
        }
        try (var stack = MemoryStack.stackPush()) {
            // Order must match terrain_params.glsl; see the layout note there.
            var data = Std140Builder.onStack(stack, PARAMS_SIZE)
                    .putVec3(value("VolumeOrigin", 0), value("VolumeOrigin", 1), value("VolumeOrigin", 2))
                    .putVec3(value("BiomeOffset", 0), value("BiomeOffset", 1), value("BiomeOffset", 2))
                    .putVec3(value("SunDirection", 0), value("SunDirection", 1), value("SunDirection", 2))
                    .putVec2(value("NoiseOffset", 0), value("NoiseOffset", 1))
                    .putFloat(value("VolumeMode", 0))
                    .putFloat(value("BlendStrength", 0))
                    .putFloat(value("BiomeBlendStrength", 0))
                    .putFloat(value("LocalBlendStrength", 0))
                    .putFloat(value("TextureAlignedBlending", 0))
                    .putFloat(value("SurfaceStrength", 0))
                    .get();
            device.createCommandEncoder().writeToBuffer(params.slice(), data);
        }
    }

    private float value(String name, int component) {
        float[] values = uniforms.get(name);
        return values == null || component >= values.length ? 0.0F : values[component];
    }

    /**
     Compiles both pipelines now, as vanilla does for its own during resource loading,
     so a shader that fails to compile falls back to ordinary terrain at load time
     rather than failing on first draw. Returns whether both compiled.
     */
    public boolean precompile() {
        // New shader sources, possibly a renderer's too: its copies compile afresh on next use.
        rendererPipelines.clear();
        solid = derive(RenderPipelines.SOLID_TERRAIN, "terrain_solid");
        cutout = derive(RenderPipelines.CUTOUT_TERRAIN, "terrain_cutout");
        var device = RenderSystem.getDevice();
        usable = device.precompilePipeline(solid).isValid() && device.precompilePipeline(cutout).isValid();
        if (!usable) BlendingConfig.LOGGER.warn("Terrain blending shaders did not compile; using ordinary terrain");
        return usable;
    }

    /** Our pipeline standing in for a vanilla terrain pipeline, or null to leave it alone. */
    public @Nullable RenderPipeline replace(RenderPipeline vanilla) {
        if (!usable) return null;
        if (vanilla == RenderPipelines.SOLID_TERRAIN) {
            if (solid == null) solid = derive(vanilla, "terrain_solid");
            return solid;
        }
        if (vanilla == RenderPipelines.CUTOUT_TERRAIN) {
            if (cutout == null) cutout = derive(vanilla, "terrain_cutout");
            return cutout;
        }
        return null; // Translucent terrain is never blended.
    }

    /**
     Our copy of a chunk renderer's opaque terrain pipeline, drawing with {@code shader}:
     the renderer's own shader with blending patched in. Each copy compiles on first
     request; one that does not compile is remembered, and its terrain draws unblended.
     */
    public @Nullable RenderPipeline replaceRenderer(RenderPipeline renderer, Identifier shader) {
        return rendererPipelines.computeIfAbsent(renderer, key -> {
            var location = key.getLocation();
            var copy = derive(key, Identifier.fromNamespaceAndPath("better_blending",
                    "pipeline/" + location.getNamespace() + "/" + location.getPath()), shader);
            if (RenderSystem.getDevice().precompilePipeline(copy).isValid()) return Optional.of(copy);
            BlendingConfig.LOGGER.warn("Terrain blending did not compile for {}; that terrain draws unblended", location);
            return Optional.empty();
        }).orElse(null);
    }

    /** Whether a pipeline is one of ours, and so needs {@link #bind} on its pass. */
    public boolean owns(RenderPipeline pipeline) {
        if (pipeline == solid || pipeline == cutout) return true;
        for (var copy : rendererPipelines.values()) {
            if (copy.orElse(null) == pipeline) return true;
        }
        return false;
    }

    /** Binds BlendParams and the cache's textures on a pass that has our pipeline set. */
    public void bind(RenderPass pass) {
        pass.setUniform("BlendParams", params);
        samplers.forEach((name, texture) ->
                pass.bindTexture(name, ModernTexture.view(texture), ModernTexture.sampler(texture)));
    }

    private static RenderPipeline derive(RenderPipeline vanilla, String name) {
        return derive(vanilla, Identifier.fromNamespaceAndPath("better_blending", "pipeline/" + name), SHADER);
    }

    private static RenderPipeline derive(RenderPipeline source, Identifier location, Identifier shader) {
        var builder = RenderPipeline.builder()
                .withLocation(location)
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withPolygonMode(source.getPolygonMode())
                .withCull(source.isCull())
                .withDepthStencilState(Optional.ofNullable(source.getDepthStencilState()))
                .withPrimitiveTopology(source.getPrimitiveTopology());
        var targets = source.getColorTargetStates();
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] != null) builder.withColorTargetState(i, targets[i]);
            else builder.withUnusedColorTargetState(i);
        }
        var formats = source.getVertexFormatBindings();
        for (int i = 0; i < formats.length; i++) builder.withVertexBinding(i, formats[i]);
        for (var layout : source.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
        builder.withBindGroupLayout(LAYOUT);
        // Carries ALPHA_CUTOUT across for cutout pipelines, and a renderer's own defines.
        var defines = source.getShaderDefines();
        defines.flags().forEach(builder::withShaderDefine);
        defines.values().forEach((key, value) -> {
            if (value.matches("-?\\d+")) builder.withShaderDefine(key, Integer.parseInt(value));
            else builder.withShaderDefine(key, Float.parseFloat(value));
        });
        return builder.build();
    }
}
