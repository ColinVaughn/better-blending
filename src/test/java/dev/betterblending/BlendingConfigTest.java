package dev.betterblending;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class BlendingConfigTest {
    @Test
    void blockExclusionsDefaultNormalizeAndRemainEditable(@TempDir Path directory) throws IOException {
        var gson = new Gson();
        var defaults = gson.fromJson("{}", BlendingConfig.class);
        assertEquals(java.util.List.of("minecraft:melon", "minecraft:pumpkin", "minecraft:carved_pumpkin",
                "minecraft:jack_o_lantern", "minecraft:hay_block"), defaults.excluded_blocks);
        assertEquals(defaults.excluded_blocks,
                gson.fromJson("{\"excluded_blocks\":null}", BlendingConfig.class).copy().excluded_blocks);
        var custom = gson.fromJson("""
                {"excluded_blocks":[null,"Bad ID","melon","minecraft:melon","example:custom_block"]}
                """, BlendingConfig.class).copy();
        assertEquals(java.util.List.of("minecraft:melon", "example:custom_block"), custom.excluded_blocks);
        custom.save(directory);
        var saved = gson.fromJson(Files.readString(directory.resolve("better-blending.json")), BlendingConfig.class);
        assertEquals(custom.excluded_blocks, saved.excluded_blocks);
        var draft = defaults.copy();
        draft.excluded_blocks.clear();
        assertEquals(5, defaults.excluded_blocks.size(), "Cancel must retain active exclusions");
        draft.save(directory);
        assertTrue(gson.fromJson(Files.readString(directory.resolve("better-blending.json")),
                BlendingConfig.class).copy().excluded_blocks.isEmpty(), "An empty list must allow default exclusions again");
    }

    @Test
    void defaultsAndUntrustedStrengthsStayUsable() {
        var gson = new Gson();
        var defaults = gson.fromJson("{}", BlendingConfig.class);
        assertTrue(defaults.vanillaTerrainShaderEnabled());
        assertEquals(0.45F, defaults.terrainBiomeBlendStrength());
        var config = gson.fromJson("""
                {"terrain_biome_blend_strength": -3,
                 "surface_shader_strength": 4,
                 "surface_detail_strength": "NaN"}
                """, BlendingConfig.class);
        assertEquals(0, config.terrainBiomeBlendStrength());
        assertEquals(1, config.surfaceShaderStrength());
        assertEquals(1, config.surfaceDetailStrength());
    }

    @Test
    void newControlsAreBoundedAndLegacySettingsSurvive(@TempDir Path directory) throws IOException {
        var previous = BlendingConfig.INSTANCE;
        var previousDirectory = BlendingConfig.configDirectory;
        try {
            Files.writeString(directory.resolve("better-blending.json"), """
                    {"vanilla_terrain_shader_enabled": false,
                     "terrain_biome_blend_strength": 0.7,
                     "local_blend_strength": -1,
                     "surface_detail_strength": "NaN",
                     "surface_map_size": 1000,
                     "surface_refresh_ticks": -2,
                     "sampling_budget_ms": "Infinity",
                     "disabled_dimensions": [null, "Bad ID", "the_end", "minecraft:the_end"]}
                    """);
            BlendingConfig.load(directory);
            var config = BlendingConfig.INSTANCE;
            assertTrue(config.enabled);
            assertTrue(config.textureAlignedBlending(), "Configs without the setting pick up the aligned default");
            assertFalse(config.vanillaTerrainShaderEnabled());
            assertEquals(0.7F, config.terrainBiomeBlendStrength());
            assertEquals(0, config.localBlendStrength());
            assertEquals(1, config.surfaceDetailStrength());
            assertEquals(256, config.surfaceMapSize());
            assertEquals(1, config.surfaceRefreshTicks());
            assertEquals(1, config.samplingBudgetMs());
            assertEquals(java.util.List.of("minecraft:the_end"), config.disabled_dimensions);
            var draft = config.copy();
            draft.disabled_dimensions.clear();
            draft.local_blend_strength = 0.25F;
            draft.texture_aligned_blending = false;
            draft.surface_shader_strength = 0.6F;
            draft.surface_map_size = 1;
            draft.surface_refresh_ticks = 999;
            draft.sampling_budget_ms = 10;
            assertEquals(1, config.disabled_dimensions.size(), "Cancel must not modify the active config");
            assertEquals(0, config.localBlendStrength());
            assertTrue(config.textureAlignedBlending(), "Cancel must not disable pixel alignment");
            draft.save(directory);
            BlendingConfig.load(directory);
            assertEquals(0.25F, BlendingConfig.INSTANCE.localBlendStrength());
            assertFalse(BlendingConfig.INSTANCE.textureAlignedBlending());
            assertEquals(0.6F, BlendingConfig.INSTANCE.surfaceShaderStrength());
            assertEquals(64, BlendingConfig.INSTANCE.surfaceMapSize());
            assertEquals(200, BlendingConfig.INSTANCE.surfaceRefreshTicks());
            assertEquals(5, BlendingConfig.INSTANCE.samplingBudgetMs());
            assertTrue(BlendingConfig.INSTANCE.disabled_dimensions.isEmpty());
            var saved = BlendingConfig.INSTANCE;
            Files.writeString(directory.resolve("better-blending.json"), "{broken");
            BlendingConfig.load(directory);
            assertSame(saved, BlendingConfig.INSTANCE);
            assertEquals("{broken", Files.readString(directory.resolve("better-blending.json")));
            Path blocked = directory.resolve("not-a-directory");
            Files.writeString(blocked, "keep");
            assertThrows(IOException.class, () -> draft.save(blocked));
            assertEquals("keep", Files.readString(blocked));
        } finally {
            BlendingConfig.INSTANCE = previous;
            BlendingConfig.configDirectory = previousDirectory;
        }
    }
}
