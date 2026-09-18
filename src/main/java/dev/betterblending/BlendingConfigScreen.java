package dev.betterblending;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;

/** Loaded only when Cloth Config is installed. */
public final class BlendingConfigScreen {
    public static Screen create(Screen parent) {
        var config = BlendingConfig.INSTANCE.copy();
        var builder = ConfigBuilder.create().setParentScreen(parent).setTitle(text("title"));
        var entries = builder.entryBuilder();
        var blending = builder.getOrCreateCategory(text("blending"));
        blending.addEntry(entries.startBooleanToggle(text("enabled"), config.enabled)
                .setDefaultValue(true).setSaveConsumer(value -> config.enabled = value).build());
        blending.addEntry(entries.startBooleanToggle(text("vanilla"), config.vanillaTerrainShaderEnabled())
                .setDefaultValue(true).setTooltip(text("vanilla.tooltip"))
                .setSaveConsumer(value -> config.vanilla_terrain_shader_enabled = value).build());
        blending.addEntry(entries.startStrList(text("disabled_dimensions"), new ArrayList<>(config.disabled_dimensions))
                .setDefaultValue(new ArrayList<>()).setTooltip(text("disabled_dimensions.tooltip"))
                .setCellErrorSupplier(value -> ResourceLocation.tryParse(value) == null
                        ? Optional.of(text("invalid_dimension")) : Optional.empty())
                .setSaveConsumer(value -> config.disabled_dimensions = new ArrayList<>(value)).build());
        blending.addEntry(entries.startStrList(text("excluded_blocks"), new ArrayList<>(config.excluded_blocks))
                .setDefaultValue(new ArrayList<>(BlendingConfig.DEFAULT_EXCLUDED_BLOCKS))
                .setTooltip(text("excluded_blocks.tooltip"))
                .setCellErrorSupplier(value -> ResourceLocation.tryParse(value) == null
                        ? Optional.of(text("invalid_block")) : Optional.empty())
                .setSaveConsumer(value -> config.excluded_blocks = new ArrayList<>(value)).build());
        strength(entries, blending, "strength", config.surfaceShaderStrength(), 100,
                value -> config.surface_shader_strength = value);
        blending.addEntry(entries.startBooleanToggle(text("texture_aligned"), config.textureAlignedBlending())
                .setDefaultValue(true).setTooltip(text("texture_aligned.tooltip"))
                .setSaveConsumer(value -> config.texture_aligned_blending = value).build());
        strength(entries, blending, "local", config.localBlendStrength(), 100,
                value -> config.local_blend_strength = value);
        strength(entries, blending, "regional", config.terrainBiomeBlendStrength(), 45,
                value -> config.terrain_biome_blend_strength = value);
        strength(entries, blending, "detail", config.surfaceDetailStrength(), 100,
                value -> config.surface_detail_strength = value);
        var performance = builder.getOrCreateCategory(text("performance"));
        performance.addEntry(entries.startIntSlider(text("map_size"), config.surfaceMapSize(), 64, 256)
                .setDefaultValue(256).setTooltip(text("map_size.tooltip"))
                .setSaveConsumer(value -> config.surface_map_size = value).build());
        builder.setSavingRunnable(() -> {
            try {
                config.save(BlendingConfig.configDirectory);
                BlendingConfig.INSTANCE = config;
                TerrainShader.clear();
            } catch (IOException exception) {
                BlendingConfig.LOGGER.warn("Could not save Better Blending settings", exception);
                SystemToast.add(Minecraft.getInstance()./*? if >=26.1 {*/ /*gui.toastManager *//*?} else {*/ getToasts /*?}*/(), SystemToast./*? if <1.20.2 {*/ /*SystemToastIds *//*?} else {*/ SystemToastId /*?}*/.PERIODIC_NOTIFICATION,
                        text("save_failed"), text("save_failed.tooltip"));
            }
        });
        return builder.build();
    }

    private static void strength(ConfigEntryBuilder entries, ConfigCategory category, String key,
                                 float value, int defaultValue, Consumer<Float> save) {
        category.addEntry(entries.startIntSlider(text(key), Math.round(value * 100), 0, 100)
                .setDefaultValue(defaultValue).setTooltip(text(key + ".tooltip"))
                .setTextGetter(percent -> Component.literal(percent + "%"))
                .setSaveConsumer(percent -> save.accept(percent / 100.0F)).build());
    }

    private static Component text(String key) {
        return Component.translatable("better_blending.config." + key);
    }
}
