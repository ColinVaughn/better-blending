package dev.betterblending.compat;

/**
 Adapts the shipped shader to the renderer's own vertex decoding, lighting and fog.
 Serves every GL-era Sodium family build: Sodium 0.5 and 0.6, Embeddium 0.3 and 1.0,
 and Rubidium 0.7. Their terrain shaders share a layout but name a few things
 differently, so each difference is read from the source rather than from the renderer.
 */
public final class RendererShaders {
    private RendererShaders() {}

    public static String patch(String path, String source) {
        String original = source.replace("void main()", "void bb_original_main()");
        if (path.endsWith(".vsh")) {
            // Rubidium calls the draw index _vert_mesh_id.
            String drawId = source.contains("_vert_mesh_id") ? "_vert_mesh_id" : "_draw_id";
            return original + """

                    uniform vec3 BiomeOffset;
                    out vec3 biomePosition;
                    void main() {
                        bb_original_main();
                        biomePosition = _vert_position + _get_draw_translation(DRAW_ID)
                                + round(u_RegionOffset + BiomeOffset);
                    }
                    """.replace("DRAW_ID", drawId);
        }
        // The shared algorithm needs no rewriting: it declares only what blending owns
        // and reaches the host through BB_SAMPLE_BASE and BB_FOG, defined just below.
        String terrain = ShaderSources.core();
        String material = source.contains("flat in uint v_Material;") ? """
                #define v_MaterialAlphaCutoff _material_alpha_cutoff(v_Material)
                #define v_MaterialMipBias (_material_use_mips(v_Material) ? 0.0 : float(-MAX_TEXTURE_LOD_BIAS))
                """ : "";
        return original + material + """

                uniform int bb_Enabled;
                vec3 faceNormal;
                #define Sampler0 u_BlockTex
                #define texCoord0 v_TexCoord
                #define ColorModulator vec4(1.0)
                """ + vertexColor(source) + outputName(source) + """
                #ifdef USE_FRAGMENT_DISCARD
                #define AlphaCutoff v_MaterialAlphaCutoff
                #else
                #define AlphaCutoff 0.0
                #endif
                #define bb_mip_bias v_MaterialMipBias
                #define BB_SAMPLE_BASE(uv) texture(Sampler0, uv, bb_mip_bias)
                #define BB_FOG(color) _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd)
                """ + ShaderSources.uniforms() + terrain + """

                void main() {
                    vec3 normal = normalize(cross(dFdx(biomePosition), dFdy(biomePosition)));
                    faceNormal = round(normal);
                    if (bb_Enabled == 0 || max(abs(normal.x), max(abs(normal.y), abs(normal.z))) < 0.9999) {
                        bb_original_main();
                    } else {
                        bb_terrain_main();
                    }
                }
                """;
    }

    /** The per-vertex colour as the algorithm expects it: tint and shade in rgb, alpha 1. */
    private static String vertexColor(String source) {
        // Rubidium passes tint and shade already combined, without alpha.
        if (source.contains("in vec3 v_ColorModulator;")) return "#define vertexColor vec4(v_ColorModulator, 1.0)\n";
        // Sodium 0.5, and Embeddium unless it uses the vanilla colour format, keep
        // ambient occlusion in alpha and apply it to rgb themselves.
        if (source.contains("*= v_Color.a;")) return """
                #ifdef USE_VANILLA_COLOR_FORMAT
                #define vertexColor v_Color
                #else
                #define vertexColor vec4(v_Color.rgb * v_Color.a, 1.0)
                #endif
                """;
        return "#define vertexColor v_Color\n";
    }

    /** Rubidium writes out_FragColor where the others write fragColor. */
    private static String outputName(String source) {
        return source.contains("out vec4 out_FragColor;") ? "#define fragColor out_FragColor\n" : "";
    }
}
