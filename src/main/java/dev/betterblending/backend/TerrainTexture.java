package dev.betterblending.backend;

/**
 A GPU texture owned by the blending cache, with a CPU mirror of its texels. Texel
 values are raw packed bytes with red in the lowest byte ({@code 0xAABBGGRR}), because
 that is the order the GLSL decodes them in. Every era must store and upload exactly
 these bytes, whatever its own texture API calls them. Shared code never sees the
 texture type of an era; only that era's backend and renderer-compat code unwrap it.
 */
public interface TerrainTexture extends AutoCloseable {
    int width();

    int height();

    int get(int x, int y);

    void set(int x, int y, int value);

    void fill(int x, int y, int width, int height, int value);

    /** Uploads every texel. */
    void upload();

    /** Uploads one rectangle. Batch several inside {@link TerrainBackend#uploadBatch}. */
    void upload(int x, int y, int width, int height);

    @Override
    void close();
}
