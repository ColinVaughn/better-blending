package dev.betterblending.neoforge;

import dev.betterblending.TerrainShader;
import dev.betterblending.backend.Backend;
import dev.betterblending.backend.ModernBackend;
import dev.betterblending.backend.ModernProgram;
import net.neoforged.bus.api.IEventBus;

/**
 NeoForge setup that differs by Minecraft era. From 26.1 the terrain pipeline is derived
 from vanilla's on first use and compiled lazily, so there is nothing to register.
 */
final class NeoForgeEra {
    private NeoForgeEra() {
    }

    static void initialize(IEventBus modBus) {
        Backend.install(new ModernBackend());
        TerrainShader.setProgram(new ModernProgram());
    }
}
