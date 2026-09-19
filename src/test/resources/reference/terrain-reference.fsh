#version 150
#moj_import <fog.glsl>

// Host-owned declarations for the vanilla pre-1.21.5 pipeline, where uniforms are
// loose and fog comes from Mojang's fog.glsl.
uniform sampler2D Sampler0;
uniform float AlphaCutoff;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
flat in vec3 faceNormal;
out vec4 fragColor;

#define BB_SAMPLE_BASE(uv) texture(Sampler0, uv)
#define BB_FOG(color) linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor)

// Blending's own scalar and vector uniforms, declared loose. Eras and renderers that
// bind loose uniforms prepend this to terrain_core.glsl. From 1.21.5 loose uniforms
// no longer exist, so those eras declare these same names in a uniform block instead.
uniform float VolumeMode;
uniform vec3 VolumeOrigin;
uniform float SurfaceStrength;
uniform vec3 SunDirection;
uniform vec3 BiomeOffset;
uniform float BlendStrength;
uniform float BiomeBlendStrength;
uniform float LocalBlendStrength;
uniform float TextureAlignedBlending;
uniform vec2 NoiseOffset;

// Terrain blending algorithm, shared by every Minecraft version and by the
// Sodium/Iris paths. It declares only what blending owns.
//
// The including prologue must provide, before this file:
//   Sampler0, texCoord0, vertexColor, ColorModulator, AlphaCutoff, faceNormal,
//   fragColor, the macros BB_SAMPLE_BASE(uv) and BB_FOG(color), and blending's own
//   uniforms: loose from terrain_uniforms.glsl, or the same names in a uniform block.
// The epilogue must call bb_terrain_main(). A host whose stage interfaces need explicit
// locations defines BB_BIOME_POSITION_LOCATION, and its vertex stage writes there.

uniform sampler2D BiomeSampler;
uniform sampler2D VolumeSampler;
uniform sampler2D SurfaceColors;
uniform sampler2D MaterialSampler;
uniform sampler2D NoiseSampler;
#ifdef BB_BIOME_POSITION_LOCATION
layout(location = BB_BIOME_POSITION_LOCATION) in vec3 biomePosition;
#else
in vec3 biomePosition;
#endif
vec3 samplePosition;
vec2 planeOffset;
int faceIndex();

vec2 decodePair(vec4 data) {
    vec4 bytes = round(data * 255.0);
    return bytes.xz + bytes.yw * 256.0;
}
// Project any cube face onto its tangent plane; positive height always points outward.
vec3 planePosition(vec3 p) {
    if (abs(faceNormal.y) > 0.5) return vec3(p.x, p.y * faceNormal.y, p.z);
    if (abs(faceNormal.x) > 0.5) return vec3(p.z, p.x * faceNormal.x, p.y);
    return vec3(p.x, p.z * faceNormal.z, p.y);
}
ivec3 planeBlock(ivec2 cell, int height) {
    if (abs(faceNormal.y) > 0.5) return ivec3(cell.x, faceNormal.y > 0.0 ? height : -height - 1, cell.y);
    if (abs(faceNormal.x) > 0.5) return ivec3(faceNormal.x > 0.0 ? height : -height - 1, cell.y, cell.x);
    return ivec3(cell.x, cell.y, faceNormal.z > 0.0 ? height : -height - 1);
}
ivec2 volumePixel(ivec3 block) {
    int sections = textureSize(VolumeSampler, 0).y;
    if (any(lessThan(block, ivec3(0))) || any(greaterThanEqual(block, ivec3(sections * 16)))) return ivec2(-1);
    ivec3 section = block / 16;
    int page = int(decodePair(texelFetch(VolumeSampler, ivec2(section.x + section.z * sections, section.y), 0)).x) - 1;
    if (page < 0) return ivec2(-1);
    int local = (block.x & 15) | ((block.z & 15) << 4) | ((block.y & 15) << 8);
    return ivec2(page % 32, page / 32) * 64 + ivec2(local & 63, local >> 6);
}
vec3 volumeSurface(ivec3 block, float height) {
    ivec2 pixel = volumePixel(block);
    if (pixel.x < 0) return vec3(0.0);
    vec2 data = decodePair(texelFetch(BiomeSampler, pixel, 0));
    if ((int(data.y) & (1 << faceIndex())) == 0) return vec3(0.0);
    return vec3(float(int(data.x) & 4095), height, float(int(data.x) & 12288));
}
vec3 surfaceAt(ivec2 cell) {
    if (VolumeMode > 0.5) {
        int height = int(floor(samplePosition.y - 0.001));
        for (int i = 0; i < 3; i++) {
            int h = height + (i == 0 ? 0 : i == 1 ? -1 : 1);
            vec3 data = volumeSurface(planeBlock(cell, h), float(h + 1));
            if (data.x > 0.0) return data;
        }
        return vec3(0.0);
    }
    ivec2 size = textureSize(BiomeSampler, 0);
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, size))) return vec3(0.0, -32768.0, 0.0);
    vec2 data = decodePair(texelFetch(BiomeSampler, cell, 0));
    return vec3(float(int(data.x) & 4095), data.y - 32768.0, float(int(data.x) & 12288));
}
vec4 surfaceColor(ivec2 cell, float height) {
    ivec2 pixel = VolumeMode > 0.5 ? volumePixel(planeBlock(cell, int(height) - 1)) : cell;
    return pixel.x < 0 ? vec4(1.0) : texelFetch(SurfaceColors, pixel, 0);
}
vec3 surfaceTint(ivec2 cell, float height) {
    return surfaceColor(cell, height).rgb;
}
float coverage(vec3 position) {
    if (VolumeMode > 0.5) {
        vec3 edge = min(biomePosition, vec3(textureSize(VolumeSampler, 0).y * 16) - biomePosition);
        return smoothstep(2.0, 18.0, min(edge.x, min(edge.y, edge.z)));
    }
    vec2 edge = min(position.xz, vec2(textureSize(BiomeSampler, 0)) - position.xz);
    return smoothstep(2.0, 18.0, min(edge.x, edge.y));
}
int faceIndex() {
    if (faceNormal.y > 0.5) return 1;
    if (faceNormal.y < -0.5) return 0;
    if (abs(faceNormal.z) > 0.5) return faceNormal.z > 0.0 ? 3 : 2;
    return faceNormal.x > 0.0 ? 5 : 4;
}
vec4 rect(int material, int face) {
    return vec4(decodePair(texelFetch(MaterialSampler, ivec2(face * 3, material), 0)),
                decodePair(texelFetch(MaterialSampler, ivec2(face * 3 + 1, material), 0)))
                / vec4(textureSize(Sampler0, 0), textureSize(Sampler0, 0));
}
vec3 flags(int material, int face) {
    return texelFetch(MaterialSampler, ivec2(face * 3 + 2, material), 0).rgb * 255.0;
}
bool inRect(vec2 uv, vec4 bounds) {
    return all(greaterThanEqual(uv, bounds.xy)) && all(lessThan(uv, bounds.zw));
}
vec4 materialColor(int material, int face, vec2 uv, vec2 gradX, vec2 gradY, vec3 tint) {
    vec4 bounds = rect(material, face);
    vec2 span = bounds.zw - bounds.xy;
    if (any(lessThanEqual(span, vec2(0.0)))) return vec4(0.0);
    vec3 properties = flags(material, face);
    vec4 color = textureGrad(Sampler0, bounds.xy + uv * span, gradX * span, gradY * span);
    if (properties.x > 0.5) color.rgb *= tint;
    if (properties.z > 0.5) {
        bounds = rect(material, face + 6);
        span = bounds.zw - bounds.xy;
        vec4 overlay = textureGrad(Sampler0, bounds.xy + uv * span, gradX * span, gradY * span);
        if (overlay.a >= 0.5) {
            color.rgb = overlay.rgb * tint;
            color.a = max(color.a, overlay.a);
        }
    }
    return color;
}
vec2 faceUV(vec3 position) {
    if (abs(faceNormal.y) > 0.5) return position.xz;
    return vec2(abs(faceNormal.x) > 0.5 ? -faceNormal.x * position.z : faceNormal.z * position.x, -position.y);
}
float hash(vec3 cell) {
    vec3 p = fract(mod(cell, 128.0) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    // Wrap before filtering so recentering cannot change UV rounding at large offsets.
    vec3 cell = mod(floor(p), 128.0);
    vec3 t = smoothstep(vec3(0.0), vec3(1.0), fract(p));
    vec2 uv = cell.xy + vec2(37.0, 17.0) * cell.z + t.xy;
    vec2 values = textureLod(NoiseSampler, (uv + 0.5) / 128.0, 0.0).rg;
    return mix(values.r, values.g, t.z);
}

float choiceNoise(vec3 world, vec3 key, int scale) {
    // All three axes prevent vertical stripes; texel clusters stay world-anchored.
    // The face-normal coordinate is exactly on a voxel plane. Perspective
    // interpolation can land either side of it as the camera moves.
    vec3 texel = mix(floor(world * 16.0), round(world * 16.0), abs(faceNormal));
    // Each world-anchored sample competes independently, rather than occupying
    // a band of one shared noise field (which draws nested contour rings).
    vec3 seed = floor(vec3(hash(key), hash(key + 29.0), hash(key + 71.0)) * 128.0);
    float value = mix(noise((texel + 0.5) / 4.0 + seed), hash(texel + seed), 0.35);
    if (scale > 1 && faceNormal.y > 0.5)
        value = mix(noise((texel + 0.5) / 32.0 + seed), value, 0.65);
    // Restore contrast lost to interpolation so small donor weights can still win.
    return clamp(smoothstep(0.15, 0.85, value), 0.0001, 0.9999);
}


float neighborWeight(ivec2 cell, vec3 surface, float height, float scale, vec2 position) {
    if (surface.x == 0.0) return 0.0;
    if (VolumeMode < 0.5 && faceNormal.y < 0.5) {
        vec2 ownCell = floor(biomePosition.xz - faceNormal.xz * 0.001);
        // Vertical faces blend along the wall, never from ground in front of or behind it.
        if (abs(dot(vec2(cell) - ownCell, faceNormal.xz)) > 0.5) return 0.0;
    }
    vec2 distance = abs(vec2(cell) + 0.5 - position);
    vec2 tent = max(vec2(1.5) - distance / scale, vec2(0.0));
    float rise = abs(surface.y - height);
    float across = scale < 1.5 && rise > 0.5 ? 0.5 : 1.0;
    return tent.x * tent.y * across * (1.0 - smoothstep(1.0, 3.0, rise));
}

bool connectedSurface(ivec2 from, ivec2 to, float height, float targetHeight) {
    ivec2 delta = to - from;
    int steps = max(abs(delta.x), abs(delta.y));
    // At most six tangent-plane cells; exposed faces prevent donors through solid walls.
    for (int step = 1; step < steps; step++) {
        vec3 surface = surfaceAt(from + ivec2(round(vec2(delta) * (float(step) / float(steps)))));
        if (surface.x == 0.0 || abs(surface.y - height) > 1.0) return false;
        height = surface.y;
    }
    return abs(targetHeight - height) <= 1.0;
}

vec4 blendTerrain(vec4 source, int ownMaterial, ivec2 cell, float height, int face,
                  vec2 uv, vec2 gradX, vec2 gradY, vec3 world, vec3 blendPosition,
                  float footprint, int scale, float amount) {
    // Compare unnormalized donor scores, then normalize once. This avoids a second
    // neighborhood scan (each 3D surface lookup can fetch three different voxels).
    if (VolumeMode < 0.5 && TextureAlignedBlending < 0.5 && faceNormal.y < 0.5) {
        // Match the grain's texel grid. Continuous weights cut single texels into thin wedges.
        // Only the mask is snapped; texture UVs and their derivatives retain their full scale.
        blendPosition = mix((floor(biomePosition * 16.0) + 0.5) / 16.0, biomePosition, abs(faceNormal));
    }
    float total = 0.0;
    ivec2 base = cell;
    if (scale > 1) base = ivec2(floor((blendPosition.xz + planeOffset - 0.5) / float(scale))) * scale
            + ivec2(scale / 2) - ivec2(planeOffset);
    if (VolumeMode < 0.5 && faceNormal.y < 0.5) base = ivec2(mix(vec2(base), vec2(cell), abs(faceNormal.xz)));
    float strength = BlendStrength * amount * coverage(blendPosition);
    if (VolumeMode < 0.5 && faceNormal.y < 0.5) strength *= 1.0 - smoothstep(0.0, 0.5, height - blendPosition.y);
    if (strength <= 0.0) return source;
    vec3 ownTint = flags(ownMaterial, face).x > 0.5 ? surfaceTint(cell, height) : vec3(1.0);
    if (min(ownTint.r, min(ownTint.g, ownTint.b)) < 0.01) return source;
    float retained = 1.0 - strength;
    float best = 1e20;
    float integrate = smoothstep(0.045 * float(scale), 0.125 * float(scale), footprint);
    vec4 selected = source;
    vec4 average = vec4(0.0);
    for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
        ivec2 neighbor = base + ivec2(x, z) * scale;
        vec3 data = surfaceAt(neighbor);
        float weight = neighborWeight(neighbor, data, height, float(scale), blendPosition.xz);
        if (weight <= 0.0) continue;
        total += weight;
        bool choose = false;
        // Fully filtered distant pixels do not use the stochastic selection at all.
        if (integrate < 1.0) {
            float score = -log(choiceNoise(world, vec3(vec2(neighbor) + planeOffset, float(scale)), scale)) / weight;
            choose = score < best;
            if (choose) best = score;
        }
        if (!choose && integrate <= 0.0) continue;
        vec4 color = source;
        // Rejected donors retain their source weight; renormalizing would amplify distant samples.
        vec4 tint = surfaceColor(neighbor, data.y);
        // Lone blocks lend no regional patches.
        if (int(data.x) != ownMaterial && connectedSurface(cell, neighbor, height, data.y) && (scale == 1 || tint.a >= 0.5)) {
            color = materialColor(int(data.x), face, uv, gradX, gradY, tint.rgb);
            // Transparent neighbor texels must not punch holes in solid ground.
            if (color.a < 0.5) color = source;
            else color.rgb /= ownTint;
            color.a = source.a;
        }
        if (choose) selected = color;
        if (integrate > 0.0) average += color * weight;
    }
    if (total <= 0.0) return source;
    if (integrate < 1.0 && retained > 0.0) {
        float sourceScore = -log(choiceNoise(world, vec3(vec2(cell) + planeOffset, float(scale) + 19.0), scale)) / retained;
        if (best * (total / strength) >= sourceScore) selected = source;
    }
    average = source * retained + average * (strength / total);
    return mix(selected, average, integrate);
}

vec3 shadeSurface(vec3 color, vec3 world, float footprint) {
    // Apply multiscale texture variation and lighting only to identified ground,
    // preserving the original material hues.
    float fine = 1.0 - smoothstep(0.025, 0.12, footprint);
    float rock = 1.0 - smoothstep(0.8, 3.0, footprint);
    float variation = 1.0 + 0.30 * (noise(world / 32.0) - 0.5)
            + 0.14 * (noise(world / 4.0) - 0.5) * rock
            + 0.10 * (noise(world * 8.0) - 0.5) * fine;
    vec3 toEye = BiomeOffset - biomePosition;
    toEye *= inversesqrt(max(dot(toEye, toEye), 0.000001));
    float sun = max(dot(faceNormal, SunDirection), 0.0);
    float eye = max(dot(faceNormal, toEye), 0.0);
    float diffuse = sun / max(sun + eye, 0.15);
    float backscatter = pow(max(dot(toEye, SunDirection), 0.0), 8.0);
    float lighting = mix(1.0, 0.80 + 0.34 * diffuse + 0.10 * backscatter * sun,
            smoothstep(-0.05, 0.25, SunDirection.y));
    float strength = SurfaceStrength * coverage(biomePosition);
    vec3 shaded = color * mix(1.0, variation * lighting, strength);
    // Keep bright ice in range without clipping individual color channels.
    return shaded / max(1.0, max(shaded.r, max(shaded.g, shaded.b)));
}

void bb_terrain_main() {
    vec4 source = BB_SAMPLE_BASE(texCoord0);
    vec4 albedo = source;
    // Derivatives precede divergent material guards; empty/out-of-range columns
    // can then skip the material table and every detail/blending lookup.
    vec2 atlasGradX = dFdx(texCoord0), atlasGradY = dFdy(texCoord0);
    vec3 positionGradX = dFdx(biomePosition), positionGradY = dFdy(biomePosition);
    vec3 worldOffset = VolumeMode > 0.5 ? mod(VolumeOrigin, 4096.0) : vec3(NoiseOffset.x, 0.0, NoiseOffset.y);
    vec3 world = biomePosition + worldOffset;
    samplePosition = VolumeMode > 0.5 ? planePosition(biomePosition) : biomePosition;
    planeOffset = VolumeMode > 0.5 ? planePosition(worldOffset).xz : NoiseOffset;
    ivec3 ownBlock = ivec3(floor(biomePosition - faceNormal * 0.001));
    float footprint = max(length(dFdx(world)), length(dFdy(world)));
    if (source.a * vertexColor.a * ColorModulator.a < AlphaCutoff) discard;
    ivec2 cell = VolumeMode > 0.5 ? ivec2(floor(samplePosition.xz)) : ivec2(floor(biomePosition.xz - faceNormal.xz * 0.001));
    vec3 surface = VolumeMode > 0.5 ? volumeSurface(ownBlock, floor(samplePosition.y - 0.001) + 1.0) : surfaceAt(cell);
    int material = int(surface.x);
    bool applyDetail = false;
    if (material > 0 && (VolumeMode > 0.5 || (faceNormal.y >= 0.0 && abs(surface.y - biomePosition.y) <= 1.01))) {
        int face = faceIndex();
        vec4 bounds = rect(material, face);
        bool blendRegional = BlendStrength > 0.0 && BiomeBlendStrength > 0.0 && (int(surface.z) & 8192) != 0;
        bool blendLocal = BlendStrength > 0.0 && LocalBlendStrength > 0.0 && (int(surface.z) & 4096) != 0;
        bool layered = flags(material, face).z > 0.5;
        // The base pass composites both layers before blending. Suppress the original
        // overlay pass so it cannot paint grass back over a borrowed material.
        if (layered && (blendRegional || blendLocal)) {
            vec4 overlayBounds = rect(material, face + 6);
            if (inRect(texCoord0, overlayBounds)) {
                vec2 ratio = (bounds.zw - bounds.xy) / (overlayBounds.zw - overlayBounds.xy);
                vec4 base = textureGrad(Sampler0, bounds.xy + (texCoord0 - overlayBounds.xy) * ratio,
                        atlasGradX * ratio, atlasGradY * ratio);
                // If a custom base has a cutout hole, its pass could not composite this pixel.
                if (base.a * vertexColor.a * ColorModulator.a >= max(AlphaCutoff, 0.0001)) discard;
            }
        }
        bool matches = inRect(texCoord0, bounds);
        if (matches) {
            vec2 span = max(bounds.zw - bounds.xy, vec2(0.000001));
            vec2 uv = (texCoord0 - bounds.xy) / span;
            vec2 gradX = atlasGradX / span, gradY = atlasGradY / span;
            if (layered && (blendRegional || blendLocal)) {
                source = materialColor(material, face, uv, gradX, gradY, surfaceTint(cell, surface.y));
                albedo = source;
            }
            vec3 blendPosition = biomePosition;
            if (TextureAlignedBlending > 0.5) {
                // Project the actual atlas texel center back onto the face. This follows
                // rotated/mirrored UVs, UV inset and resource-pack resolution.
                vec2 center = (floor(texCoord0 * vec2(textureSize(Sampler0, 0))) + 0.5)
                        / vec2(textureSize(Sampler0, 0));
                vec2 delta = center - texCoord0;
                float determinant = atlasGradX.x * atlasGradY.y - atlasGradX.y * atlasGradY.x;
                if (abs(determinant) > 1e-20) {
                    blendPosition += (positionGradX * (delta.x * atlasGradY.y - delta.y * atlasGradY.x)
                            + positionGradY * (delta.y * atlasGradX.x - delta.x * atlasGradX.y)) / determinant;
                    // Full cube UVs have signed unit axes (apart from the atlas inset).
                    // Reconstruct their centers exactly so perspective rounding cannot
                    // tip material choices within one texel. Keep the projection for custom UVs.
                    vec3 uAxis = (positionGradX * atlasGradY.y - positionGradY * atlasGradX.y) * span.x / determinant;
                    vec3 vAxis = (positionGradY * atlasGradX.x - positionGradX * atlasGradY.x) * span.y / determinant;
                    vec3 uStep = round(uAxis), vStep = round(vAxis);
                    if (dot(uStep, uStep) == 1.0 && dot(vStep, vStep) == 1.0 && dot(uStep, vStep) == 0.0
                            && all(lessThan(abs(uAxis - uStep), vec3(0.02)))
                            && all(lessThan(abs(vAxis - vStep), vec3(0.02)))) {
                        vec2 localCenter = (center - bounds.xy) / span;
                        blendPosition = floor(biomePosition - faceNormal * 0.001) + max(faceNormal, vec3(0.0))
                                + uStep * localCenter.x + vStep * localCenter.y
                                + max(-uStep, vec3(0.0)) + max(-vStep, vec3(0.0));
                    }
                    world = blendPosition + worldOffset;
                    // Borrowed stone keeps a consistent orientation across randomly rotated receivers.
                    uv = fract(faceUV(blendPosition));
                    gradX = faceUV(positionGradX);
                    gradY = faceUV(positionGradY);
                }
            }
            if (VolumeMode > 0.5) blendPosition = planePosition(blendPosition);
            if (blendRegional)
                albedo = blendTerrain(albedo, material, cell, surface.y, face,
                        uv, gradX, gradY, world, blendPosition, footprint, 4, BiomeBlendStrength);
            if (blendLocal) albedo = blendTerrain(albedo, material, cell, surface.y, face,
                    uv, gradX, gradY, world, blendPosition, footprint, 1, LocalBlendStrength);
            applyDetail = SurfaceStrength > 0.0 && !layered && flags(material, face).y < 0.5;
        }
    }
    vec4 color = albedo * vertexColor * ColorModulator;
    // Tint compensation can exceed 1 before vertex tint cancels it. Limit highlights only after that cancellation.
    if (applyDetail) color.rgb = shadeSurface(color.rgb, world, footprint);
    fragColor = BB_FOG(color);
}

void main() {
    bb_terrain_main();
}
