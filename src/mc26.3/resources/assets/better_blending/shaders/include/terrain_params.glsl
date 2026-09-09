#ifndef BETTER_BLENDING_TERRAIN_PARAMS_GLSL
#define BETTER_BLENDING_TERRAIN_PARAMS_GLSL

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

// Every shader is compiled to SPIR-V on this era, even for OpenGL, so each stage input
// and output needs an explicit location. biomePosition takes one clear of vanilla's
// terrain varyings (0 to 4) and Sodium's (0 to 3).
#define BB_BIOME_POSITION_LOCATION 8

#endif
