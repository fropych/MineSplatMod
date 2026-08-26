# MineSplat MVP architecture

`MineSplatController` is the singleton session owner. Screens subscribe to an
immutable snapshot, so closing a screen cannot cancel network or conversion
work. Java `HttpClient`, a scheduled polling executor, and a dedicated
conversion executor keep work off the render thread.

`MineSplatScreen` presents that snapshot as a four-page wizard: Source, Blocks,
Creating, and Result. The wizard collects all settings before calling the
existing automatic controller pipeline; reopening it selects Creating or
Result from the current snapshot. Inference configuration and local model
management live in a separate settings screen.

`InferenceTarget` makes the backend explicit. A remote target creates the
normal API client immediately. A local target asks `LocalRuntimeManager` to
verify/extract the bundled platform runtime, start `triposplat-vulkan serve` on
a randomly allocated `127.0.0.1` port, and wait for API v1 health. From that
point onward the controller uses exactly the same REST calls for both modes;
there is no in-process native interface and no automatic remote/local fallback.

```text
InferenceTarget
  -> Remote: user HTTP/HTTPS base URL -------------------+
  -> Local: bundled sidecar -> loopback REST API v1 ----+-> MineSplatController
```

`LocalModelManager` exposes independent CORE and TEXT snapshots. CORE contains
the five image-to-3D weights and is sufficient to start the local server. After
download, the bundled executable's `download` command atomically normalizes
three CORE files to their pinned F16/I32 hashes. TEXT contains the optional
Z-Image diffusion, Qwen encoder, and VAE weights and is downloaded only through
its explicit UI action. Both sets verify size and SHA-256 off the render thread,
resume `.part` files with HTTP Range, and remain outside the JAR.
The local process is stopped during client shutdown and when the selected mode,
model directory, or Vulkan device changes.

```text
image upload -> generation job -------------------------+
prompt -> text-generation job -> discard generated PNG -+
  -> 32,768 Gaussians
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
mod id `chiselsandbits` at the exact release declared by the target metadata:
`20.1.20` for Minecraft 1.20.1, `21.1.33` for 1.21.1, and `21.11.45` for
1.21.11. Core signatures contain no C&B types. `.msbp` stores exact block-state
strings, face colors, and packed sparse voxel/palette indices rather than C&B
internal NBT. Placement is limited to a Creative integrated server, preflights
every occupied host position, then uses the public C&B mutator API in bounded
server-tick batches.

Artifacts are deleted best-effort according to the API contract. The generation
PLY remains on the server while the current client session can reuse it, then is
deleted on explicit session finish, a new generation, or client shutdown.

Each Minecraft-specific JAR is universal only along the native-runtime axis: it
carries the independently hashed official Windows and Linux x86-64 assets from
TripoSplatVulkan release `v0.2.1`, source commit
`01831b39aa0512413dbb2a167635648d38c788ca`. The upstream project remains an
unmodified Git submodule pinned to the same commit. Unsupported OS/CPU pairs
expose only Remote mode; local inference is Vulkan-only and has no CPU backend.

## Version and release architecture

MineSplat builds one Minecraft target per Git line. `main` targets Minecraft
1.21.11; `1.21.1/stable` and `1.20.1/stable` are maintenance lines. Shared
changes are backported as reviewed, atomic commits instead of merging the
feature line wholesale into an older API surface.

The checked-out line's `gradle.properties` is the source of truth for Minecraft,
Java, Yarn, Fabric Loader/API, Litematica, MaLiLib, Mod Menu, C&B, and MineSplat
versions. Resource processing expands that data into `fabric.mod.json` and
`minesplat-target.properties`. The matching versioned palette is selected from
the same target metadata. Custom palettes remain block-ID based and therefore
portable; IDs absent from the current registry are retained on disk but omitted
from the effective palette.

Every line emits exactly one remapped JAR and one matching version-specific
Prism pack:

```text
minesplat-fabric-<minecraft>-<mod-version>.jar
minesplat-prism-<minecraft>-<mod-version>.mrpack
```

CI runs a clean build, target/resource validation, palette checks, tests, and
pack validation for all supported branches. Tags use
`mc<minecraft>-<mod-version>`. The release workflow rejects a tag whose target,
mod version, or branch ancestry does not match, stages only the main JAR and
matching `.mrpack`, generates `SHA256SUMS`, and publishes them through a draft
GitHub Release. A dispatch run is validation-only. Only the 1.21.11 release is
marked as the latest line.
