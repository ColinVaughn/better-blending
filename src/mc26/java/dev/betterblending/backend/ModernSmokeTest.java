package dev.betterblending.backend;

import dev.betterblending.BlendingConfig;

/**
 Development check for the 26.x GPU path. Launched with
 {@code -Dbetter_blending.smokeTest=true}, the client exercises every GPU call the
 blending cache makes through the real device, right after vanilla's own pipelines
 compile, and exits: our pipelines compile through Mojang's shader preprocessor,
 textures are created and written whole and in rectangles, and the uniform buffer
 is written. Unlike the mixin audit this opens the game window, so it needs a GPU.
 {@code rendererCompiled} reports the chunk renderer's pipelines, when one is installed.
 */
public final class ModernSmokeTest {
    private ModernSmokeTest() {
    }

    public static void run(ModernProgram program, boolean compiled, boolean rendererCompiled) {
        boolean passed = compiled && rendererCompiled;
        try {
            try (var texture = new ModernTexture("better_blending:smoke", 128, 64, false)) {
                texture.fill(0, 0, 128, 64, 0x04030201);
                texture.set(5, 7, 0xFFEEDDCC);
                if (texture.get(5, 7) != 0xFFEEDDCC || texture.get(6, 7) != 0x04030201) passed = false;
                texture.upload();
                texture.upload(64, 0, 64, 64);
                texture.upload(3, 5, 17, 9);
            }
            for (String name : TerrainUniforms.MIRRORED) program.uniform(name, 1.0F, 2.0F, 3.0F);
            program.flush();
        } catch (RuntimeException exception) {
            BlendingConfig.LOGGER.error("Better Blending GPU smoke test failed", exception);
            passed = false;
        }
        BlendingConfig.LOGGER.info("Better Blending GPU smoke test {}: pipelines compiled={}, renderer pipelines compiled={}",
                passed ? "passed" : "FAILED", compiled, rendererCompiled);
        System.exit(passed ? 0 : 1);
    }
}
