package dev.betterblending;

import dev.betterblending.compat.RendererShaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opengl.GL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

class RendererShaderTest {
    @Test void irisOnlyChangesAtlasSamplesAndPreservesPackOutputs() {
        String pack = """
                #version 330 core
                uniform sampler2D gtexture, normals, shadowtex0;
                in vec2 uv;
                layout(location=0) out vec4 color;
                layout(location=1) out vec4 normal;
                float noise(vec2 p) { return p.x; }
                vec4 sampleMap(sampler2D tex, vec2 p) { return texture(tex, p); }
                void main() {
                    color = texture(gtexture, uv) * texture(shadowtex0, uv);
                    color += textureLod(gtexture, uv, 0.0) * 0.1;
                    color += textureGrad(gtexture, uv, dFdx(uv), dFdy(uv)) * 0.1;
                    normal = texture(normals, uv) + sampleMap(normals, uv);
                }
                """;
        String patched = dev.betterblending.compat.IrisShaders.fragment(pack);
        String compact = patched.replaceAll("\\s+", "");
        assertTrue(compact.contains("bb_blend(uv,texture(gtexture,uv))"), patched);
        assertTrue(compact.contains("bb_blend(uv,textureLod(gtexture,uv,"));
        assertTrue(compact.contains("bb_blend(uv,textureGrad(gtexture,uv,"));
        assertTrue(compact.contains("normal=texture(normals,uv)"));
        assertTrue(compact.contains("*texture(shadowtex0,uv)"));
        assertTrue(compact.contains("floatbb_noise("));
        assertTrue(compact.contains("floatnoise("));
        assertTrue(compact.contains("returntexture(tex,p);"));
        assertFalse(patched.contains("linear_fog"));
        assertEquals(pack.replace("gtexture", "colortex0"), dev.betterblending.compat.IrisShaders.fragment(pack.replace("gtexture", "colortex0")));
    }

    /**
     Upstream renderer builds whose terrain shaders the GL-era patch adapts, each mapped to
     the asset namespace its shaders live under. The build passes each jar by name.
     */
    static final Map<String, String> RENDERERS = new LinkedHashMap<>();
    static {
        RENDERERS.put("sodium", "sodium");        // Sodium 0.6, Minecraft 1.21.1
        RENDERERS.put("embeddium", "embeddium");  // Embeddium 1.0, Minecraft 1.21.1
        RENDERERS.put("sodium-0.5", "sodium");    // Sodium 0.5, Minecraft 1.20.1 Fabric
        RENDERERS.put("embeddium-0.3", "sodium"); // Embeddium 0.3, Minecraft 1.20.1 Forge
        RENDERERS.put("rubidium", "sodium");      // Rubidium 0.7, Minecraft 1.20.1 Forge
    }

    static String source(String renderer, String namespace, String path) throws Exception {
        String file = System.getProperty("bb.rendererFixture." + renderer);
        if (file == null) throw new AssertionError("No fixture jar for " + renderer);
        try (var jar = new ZipFile(file)) {
            var entry = jar.getEntry("assets/" + namespace + "/shaders/" + path);
            if (entry == null) throw new AssertionError("Missing upstream shader " + renderer + " " + namespace + ":" + path);
            try (var in = jar.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    static String patched(String renderer, String extension) throws Exception {
        String path = "blocks/block_layer_opaque." + extension;
        String source = RendererShaders.patch(path, source(renderer, RENDERERS.get(renderer), path));
        var imports = Pattern.compile("#import <([^:]+):([^>]+)>").matcher(source);
        StringBuilder result = new StringBuilder();
        while (imports.find()) imports.appendReplacement(result,
                java.util.regex.Matcher.quoteReplacement(source(renderer, imports.group(1), imports.group(2))));
        return imports.appendTail(result).toString();
    }

    @Test void adaptersKeepOriginalFallbackAndSharedBlending() throws Exception {
        for (String renderer : RENDERERS.keySet()) {
            String fragment = patched(renderer, "fsh");
            assertTrue(fragment.contains("bb_original_main();"), renderer);
            assertTrue(fragment.contains("bb_terrain_main();"), renderer);
            assertTrue(fragment.contains("blendTerrain(albedo"), renderer);
            assertFalse(fragment.contains("#moj_import"), renderer);
            assertFalse(fragment.contains("uniform float AlphaCutoff;"), renderer);
            String drawId = renderer.equals("rubidium") ? "_vert_mesh_id" : "_draw_id";
            String vertex = patched(renderer, "vsh");
            assertTrue(vertex.contains("_get_draw_translation(" + drawId + ")\n"), renderer);
            assertTrue(vertex.contains("round(u_RegionOffset + BiomeOffset)"), renderer);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BB_SHADER_GL_TEST", matches = "1")
    void realRendererStagesCompileAndLinkForEveryPass() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(32, 32, "Renderer shader check", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            for (String renderer : RENDERERS.keySet()) {
                for (boolean cutout : new boolean[]{false, true}) {
                    for (boolean vanillaColor : new boolean[]{false, true}) {
                        String defines = "\n#define USE_FOG\n#define USE_VERTEX_COMPRESSION\n"
                                + "#define VERT_POS_SCALE 0.00048828125\n#define VERT_POS_OFFSET -8.0\n#define VERT_TEX_SCALE 0.000030517578125\n"
                                + (cutout ? "#define USE_FRAGMENT_DISCARD\n" : "")
                                + (vanillaColor ? "#define USE_VANILLA_COLOR_FORMAT\n" : "");
                        int program = glCreateProgram();
                        for (String stage : new String[]{"vsh", "fsh"}) {
                            int shader = glCreateShader(stage.equals("vsh") ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER);
                            glShaderSource(shader, patched(renderer, stage).replace("#version 330 core", "#version 330 core" + defines));
                            glCompileShader(shader);
                            assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), renderer + " " + stage + ": " + glGetShaderInfoLog(shader));
                            glAttachShader(program, shader);
                            glDeleteShader(shader);
                        }
                        glLinkProgram(program);
                        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), renderer + ": " + glGetProgramInfoLog(program));
                        assertTrue(glGetUniformLocation(program, "bb_Enabled") >= 0);
                        assertTrue(glGetUniformLocation(program, "BiomeOffset") >= 0);
                        glDeleteProgram(program);
                    }
                }
            }
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }
}
