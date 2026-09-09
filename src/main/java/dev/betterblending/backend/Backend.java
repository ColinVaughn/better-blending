package dev.betterblending.backend;

/**
 Holds the {@link TerrainBackend} for the era this jar was built against. The loader
 entry point installs it before any rendering happens.
 */
public final class Backend {
    private static volatile TerrainBackend instance;

    private Backend() {
    }

    public static void install(TerrainBackend backend) {
        instance = backend;
    }

    public static boolean installed() {
        return instance != null;
    }

    public static TerrainBackend get() {
        TerrainBackend backend = instance;
        if (backend == null) throw new IllegalStateException("No terrain backend installed");
        return backend;
    }
}
