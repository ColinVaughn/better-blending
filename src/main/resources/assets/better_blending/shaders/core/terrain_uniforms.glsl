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
