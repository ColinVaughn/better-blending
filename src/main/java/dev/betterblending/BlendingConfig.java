package dev.betterblending;

import com.google.gson.GsonBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;

/** Shared JSON settings; the optional in-game screen saves and applies a detached copy. */
public final class BlendingConfig {
    public enum BlendingStyle {
        FULL_BLEND(0), ISOLATED_BLOCKS(1), SMALL_FEATURES(2);

        final int surfaceNeighbors;

        BlendingStyle(int surfaceNeighbors) { this.surfaceNeighbors = surfaceNeighbors; }
    }

    public static final Logger LOGGER = LoggerFactory.getLogger("better_blending");
    static final List<String> DEFAULT_EXCLUDED_BLOCKS = List.of("minecraft:melon", "minecraft:pumpkin",
            "minecraft:carved_pumpkin", "minecraft:jack_o_lantern", "minecraft:hay_block");
    public static BlendingConfig INSTANCE = new BlendingConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Path configDirectory;
    boolean enabled = true;
    boolean vanilla_terrain_shader_enabled = true;
    boolean texture_aligned_blending = true;
    BlendingStyle blending_style = BlendingStyle.ISOLATED_BLOCKS;
    List<String> disabled_dimensions = new ArrayList<>();
    List<String> excluded_blocks = new ArrayList<>(DEFAULT_EXCLUDED_BLOCKS);
    float terrain_biome_blend_strength = 0.45F;
    float surface_shader_strength = 1.0F;
    float local_blend_strength = 1.0F;
    float surface_detail_strength = 1.0F;
    int surface_map_size = 256;
    int surface_refresh_ticks = 20;
    float sampling_budget_ms = 1.0F;

    public static void load(Path configDirectory) {
        BlendingConfig.configDirectory = configDirectory;
        Path path = configDirectory.resolve("better-blending.json");
        try {
            if (Files.exists(path)) {
                var loaded = GSON.fromJson(Files.readString(path), BlendingConfig.class);
                if (loaded == null) throw new JsonParseException("Expected a configuration object");
                loaded.normalize();
                INSTANCE = loaded;
            } else {
                INSTANCE.save(configDirectory);
            }
        } catch (IOException | JsonParseException exception) {
            LOGGER.warn("Could not load {}; retaining current settings", path, exception);
        }
    }

    public BlendingConfig copy() {
        var copy = GSON.fromJson(GSON.toJson(this), BlendingConfig.class);
        copy.normalize();
        return copy;
    }

    public void save(Path directory) throws IOException {
        normalize();
        Files.createDirectories(directory);
        Path path = directory.resolve("better-blending.json");
        Path temporary = Files.createTempFile(directory, "better-blending-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(this) + "\n");
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void normalize() {
        terrain_biome_blend_strength = terrainBiomeBlendStrength();
        surface_shader_strength = surfaceShaderStrength();
        local_blend_strength = localBlendStrength();
        surface_detail_strength = surfaceDetailStrength();
        surface_map_size = surfaceMapSize();
        surface_refresh_ticks = surfaceRefreshTicks();
        sampling_budget_ms = samplingBudgetMs();
        blending_style = blendingStyle();
        if (disabled_dimensions == null) disabled_dimensions = new ArrayList<>();
        disabled_dimensions = normalizeIds(disabled_dimensions);
        excluded_blocks = normalizeIds(excluded_blocks == null ? DEFAULT_EXCLUDED_BLOCKS : excluded_blocks);
    }

    private static List<String> normalizeIds(List<String> ids) {
        return new ArrayList<>(ids.stream()
                .filter(id -> id != null && ResourceLocation.tryParse(id) != null)
                .map(id -> ResourceLocation.tryParse(id).toString()).distinct().toList());
    }

    public boolean dimensionEnabled(ResourceKey<Level> dimension) {
        return enabled && (disabled_dimensions == null || !disabled_dimensions.contains(dimension./*? if >=26.1 {*/ /*identifier *//*?} else {*/ location /*?}*/().toString()))
                && (vanilla_terrain_shader_enabled || (!dimension.equals(Level.OVERWORLD) && !dimension.equals(Level.NETHER)));
    }

    public boolean vanillaTerrainShaderEnabled() { return vanilla_terrain_shader_enabled; }
    public boolean textureAlignedBlending() { return texture_aligned_blending; }
    public BlendingStyle blendingStyle() {
        return blending_style == null ? BlendingStyle.ISOLATED_BLOCKS : blending_style;
    }
    public float terrainBiomeBlendStrength() { return bounded(terrain_biome_blend_strength, 0.45F); }
    public float surfaceShaderStrength() { return bounded(surface_shader_strength, 1.0F); }
    public float localBlendStrength() { return bounded(local_blend_strength, 1.0F); }
    public float surfaceDetailStrength() { return bounded(surface_detail_strength, 1.0F); }
    public int surfaceMapSize() { return Mth.clamp(surface_map_size, 64, 256); }
    public int surfaceRefreshTicks() { return Mth.clamp(surface_refresh_ticks, 1, 200); }
    public float samplingBudgetMs() {
        return Float.isFinite(sampling_budget_ms) ? Mth.clamp(sampling_budget_ms, 0.1F, 5.0F) : 1.0F;
    }

    private static float bounded(float value, float fallback) {
        return Float.isFinite(value) ? Mth.clamp(value, 0.0F, 1.0F) : fallback;
    }
}
