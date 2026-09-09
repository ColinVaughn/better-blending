#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;
uniform vec3 BiomeOffset;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 biomePosition;
// Cube normals must stay exact: interpolation rounding can select an adjacent map row.
flat out vec3 faceNormal;

void main() {
    vec3 pos = Position + ChunkOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    // Chunk origin minus map origin is integral. Cancel camera offsets before
    // adding local vertices, so jumping cannot move the material/noise grid.
    biomePosition = Position + round(ChunkOffset + BiomeOffset);
    faceNormal = Normal;
}
