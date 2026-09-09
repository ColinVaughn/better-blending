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
