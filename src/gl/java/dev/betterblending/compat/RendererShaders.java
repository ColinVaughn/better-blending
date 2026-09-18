package dev.betterblending.compat;

import dev.betterblending.BlendingConfig;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 Adapts the shipped shader to the renderer's own vertex decoding, lighting and fog.
 Serves every GL-era Sodium family build: Sodium 0.5 and 0.6, Embeddium 0.3 and 1.0,
 and Rubidium 0.7. Their terrain shaders share a layout but name a few things
 differently, so each difference is read from the source rather than from the renderer.
 */
public final class RendererShaders {
    /* Sodium's terrain shaders, by the namespace each renderer family keeps them under. */
    private static final Set<String> NAMESPACES = Set.of("sodium", "embeddium");
    private static final String VERTEX = "blocks/block_layer_opaque.vsh";
    private static final String FRAGMENT = "blocks/block_layer_opaque.fsh";
    private static final String MAIN = "void main()";
    /* Every host name the patched vertex stage reaches for. */
    private static final List<String> VERTEX_NAMES = List.of("_vert_position", "_get_draw_translation", "u_RegionOffset");
    /* Every host name the patched fragment stage reaches for, apart from its colour output. */
    private static final List<String> FRAGMENT_NAMES = List.of("u_BlockTex", "v_TexCoord", "v_Color",
            "_linearFog", "v_FragDistance", "u_FogColor", "u_FogStart", "u_FogEnd");
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private RendererShaders() {}

    /**
     Whether this is the renderer's own opaque terrain shader, in the shape {@link #patch}
     rewrites. The patch replaces the renderer's only terrain shader, with no fallback if it
     fails to compile, so anything unrecognised (an addon's shader, a renamed input) is left
     alone.
     */
    public static boolean patches(String namespace, String path, String source) {
        if (!path.equals(VERTEX) && !path.equals(FRAGMENT)) return false;
        String declined = decline(namespace, path, source);
        if (declined == null) return true;
        // One line per shader, not one per program variant the renderer compiles.
        if (reported.add(namespace + ":" + path)) {
            BlendingConfig.LOGGER.warn("Leaving {}:{} unpatched because {}. That terrain draws unblended.",
                    namespace, path, declined);
        }
        return false;
    }

    /* Why this source cannot be patched, or null when it can. */
    private static @Nullable String decline(String namespace, String path, String source) {
        if (!NAMESPACES.contains(namespace)) return "it is not the renderer's own shader";
        // The epilogue renames the original entry point and supplies the only main. A source
        // with none, or with several, leaves the stage without an entry point or with two.
        int mains = (source.length() - source.replace(MAIN, "").length()) / MAIN.length();
        if (mains != 1) return "it declares main " + mains + " times rather than once";
        for (String name : path.equals(VERTEX) ? VERTEX_NAMES : FRAGMENT_NAMES) {
            if (!source.contains(name)) return "it does not declare " + name;
        }
        // Rubidium's colour output is the one name the fragment epilogue renames instead.
        if (path.equals(FRAGMENT) && !source.contains("fragColor") && !source.contains("out vec4 out_FragColor;")) {
            return "it does not declare a colour output the epilogue can write";
        }
        return null;
    }

    public static String patch(String path, String source) {
        String original = source.replace(MAIN, "void bb_original_main()");
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

    /* The per-vertex colour as the algorithm expects it: tint and shade in rgb, alpha 1. */
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

    /* Rubidium writes out_FragColor where the others write fragColor. */
    private static String outputName(String source) {
        return source.contains("out vec4 out_FragColor;") ? "#define fragColor out_FragColor\n" : "";
    }
}
