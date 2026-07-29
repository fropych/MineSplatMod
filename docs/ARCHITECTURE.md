# MineSplat MVP architecture

`MineSplatController` is the singleton session owner. Screens subscribe to an
immutable snapshot, so closing a screen cannot cancel network or conversion
work. Java `HttpClient`, a scheduled polling executor, and a dedicated
conversion executor keep work off the render thread.

```text
image upload
  -> generation job (32,768 Gaussians)
  -> keep PLY for the session
  -> voxelization job (32 / 64 / 128)
  -> strict TSVOX v2 validation
  -> sparse OKLab block matching
  -> create and save .litematic
  -> load and select Litematica placement
```

Only Litematica holder and placement operations, block-state resolution, and
schematic container creation run on the Minecraft client thread. Sparse parsing
and color matching run in the background. No Java object is allocated for every
cell in the cubic grid.

Artifacts are deleted best-effort according to the API contract. The generation
PLY remains on the server while the current client session can reuse it, then is
deleted on explicit session finish, a new generation, or client shutdown.
