# MineSplat MVP architecture

`MineSplatController` is the singleton session owner. Screens subscribe to an
immutable snapshot, so closing a screen cannot cancel network or conversion
work. Java `HttpClient`, a scheduled polling executor, and a dedicated
conversion executor keep work off the render thread.

```text
image upload
  -> generation job (32,768 Gaussians)
  -> keep PLY for the session
  -> voxelization job (32 / 64 / 128 / 256 / 512 / 1024)
  -> strict TSVOX v2 validation
  -> sparse OKLab block matching
  -> output backend:
       -> create/save/place .litematic, or
       -> save sparse .msbp Chisels & Bits blueprint
```

Only Litematica holder and placement operations, block-state resolution, and
schematic container creation run on the Minecraft client thread. Sparse parsing,
color matching, blueprint I/O, C&B grouping, and sparse preview meshing run in
the background. No Java object is allocated for every cell in the cubic grid.
TSVOX occupancy is decoded by scanning set bits instead of iterating over every
one of the possible `1024³` cells.
Preview vertices are uploaded once to a static GPU buffer and re-uploaded only
after loading or rotation; moving it only changes the draw matrix. The anchor
uses a client-side block raycast up to 1024 blocks, bounded in practice by loaded
chunks.

The optional C&B bridge is loaded reflectively only after Fabric Loader confirms
mod id `chiselsandbits` at exact version 21.1.33. Core signatures contain no C&B
types. `.msbp` stores exact block-state strings, face colors, and packed sparse
voxel/palette indices rather than C&B internal NBT. Placement is limited to a
Creative integrated server, preflights every occupied host position, then uses
the public C&B mutator API in bounded server-tick batches.

Artifacts are deleted best-effort according to the API contract. The generation
PLY remains on the server while the current client session can reuse it, then is
deleted on explicit session finish, a new generation, or client shutdown.
