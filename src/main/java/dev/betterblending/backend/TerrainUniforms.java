package dev.betterblending.backend;

/**
 Names shared between the blending program, the GLSL sources and the mirror into a
 chunk renderer's own program. Kept in one place so those three cannot drift apart.
 */
public final class TerrainUniforms {
    private TerrainUniforms() {
    }

    /** Samplers, in the order the renderer-compat path assigns texture units. */
    public static final String[] SAMPLERS = {
            "VolumeSampler", "BiomeSampler", "SurfaceColors", "MaterialSampler", "NoiseSampler"
    };

    /** Uniforms that must be mirrored into a chunk renderer's program. */
    public static final String[] MIRRORED = {
            "VolumeMode", "VolumeOrigin", "BiomeOffset", "NoiseOffset", "BlendStrength",
            "BiomeBlendStrength", "LocalBlendStrength", "TextureAlignedBlending",
            "SurfaceStrength", "SunDirection"
    };
}
