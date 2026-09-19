# Changelog

## 0.3.1

- Single placed blocks and pairs keep their own texture. They used to be mostly painted over by whatever surrounded them, top face only. They still lend a narrow edge to the blocks right beside them.
- Ground one block below or above a surface borrows half as much of it, so a block sitting on the ground no longer smears its top across the floor around it.
- 26.2 and 26.3: grass block sides and borrowed textures near a blend were blurry. They are now sampled the way vanilla samples terrain, so their pixels stay sharp. This also removes faint dark seams along those block edges.
- Wider regional transitions no longer fill whole blocks with the neighboring texture, and a lone block no longer scatters patches of itself several blocks away.

## 0.3.0

- Texture-aligned blending is now on by default. Transitions and added detail snap to the receiving block's texture pixels, so blended edges no longer mix pixel sizes. Config files that already list `texture_aligned_blending` keep their setting; turn it off under **Align blending to texture pixels** for the original style.
- Added Minecraft 26.3 on NeoForge, with Sodium support.
- Sodium, Embeddium and Rubidium on 1.20.1 and 1.21.1: Better Blending now only rewrites the renderer's own opaque terrain shader. Shaders from addons, or a terrain shader in a shape it doesn't recognize, are left untouched and logged once, instead of risking a shader that fails to compile.