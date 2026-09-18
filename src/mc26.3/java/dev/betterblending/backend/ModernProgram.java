package dev.betterblending.backend;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.betterblending.BlendingConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 {@link TerrainProgram} for 26.3, where terrain draws through render pipelines that
 compile on first use from a pipeline cache, and blending's uniforms live in the
 {@code BlendParams} uniform block. Our pipelines are derived from vanilla's terrain
 pipelines at runtime rather than rebuilt by hand: every piece of state is copied from
 the vanilla pipeline, and only the shaders change and one bind group is added. A patch
 release that tunes terrain state is therefore picked up without a code change.
 */
public final class ModernProgram implements TerrainProgram {
    /* std140 size of BlendParams. The linked GL program reports the same 80 bytes. */
    private static final int PARAMS_SIZE = 80;
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("better_blending", "core/terrain");
    private static final BindGroupLayout LAYOUT = layout();
    /* Vanilla's opaque terrain pipelines, one per draw path: separate draws and multidraw. */
    private static final List<RenderPipeline> VANILLA = List.of(RenderPipelines.SOLID_TERRAIN,
            RenderPipelines.CUTOUT_TERRAIN, RenderPipelines.SOLID_TERRAIN_MULTIDRAW, RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW);

    private final Map<String, float[]> uniforms = new HashMap<>();
    private final Map<String, TerrainTexture> samplers = new LinkedHashMap<>();
    /* Pipelines we stand in for, vanilla's and a chunk renderer's, and our copies; empty where a copy failed to compile. */
    private final Map<RenderPipeline, Optional<RenderPipeline>> copies = new IdentityHashMap<>();
    private @Nullable GpuBuffer params;

    private static BindGroupLayout layout() {
        var builder = BindGroupLayout.builder().withUniform("BlendParams", UniformType.UNIFORM_BUFFER);
        for (String sampler : TerrainUniforms.SAMPLERS) builder.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
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
     Compiles our copy of every vanilla opaque terrain pipeline now, as the shader
     manager's reload does for vanilla's own, so a shader that fails to compile falls
     back to ordinary terrain at load time rather than failing on first draw. Called
     once the reload has installed its new pipeline cache, which our copies then live
     in. Returns whether every copy compiled.
     */
    public boolean precompile() {
        // New shader sources, possibly a renderer's too: every copy compiles afresh.
        copies.clear();
        boolean compiled = true;
        for (var vanilla : VANILLA) compiled &= replace(vanilla) != null;
        if (!compiled) BlendingConfig.LOGGER.warn("Terrain blending shaders did not compile; using ordinary terrain");
        return compiled;
    }

    /** Our pipeline standing in for a vanilla terrain pipeline, or null to leave it alone. */
    public @Nullable RenderPipeline replace(RenderPipeline vanilla) {
        // Translucent terrain is never blended, and wireframe keeps its own look.
        if (!VANILLA.contains(vanilla)) return null;
        return copies.computeIfAbsent(vanilla, key -> compile(derive(key,
                Identifier.fromNamespaceAndPath("better_blending", key.getLocation().getPath()), SHADER))).orElse(null);
    }

    /**
     Our copy of a chunk renderer's opaque terrain pipeline, drawing with {@code shader}:
     the renderer's own shader with blending patched in. Each copy compiles on first
     request; one that does not compile is remembered, and its terrain draws unblended.
     */
    public @Nullable RenderPipeline replaceRenderer(RenderPipeline renderer, Identifier shader) {
        return copies.computeIfAbsent(renderer, key -> {
            var location = key.getLocation();
            return compile(derive(key, Identifier.fromNamespaceAndPath("better_blending",
                    "pipeline/" + location.getNamespace() + "/" + location.getPath()), shader));
        }).orElse(null);
    }

    /*
     Compiles a copy into the current pipeline cache, where later draws find it. A copy
     that fails is not cached there, so the caller must remember the failure.
     */
    private static Optional<RenderPipeline> compile(RenderPipeline copy) {
        if (RenderSystem.getCompiledPipelineNullable(copy) != null) return Optional.of(copy);
        BlendingConfig.LOGGER.warn("Terrain blending did not compile for {}; that terrain draws unblended", copy.getLocation());
        return Optional.empty();
    }

    /** Whether a pipeline is one of ours, and so needs {@link #bind} on its pass. */
    public boolean owns(RenderPipeline pipeline) {
        for (var copy : copies.values()) {
            if (copy.orElse(null) == pipeline) return true;
        }
        return false;
    }

    /** Binds BlendParams and the cache's textures on a pass that has our pipeline set. */
    public void bind(RenderPass pass) {
        pass.setUniform("BlendParams", params);
        samplers.forEach((name, texture) ->
                pass.setUniform(name, ModernTexture.view(texture), ModernTexture.sampler(texture)));
    }

    private static RenderPipeline derive(RenderPipeline source, Identifier location, Identifier shader) {
        var builder = RenderPipeline.builder()
                .withLocation(location)
                .withVertexShader(shader)
                .withFragmentShader(shader)
                .withPolygonMode(source.getPolygonMode())
                .withCull(source.isCull())
                .withDepthStencilState(Optional.ofNullable(source.getDepthStencilState()))
                .withPrimitiveTopology(source.getPrimitiveTopology())
                .withPushConstantSize(source.pushConstantSize());
        var targets = source.getColorTargetStates();
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i) != null) builder.withColorTargetState(i, targets.get(i));
            else builder.withUnusedColorTargetState(i);
        }
        // Multidraw pipelines read per-draw section data from a second, instanced binding.
        var formats = source.getVertexFormatBindings();
        for (int i = 0; i < formats.size(); i++) {
            if (formats.get(i) != null) builder.withVertexBinding(i, formats.get(i));
        }
        for (var layout : source.getBindGroupLayouts()) builder.withBindGroupLayout(layout);
        builder.withBindGroupLayout(LAYOUT);
        // Carries ALPHA_CUTOUT and MULTIDRAW_TERRAIN across, and a renderer's own defines.
        var defines = source.getShaderDefines();
        defines.flags().forEach(builder::withShaderDefine);
        defines.values().forEach((key, value) -> {
            if (value.matches("-?\\d+")) builder.withShaderDefine(key, Integer.parseInt(value));
            else builder.withShaderDefine(key, Float.parseFloat(value));
        });
        return builder.build();
    }
}
