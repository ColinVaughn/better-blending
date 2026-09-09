package dev.betterblending.backend;

/**
 The compiled terrain blending program, reduced to what the shared code needs. Before
 1.21.5 this wraps a {@code ShaderInstance} and its loose uniforms; from 1.21.5 the same
 operations are a render pipeline and uniform buffer writes. Shared code cannot tell the
 difference.
 */
public interface TerrainProgram {
    /** Points a named sampler at one of the blending cache's textures. */
    void sampler(String name, TerrainTexture texture);

    /** Sets a float uniform of one to four components. */
    void uniform(String name, float... values);

    /**
     Current values of a uniform, or null when the program has no such uniform. Used to
     mirror blending uniforms into a chunk renderer's own program, which is a separate
     program object that never went through {@link #uniform}.
     */
    float[] uniformValues(String name);

    /**
     Makes the values set this frame visible to the GPU. Loose uniforms already are;
     eras that keep uniforms in a buffer write it here, before any render pass opens.
     */
    default void flush() {
    }
}
