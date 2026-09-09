package dev.betterblending.compat;

import dev.betterblending.BlendingConfig;
import dev.betterblending.backend.TerrainUniforms;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import java.util.ArrayList;
import static org.lwjgl.opengl.GL33C.*;

/**
 The Iris check of the 1.20.1 smoke test, for Iris 1.7 on Fabric and Oculus 1.8 on
 Forge. Blending patches inside {@code TransformPatcher.patchSodium}, so calling it
 with a minimal pack terrain program returns exactly what a real pack's program would
 become. The result is linked on the game's GL context, and blending's uniforms and
 samplers must survive linking as active.
 */
public final class IrisSmokeTest {
    private static final String VERTEX = """
            #version 330 compatibility
            out vec2 texcoord;
            out vec2 lmcoord;
            out vec4 glcolor;
            void main() {
                gl_Position = ftransform();
                texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
                glcolor = gl_Color;
            }
            """;
    private static final String FRAGMENT = """
            #version 330 compatibility
            uniform sampler2D gtexture;
            uniform sampler2D lightmap;
            in vec2 texcoord;
            in vec2 lmcoord;
            in vec4 glcolor;
            /* RENDERTARGETS: 0 */
            layout(location = 0) out vec4 color;
            void main() {
                color = texture(gtexture, texcoord) * glcolor * texture(lightmap, lmcoord);
                if (color.a < 0.1) discard;
            }
            """;

    private IrisSmokeTest() {
    }

    public static boolean run() {
        var sources = TransformPatcher.patchSodium("better_blending_smoke", VERTEX, null, null, null, FRAGMENT,
                AlphaTests.ONE_TENTH_ALPHA, new ShaderAttributeInputs(true, true, false, true, true),
                Object2ObjectMaps.emptyMap());
        String vertex = sources.get(PatchShaderType.VERTEX), fragment = sources.get(PatchShaderType.FRAGMENT);
        if (!fragment.contains("bb_blend(")) {
            BlendingConfig.LOGGER.error("Iris smoke test: blending did not patch Iris's transformed fragment shader");
            return false;
        }
        int program = glCreateProgram();
        int vs = compile(GL_VERTEX_SHADER, vertex), fs = compile(GL_FRAGMENT_SHADER, fragment);
        try {
            if (vs == 0 || fs == 0) return false;
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
                BlendingConfig.LOGGER.error("Iris smoke test: link failed:\n{}", glGetProgramInfoLog(program));
                return false;
            }
            var names = new ArrayList<String>();
            names.add("bb_Enabled");
            names.add("bb_Sampler0");
            for (String name : TerrainUniforms.SAMPLERS) names.add("bb_" + name);
            for (String name : TerrainUniforms.MIRRORED) names.add("bb_" + name);
            var missing = names.stream().filter(name -> glGetUniformLocation(program, name) < 0).toList();
            // A uniform the algorithm never reads is optimized away, which is harmless; one
            // that should drive blending but is inactive means the patch lost its wiring.
            BlendingConfig.LOGGER.info("Iris smoke test: linked; inactive blending uniforms: {}", missing);
            return !missing.contains("bb_Enabled") && !missing.contains("bb_Sampler0") && !missing.contains("bb_BiomeOffset");
        } finally {
            if (vs != 0) glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
            glDeleteProgram(program);
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != 0) return shader;
        BlendingConfig.LOGGER.error("Iris smoke test: {} shader failed to compile:\n{}",
                type == GL_VERTEX_SHADER ? "vertex" : "fragment", glGetShaderInfoLog(shader));
        glDeleteShader(shader);
        return 0;
    }
}
