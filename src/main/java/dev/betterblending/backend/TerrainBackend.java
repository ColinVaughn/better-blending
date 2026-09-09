package dev.betterblending.backend;

/**
 GPU work that the shared blending code cannot express portably across Minecraft
 eras. One implementation per era lives in that era's source set. Everything else the
 blending algorithm does is version-free and stays in {@code src/main}. Textures come
 from here because each era allocates, fills and uploads them differently: GL names
 before 1.21.5, GPU texture objects after.
 */
public interface TerrainBackend {
    /**
     Creates a texture whose texels start zeroed.

     @param linear whether sampling filters linearly; data textures read with texelFetch
                   ignore this, but the noise texture relies on it
     */
    TerrainTexture createTexture(String label, int width, int height, boolean linear);

    /**
     Runs a group of rectangle uploads, letting an era preserve and restore any host
     upload state once around the group rather than once per rectangle.
     */
    void uploadBatch(Runnable uploads);

    /**
     Adds blending uniforms and samplers to the program the chunk renderer has
     currently bound, leaving every other part of that program untouched.

     @param enabled false to tell the program to fall through to ordinary rendering
     */
    void bindRendererProgram(TerrainProgram program, TerrainTexture[] textures, boolean enabled);

    /** Restores the texture and sampler bindings that {@link #bindRendererProgram} replaced. */
    void unbindRendererProgram();
}
