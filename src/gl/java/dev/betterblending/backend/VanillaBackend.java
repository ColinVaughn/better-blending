package dev.betterblending.backend;

import static org.lwjgl.opengl.GL33C.*;

/**
 Pre-1.21.5 backend. Uniforms are loose GL uniforms and textures are plain GL names,
 so reaching into a chunk renderer's program means poking it with raw GL calls.
 */
public final class VanillaBackend implements TerrainBackend {
    private final int[] previousTextures = new int[TerrainUniforms.SAMPLERS.length];
    private final int[] previousSamplers = new int[TerrainUniforms.SAMPLERS.length];
    private boolean bound;

    @Override
    public TerrainTexture createTexture(String label, int width, int height, boolean linear) {
        return new VanillaTexture(width, height, linear);
    }

    @Override
    public void uploadBatch(Runnable uploads) {
        // NativeImage.upload rewrites the unpack state; hand the host back what it had.
        int rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH), skipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        int skipRows = glGetInteger(GL_UNPACK_SKIP_ROWS), alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        try {
            uploads.run();
        } finally {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength); glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipPixels);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, skipRows); glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
        }
    }

    @Override
    public void bindRendererProgram(TerrainProgram program, TerrainTexture[] textures, boolean enabled) {
        int glProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int enabledLocation = glGetUniformLocation(glProgram, "bb_Enabled");
        if (enabledLocation < 0) return; // Shadow passes and programs without terrain atlas samples.
        glUniform1i(enabledLocation, enabled ? 1 : 0);
        if (!enabled) return;
        boolean iris = glGetUniformLocation(glProgram, "bb_VolumeMode") >= 0;
        for (String name : TerrainUniforms.MIRRORED) {
            float[] values = program.uniformValues(name);
            if (values == null) continue;
            int location = glGetUniformLocation(glProgram, iris ? "bb_" + name : name);
            switch (values.length) {
                case 1 -> glUniform1f(location, values[0]);
                case 2 -> glUniform2f(location, values[0], values[1]);
                case 3 -> glUniform3f(location, values[0], values[1], values[2]);
            }
        }
        if (iris) return; // Iris's sampler allocator binds our textures alongside the pack's.
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int i = 0; i < textures.length; i++) {
            int unit = i + 2; // The renderer owns atlas/lightmap units 0 and 1.
            glActiveTexture(GL_TEXTURE0 + unit);
            previousTextures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
            previousSamplers[i] = glGetInteger(GL_SAMPLER_BINDING);
            glBindSampler(unit, 0);
            glBindTexture(GL_TEXTURE_2D, VanillaTexture.glId(textures[i]));
            glUniform1i(glGetUniformLocation(glProgram, TerrainUniforms.SAMPLERS[i]), unit);
        }
        glActiveTexture(activeTexture);
        bound = true;
    }

    @Override
    public void unbindRendererProgram() {
        if (!bound) return;
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int i = 0; i < previousTextures.length; i++) {
            glActiveTexture(GL_TEXTURE0 + i + 2);
            glBindTexture(GL_TEXTURE_2D, previousTextures[i]);
            glBindSampler(i + 2, previousSamplers[i]);
        }
        glActiveTexture(activeTexture);
        bound = false;
    }
}
