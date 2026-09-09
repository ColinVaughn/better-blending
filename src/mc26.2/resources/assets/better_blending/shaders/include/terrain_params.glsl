#version 330

// Blending's own uniforms for eras without loose uniforms. Member names match
// terrain_uniforms.glsl exactly, so terrain_core.glsl reads them unchanged.
//
// ModernProgram writes this block with Std140Builder in this exact order. Keep every
// vec3 ahead of the smaller members: Std140Builder advances 16 bytes per vec3, while
// std140 would pack a following float into the vec3's last 4 bytes, and the two
// layouts would silently disagree from that member on.
layout(std140) uniform BlendParams {
    vec3 VolumeOrigin;
    vec3 BiomeOffset;
    vec3 SunDirection;
    vec2 NoiseOffset;
    float VolumeMode;
    float BlendStrength;
    float BiomeBlendStrength;
    float LocalBlendStrength;
    float TextureAlignedBlending;
    float SurfaceStrength;
};
