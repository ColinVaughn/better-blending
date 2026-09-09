package dev.betterblending.fabric;

import dev.betterblending.TerrainShader;
import dev.betterblending.backend.Backend;
import dev.betterblending.backend.ModernBackend;
import dev.betterblending.backend.ModernProgram;

/**
 Fabric setup that differs by Minecraft era. From 26.1 the terrain pipeline is derived
 from vanilla's on first use and compiled lazily, so there is nothing to register.
 */
final class FabricEra {
    private FabricEra() {
    }

    static void initialize() {
        Backend.install(new ModernBackend());
        TerrainShader.setProgram(new ModernProgram());
    }
}
