# Better Blending

A client-side mod that smooths the transitions between terrain materials, so sand meeting grass reads as a gradual boundary instead of a hard block edge.

| Minecraft | Loaders | Java |
|---|---|---|
| 1.20.1 | Fabric, Forge | 17 |
| 1.21.1 | Fabric, NeoForge | 21 |
| 26.2 | Fabric, NeoForge | 25 |
| 26.3 | Fabric | 25 |

26.3 on NeoForge will follow once NeoForge 26.3 leaves beta and Iris and Cloth Config publish NeoForge builds for it.

Blending also works with Sodium and Iris on every version, with Embeddium on 1.20.1 and 1.21.1, and with Rubidium and Oculus on 1.20.1. See [Renderer compatibility](#renderer-compatibility).

Materials are discovered automatically from baked models, so there is no block or texture-name allowlist to keep up to date. That covers biome tints, one-block transitions, wider regional transitions, surface detail and cutout faces, with visible-surface updates kept bounded. Blending is on by default in every dimension, the End and modded dimensions included, and they all share one configurable effect with independent samples for floors, walls and ceilings at any height.

## Build and run

The build is a [Stonecutter](https://stonecutter.kikugie.dev/) matrix. Each target is a *node* named `<minecraft>-<loader>`, and one source tree serves all of them. Node names contain a dot, so quote them in PowerShell.

```powershell
.\gradlew.bat buildAll                      # every node
.\gradlew.bat "1.21.1-fabric:build"         # one node
.\gradlew.bat "1.21.1-fabric:runClient"
# Or: .\gradlew.bat "1.21.1-neoforge:runClient"
```

Install the jar for your setup from `versions/<node>/build/libs/`, for example `better-blending-1.21.1-fabric-0.2.0.jar` or `better-blending-1.21.1-neoforge-0.2.0.jar`. Fabric also needs Fabric API. The mod is client-only; servers do not need it.

One thing to watch if you are working on the source: Stonecutter keeps the working tree in the shape of one *active* node and rewrites `src/` in place when you switch, so switch back to `1.21.1-fabric` before committing.

## Configuration

For an in-game settings screen, install the optional config mods for your Minecraft version:

- **Fabric:** Mod Menu and Cloth Config (1.20.1: Mod Menu 7, Cloth 11; 1.21.1: Mod Menu 11, Cloth 15; 26.2: Mod Menu 20, Cloth 26.2; 26.3: Mod Menu 21 beta, Cloth 26.3). Open **Mods → Better Blending → Configure**.
- **NeoForge and Forge:** Cloth Config for that loader. Open **Mods → Better Blending → Config**.

Saving applies your changes immediately and rebuilds the material cache; Cancel discards them. Every entry carries a default and a tooltip. These screens are built with [Cloth Config's screen builder](https://shedaniel.gitbook.io/cloth-config/using-cloth-config/creating-a-config-screen) and [NeoForge's config-screen extension](https://docs.neoforged.net/docs/1.21.1/misc/config/#configuration-screen).

Without those optional mods, edit `config/better-blending.json`, created on first launch, and restart the client:

```json
{
  "enabled": true,
  "vanilla_terrain_shader_enabled": true,
  "disabled_dimensions": [],
  "excluded_blocks": ["minecraft:melon", "minecraft:pumpkin", "minecraft:carved_pumpkin", "minecraft:jack_o_lantern", "minecraft:hay_block"],
  "terrain_biome_blend_strength": 0.45,
  "surface_shader_strength": 1.0,
  "local_blend_strength": 1.0,
  "texture_aligned_blending": false,
  "surface_detail_strength": 1.0,
  "surface_map_size": 256,
  "surface_refresh_ticks": 20,
  "sampling_budget_ms": 1.0
}
```

Strengths clamp to 0-1. Local and regional strengths control nearby and wider transitions independently, and transitions follow every exposed face, entire walls and undersides included. Grass sides and other compatible two-layer faces blend their base and tinted overlay together, as both receivers and donors; more complex layers and mismatched UVs keep their original rendering. Surface detail controls the added texture variation and lighting, and an overall surface strength of zero bypasses blending and map work entirely.

`enabled` is the master switch. To exclude specific dimensions, add their IDs, such as `minecraft:the_end` or `modid:dimension`, to `disabled_dimensions`.

`texture_aligned_blending` keeps transitions and added detail aligned to the receiving block's texture pixels, which cuts down on mixed pixel sizes ("mixels"). It follows the face UVs and the current resource pack resolution on all six cube faces. Off by default; normal distance filtering stays active either way.

`surface_map_size` bounds the camera-centered GPU cache to 64-256 blocks per side, rounded down to whole sections. Smaller regions mean less coverage and less memory.

`excluded_blocks` stops the listed blocks from receiving blending or lending textures to their neighbors. Melons, pumpkins (carved ones and jack o'lanterns included) and hay bales are excluded by default, even with older config files. Edit **Excluded blocks** in the config screen or add IDs like `modid:block` to the JSON list. Every state of a listed block is covered. Remove an entry to allow that block again, or use `[]` to turn exclusions off. Unknown mod IDs are kept, so one list can be shared across modpacks.

Upgrading from an older version: the overall strength setting is now `surface_shader_strength` (default 1.0), so set it again if you had customized the old one. The legacy vanilla toggle still affects only the Overworld and the Nether. `surface_refresh_ticks` and `sampling_budget_ms` are still accepted for compatibility but no longer schedule per-frame sampling, and they have been removed from the settings screen. Anything missing picks up the new defaults.

## Tests

```powershell
.\gradlew.bat "1.21.1-fabric:test"
$env:BB_SHADER_GL_TEST='1'
.\gradlew.bat "1.21.1-fabric:test" --rerun-tasks
# Optional GPU benchmarks: also set BB_SHADER_BENCHMARK=1.
```

Two development checks cover what compiling cannot.

`-PauditMixins` applies every mixin at startup and exits, failing if an injection no longer matches its target. It needs no display on Fabric.

`-PsmokeTest` (26.x nodes) compiles the blending pipelines through the real GPU device, exercises every texture and uniform-buffer call, then exits. It uses whichever graphics backend the game picks; 26.3 also has a Vulkan backend, so add `--args="--graphicsBackend vulkan"` to test that one.

Add `-Prenderer=<name>` or `-Piris` to either check to bring in the renderer adapters. The audit then applies those too, and the smoke test additionally compiles the blended copies of Sodium's pipelines and links a minimal shader-pack program after both Iris's own transform and Better Blending's patch. On 1.20.1, `-PsmokeTest -Piris` runs that shader-pack check against Iris on Fabric and Oculus on Forge; the renderer shaders themselves are covered by the GPU tests.

```powershell
.\gradlew.bat "26.2-neoforge:runClient" -PauditMixins
.\gradlew.bat "26.2-fabric:runClient" -PsmokeTest
.\gradlew.bat "26.2-fabric:runClient" -PsmokeTest -Piris
.\gradlew.bat "26.3-fabric:runClient" -PsmokeTest -Prenderer=sodium --args="--graphicsBackend vulkan"
.\gradlew.bat "1.20.1-forge:runClient" -PsmokeTest -Piris
```

CI (`.github/workflows/build.yml`) builds every node, runs the shader tests on software OpenGL, and runs the mixin audit on every node, both without renderers and with Sodium and Iris installed, plus Embeddium on 1.21.1 NeoForge and Rubidium on 1.20.1 Forge.

The regression suite covers baked faces, grass and cutout layers, stacked cave floors, all six face directions, first-frame readiness, retained snapshots, culled interiors, real GLSL rendering, local and regional transitions, tint, camera stability, high material IDs and protected interiors. GPU checks need an OpenGL context and write review images to `versions/1.21.1-fabric/build/shader-review/`. Fixtures render with vanilla Minecraft textures from the development game assets.

### Performance profiling

```powershell
$env:BB_SHADER_GL_TEST='1'
$env:BB_TERRAIN_PROFILE='1'
.\gradlew.bat "1.21.1-fabric:test" --tests '*actualSurfaceBoundaryKeepsCrispTexturesAndDoesNotRepaintInteriors'
Remove-Item Env:BB_TERRAIN_PROFILE
```

For a paired shader comparison, save the pre-edit `terrain.fsh` as `versions/1.21.1-fabric/build/shader-review/terrain-before-performance.fsh` first; without it the current shader is the reference. The benchmark writes GPU timings to `benchmark.csv`, CPU publication timings to `cache-profile.csv`, and comparison PNGs in the same directory. The environment switches are Gradle test inputs, so changing modes reruns the tests.

Measured on Intel Graphics, driver 32.0.101.8826, on 2026-09-13:

| Fixture | Before | After |
| --- | ---: | ---: |
| Saturated cache, first publication (CPU) | 1,649 ms | 136 ms |
| Saturated cache, unchanged subsequent frames (CPU) | 3,080–4,863 ms | 1.61–2.96 ms |
| Near terrain, 1080p shader median (GPU) | 21.00 ms | 17.56 ms |
| Distant terrain, 1080p shader median (GPU) | 19.66 ms | 14.35 ms |

The cache fixture pushes 1,089 sections into a 1,024-page cache, donor halos included, so it runs deliberately over capacity. Subsequent frames now upload nothing at all, which the ordinary GPU regression suite asserts as well. CPU numbers come from separate runs and exclude upload time; GPU runs alternate the old and new programs in the same process using RGBA8 textures.

Regional blending is still the expensive part: a separate paired probe measured 7.52 ms with local blending against 17.05 ms with local plus regional. Absolute GPU timings moved around a lot between runs, so read the table as one run rather than a frame budget. The fixture also marks every terrain cell as a blend boundary, which is the worst case by design.

These are synthetic diagnostics, not in-game FPS. Initial publication still costs something, and none of these fixtures model a cave's full overdraw, chunk compilation or resource-pack complexity.

## Renderer compatibility

Install one renderer. The adapters use its chunk workers, visible-section lists, vertex decoding, lighting and fog while sharing Better Blending's own material cache and blending algorithm. No renderer is bundled or required.

**Minecraft 1.21.1** targets **Sodium 0.6.13** on Fabric and NeoForge, and **Embeddium 1.0.15** on NeoForge.

**Iris 1.8.8 (Fabric) / 1.8.12 (NeoForge):** blending runs with shader packs **on or off**. With a pack enabled, Better Blending applies its algorithm to terrain atlas samples before the pack's lighting, leaving its fog, shadows and output buffers intact. Iris allocates the extra samplers, and shadow passes never overwrite the visible-terrain cache. The pack keeps control of surface lighting and detail, and its normal and specular maps are untouched.

The integration handles direct `texture`, `textureLod` and `textureGrad` reads of the terrain atlas (`gtexture`/`tex`, including the legacy aliases Iris normalizes). Geometry and tessellation terrain stages are not supported yet, nor are packs that hide every atlas read behind generic sampler parameters; those passes log a warning. Treat that as a caveat rather than a promise about any particular pack. I test against a minimal pack and Complementary Reimagined r5.9.1, toggling both blending and the pack, and the GPU tests compare the Iris path against the original blending shader.

**Oculus has no official Minecraft 1.21.1 release**, and supporting it would need a separate Minecraft/Forge port. See the [Iris releases](https://modrinth.com/mod/iris/versions) and [Oculus releases](https://modrinth.com/mod/oculus/versions).

**Minecraft 26.2** targets **Sodium 0.9.2** and **Iris 1.11.4** on Fabric and NeoForge. Sodium 0.9 draws terrain through render pipelines, so Better Blending compiles a copy of each opaque Sodium pipeline with blending patched into Sodium's own shader and draws with that copy while no shader pack is active. With a pack enabled, the pack's terrain program is patched as on 1.21.1, with the same limits. No Embeddium release exists for 26.2. I've looked at a 26.2 world with the vanilla renderer, but not yet with Sodium or under a shader pack.

**Minecraft 26.3** targets **Sodium 0.9.2** and **Iris 1.11.6** on Fabric and works as on 26.2. 26.3 moved the GPU layer into a new API with both OpenGL and Vulkan backends and compiles every shader to SPIR-V, even for OpenGL; both backends are supported. I've looked at a 26.3 world with the vanilla renderer and with Sodium, on both OpenGL and Vulkan. Under a shader pack, not yet.

**Minecraft 1.20.1** targets **Sodium 0.5.13** and **Iris 1.7.6** on Fabric, and **Embeddium 0.3.31** or **Rubidium 0.7.1** with **Oculus 1.8.0** on Forge. Oculus requires Embeddium. All three descend from Sodium 0.5 and share one adapter, and shader packs are handled as on 1.21.1 with the same limits. Embeddium logs that the game is "tainted" whenever another mod hooks its internals, which Better Blending does and so does Oculus; that is Embeddium disclaiming support, not an error. I haven't looked at a 1.20.1 world with these renderers yet.

Development launches (`-Piris` also installs the renderer Iris runs on: Sodium, or Embeddium with Oculus on Forge 1.20.1):

```powershell
.\gradlew.bat "1.21.1-fabric:runClient" -Prenderer=sodium
.\gradlew.bat "1.21.1-neoforge:runClient" -Prenderer=embeddium
.\gradlew.bat "1.21.1-fabric:runClient" -Piris
.\gradlew.bat "26.2-neoforge:runClient" -Piris
.\gradlew.bat "1.20.1-forge:runClient" -Prenderer=rubidium
.\gradlew.bat "1.20.1-forge:runClient" -Piris
```

The GPU tests also compile the real upstream renderer shader stages for the solid and cutout passes, from every Sodium-family build above that uses OpenGL shaders directly (Sodium 0.5 and 0.6, Embeddium 0.3 and 1.0, Rubidium 0.7), and compare the adapted fragments against vanilla-path blending. None of that replaces live testing with resource packs.

## Current limits

Full solid and cutout cube terrain is supported; fluids, translucent blocks and custom geometry fall back to ordinary rendering. The cache covers a moving region up to 256 blocks on each axis, with 4,095 materials. Preparation is conservative at section granularity: hidden interior faces are skipped, while exposed faces and blend donors are prepared ahead of where the camera turns next. The GPU cache has a fixed capacity, and sections outside it render normally.

MIT licensed.
