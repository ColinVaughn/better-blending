#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>
#include <better_blending:terrain_params.glsl>

// Host bindings for the 26.3 terrain pipeline: uniform blocks, spherical and
// cylindrical fog, chunk fade-in, and texel-snapped atlas sampling. Every stage input
// and output carries the location vanilla's terrain shader gives it.
uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;

layout(location = 0) out vec4 fragColor;

// This vertex format has no normal; the epilogue derives one per fragment.
vec3 faceNormal;

#define ColorModulator vec4(1.0)
#ifdef ALPHA_CUTOUT
#define AlphaCutoff ALPHA_CUTOUT
#else
#define AlphaCutoff 0.0
#endif

// The atlas is sampled with vanilla's own helpers, so faces that blending leaves alone
// look exactly as they would without the mod.
#define BB_SAMPLE_BASE(uv) (UseRgss == 1 ? sampleRGSS(Sampler0, uv, 1.0 / vec2(TextureSize)) : sampleNearest(Sampler0, uv, 1.0 / vec2(TextureSize)))
#define BB_FOG(color) apply_fog(mix(FogColor * vec4(1.0, 1.0, 1.0, (color).a), (color), chunkVisibility), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor)
