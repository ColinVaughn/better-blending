# Changelog

## 0.3.3

- Trees no longer blend with the terrain around them. Leaves and bark were mixing into each other, and into the ground under a low canopy. A block that does not occlude, which in practice means leaves, is no longer a material boundary for one that does, in either direction. Two blocks of the same kind still blend, so one species of leaf meets another as before.
- Leaves buried inside a canopy no longer blend. Blocks that do not occlude never cull each other, so a leaf cluster exposed every face it had and the whole of it was prepared and blended. Only the shell of such a cluster, the blocks that touch air, takes part now. Thick canopies cost far less to prepare; ordinary oak and birch canopies are thin enough that most of their leaves are shell either way.
- Added **Blend leaves** under Performance, and `blend_leaves` in the config file. It is on by default. Turning it off hands leaves to vanilla entirely, which prepares roughly half as many blocks per section in a forest.

## 0.3.2

- Added a blending-style setting. The default now protects only isolated single blocks, so two or more adjacent blocks blend normally; the original full-blend behavior and 0.3.1 single-block-and-pair protection remain selectable.

## 0.3.1

- Single placed blocks and pairs keep their own texture. They used to be mostly painted over by whatever surrounded them, top face only. They still lend a narrow edge to the blocks right beside them.
- Ground one block below or above a surface borrows half as much of it, so a block sitting on the ground no longer smears its top across the floor around it.
- 26.2 and 26.3: grass block sides and borrowed textures near a blend were blurry. They are now sampled the way vanilla samples terrain, so their pixels stay sharp. This also removes faint dark seams along those block edges.
- Wider regional transitions no longer fill whole blocks with the neighboring texture, and a lone block no longer scatters patches of itself several blocks away.

## 0.3.0

- Texture-aligned blending is now on by default. Transitions and added detail snap to the receiving block's texture pixels, so blended edges no longer mix pixel sizes. Config files that already list `texture_aligned_blending` keep their setting; turn it off under **Align blending to texture pixels** for the original style.
- Added Minecraft 26.3 on NeoForge, with Sodium support.
- Sodium, Embeddium and Rubidium on 1.20.1 and 1.21.1: Better Blending now only rewrites the renderer's own opaque terrain shader. Shaders from addons, or a terrain shader in a shape it doesn't recognize, are left untouched and logged once, instead of risking a shader that fails to compile.
