# Changelog

## 0.3.0

- Texture-aligned blending is now on by default. Transitions and added detail snap to the receiving block's texture pixels, so blended edges no longer mix pixel sizes. Config files that already list `texture_aligned_blending` keep their setting; turn it off under **Align blending to texture pixels** for the original style.
- Added Minecraft 26.3 on NeoForge, with Sodium support.
- Sodium, Embeddium and Rubidium on 1.20.1 and 1.21.1: Better Blending now only rewrites the renderer's own opaque terrain shader. Shaders from addons, or a terrain shader in a shape it doesn't recognize, are left untouched and logged once, instead of risking a shader that fails to compile.