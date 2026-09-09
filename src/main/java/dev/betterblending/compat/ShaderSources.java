package dev.betterblending.compat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Blending's GLSL as shipped, for renderers that assemble their own programs at runtime. */
public final class ShaderSources {
    private ShaderSources() {}

    /** The shared blending algorithm. */
    public static String core() {
        return read("core/terrain_core.glsl");
    }

    /** Blending's uniforms declared loose, for programs that set them one by one. */
    public static String uniforms() {
        return read("core/terrain_uniforms.glsl");
    }

    /** Any file under {@code assets/better_blending/shaders/}, with Unix line endings. */
    public static String read(String path) {
        try (var in = ShaderSources.class.getResourceAsStream("/assets/better_blending/shaders/" + path)) {
            if (in == null) throw new IOException("Missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
