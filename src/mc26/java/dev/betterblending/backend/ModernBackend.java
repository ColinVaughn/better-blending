package dev.betterblending.backend;

/**
 26.x backend. Textures are GPU texture objects written through the command encoder,
 which leaves no host upload state to preserve. Chunk renderers draw through render
 pipelines here too, so there is no bound program to reach into. Sodium's terrain
 instead draws with a blended copy of its pipeline, bound on the pass (see
 {@link ModernProgram#replaceRenderer}), and the renderer hooks below have nothing to do.
 */
public final class ModernBackend implements TerrainBackend {
    @Override
    public TerrainTexture createTexture(String label, int width, int height, boolean linear) {
        return new ModernTexture(label, width, height, linear);
    }

    @Override
    public void uploadBatch(Runnable uploads) {
        uploads.run();
    }

    @Override
    public void bindRendererProgram(TerrainProgram program, TerrainTexture[] textures, boolean enabled) {
    }

    @Override
    public void unbindRendererProgram() {
    }
}
