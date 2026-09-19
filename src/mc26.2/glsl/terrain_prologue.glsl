#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <better_blending:terrain_params.glsl>

// Host bindings for the 26.x terrain pipeline: uniform blocks, spherical and
// cylindrical fog, chunk fade-in, and texel-snapped atlas sampling.
uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

// This vertex format has no normal; the epilogue derives one per fragment.
vec3 faceNormal;

#define ColorModulator vec4(1.0)
#ifdef ALPHA_CUTOUT
#define AlphaCutoff ALPHA_CUTOUT
#else
#define AlphaCutoff 0.0
#endif

// The atlas is sampled the way vanilla terrain samples it, so faces that blending
// leaves alone look exactly as they would without the mod: the nearest texel at a
// footprint-dependent mip, with rotated-grid supersampling when that option is on.
vec4 bb_sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5;
    vec2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = clamp((texelOffset - 0.5) * pixelSize / texelScreenSize + 0.5, 0.0, 1.0);
    return textureGrad(source, (texelCenter + texelOffset) * pixelSize, du, dv);
}

vec4 bb_sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv), dv = dFdy(uv);
    return bb_sampleNearest(source, uv, pixelSize, du, dv, sqrt(du * du + dv * dv));
}

vec4 bb_sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv), dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, max(texelScreenSize.x, texelScreenSize.y));
    float effectiveDerivative = sqrt(min(length(du), length(dv)) * max(length(du), length(dv)));
    float mipLevel = max(0.0, log2(effectiveDerivative / minPixelSize));
    float mipLow = floor(mipLevel);
    const vec2 offsets[4] = vec2[](vec2(0.125, 0.375), vec2(-0.125, -0.375), vec2(0.375, -0.125), vec2(-0.375, 0.125));
    vec4 low = vec4(0.0), high = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        low += textureLod(source, sampleUV, mipLow);
        high += textureLod(source, sampleUV, mipLow + 1.0);
    }
    vec4 rgss = mix(low * 0.25, high * 0.25, fract(mipLevel));
    return mix(bb_sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize), rgss, blendFactor);
}

#define BB_RGSS (UseRgss == 1)
#define BB_SAMPLE_BASE(uv) (UseRgss == 1 ? bb_sampleRGSS(Sampler0, uv, 1.0 / vec2(TextureSize)) : bb_sampleNearest(Sampler0, uv, 1.0 / vec2(TextureSize)))
#define BB_FOG(color) apply_fog(mix(FogColor * vec4(1.0, 1.0, 1.0, (color).a), (color), ChunkVisibility), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor)
