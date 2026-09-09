
// Vanilla's own fragment path, for faces blending has to leave alone.
void bb_plain_main() {
    vec4 color = BB_SAMPLE_BASE(texCoord0) * vertexColor;
    if (color.a < AlphaCutoff) discard;
    fragColor = BB_FOG(color);
}

void main() {
    // No vertex normal in this format, so derive it from the interpolated position.
    // Only axis-aligned faces are cube faces, and only cube faces can blend.
    vec3 normal = normalize(cross(dFdx(biomePosition), dFdy(biomePosition)));
    if (max(abs(normal.x), max(abs(normal.y), abs(normal.z))) < 0.9999) {
        bb_plain_main();
        return;
    }
    faceNormal = round(normal);
    bb_terrain_main();
}
