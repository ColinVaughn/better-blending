package dev.betterblending.compat;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

/**
 Sodium 0.9's terrain shader with blending added. Sodium draws terrain through render
 pipelines built on its own shader, so blending uses a copy of each opaque pipeline that
 names this shader instead. The source patched is Sodium's own, as the shader manager
 loaded it, so vertex decoding, fog and atlas sampling stay exactly Sodium's.
 */
public final class SodiumShaders {
    /** Sodium's opaque terrain shader, both stages. */
    public static final Identifier SODIUM = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");
    /** The patched copy. ShaderManagerMixin serves it under this name. */
    public static final Identifier BLENDED = Identifier.fromNamespaceAndPath("better_blending", "sodium/block_layer_opaque");

    private SodiumShaders() {}

    /**
     Patches one stage. {@code source} has its imports already inlined, so blending's
     own include is inlined here too. If Sodium's entry point ever changes shape, the
     result declares main twice and fails to compile, and that terrain draws unblended.
     */
    public static String patch(ShaderType type, String source) {
        String original = source.replace("void main()", "void bb_original_main()");
        String params = ShaderSources.read("include/terrain_params.glsl").replaceFirst("(?m)^#version[^\\n]*\\n", "");
        if (type == ShaderType.VERTEX) {
            return original + params + """

                    out vec3 biomePosition;
                    void main() {
                        bb_original_main();
                        // The region's offset from the camera plus the camera's from the volume
                        // is the integral region-to-volume offset; rounding the sum is exact.
                        biomePosition = _vert_position + _get_draw_translation(_draw_id)
                                + round(u_RegionOffset + BiomeOffset);
                    }
                    """;
        }
        // The shared algorithm reaches the host only through the names defined here.
        return original + params + """

                vec3 faceNormal;
                #define Sampler0 u_BlockTex
                #define texCoord0 v_TexCoord
                #define vertexColor v_Color
                #define ColorModulator vec4(1.0)
                #ifdef ALPHA_CUTOUT
                #define AlphaCutoff ALPHA_CUTOUT
                #else
                #define AlphaCutoff 0.0
                #endif
                #define BB_SAMPLE_BASE(uv) (u_UseRGSS ? sampleRGSS(u_BlockTex, uv, u_TexelSize) : sampleNearest(u_BlockTex, uv, u_TexelSize))
                #define BB_FOG(color) _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor)
                """ + ShaderSources.core() + """

                void main() {
                    // No vertex normal in Sodium's format either; derive it per fragment.
                    // Only axis-aligned faces are cube faces, and only cube faces can blend.
                    vec3 normal = normalize(cross(dFdx(biomePosition), dFdy(biomePosition)));
                    if (max(abs(normal.x), max(abs(normal.y), abs(normal.z))) < 0.9999) {
                        bb_original_main();
                        return;
                    }
                    faceNormal = round(normal);
                    bb_terrain_main();
                }
                """;
    }
}
