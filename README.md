# MineSplat

MineSplat is a client-side Fabric mod for Minecraft 1.21.1. It turns an image
or text prompt into a fixed 32,768-Gaussian model, voxelizes it, maps TSVOX v2 colors to safe
vanilla blocks in OKLab, and exports either a `.litematic` or an optional
Chisels & Bits miniature.

Inference can use either a user-supplied remote TripoSplat API v1 or the
TripoSplatVulkan runtime bundled in the mod. Local mode starts that runtime as a
loopback REST sidecar, so both modes use the same API client and generation
workflow. Local inference is available on Windows and Linux x86-64, requires a
Vulkan 1.2-capable GPU with a current driver, and has no CPU fallback. Other
platforms retain remote mode.

## Locked runtime

- Minecraft 1.21.1
- Fabric Loader 0.16.7
- Fabric API 0.107.0+1.21.1
- Litematica 0.19.50
- MaLiLib 0.21.0
- Mod Menu 11.0.3 (optional for the JAR, included in the Prism pack)
- Chisels & Bits 21.1.33 (optional, installed separately)
- Java 21

## Install and use

See the bilingual [user guide](docs/USER_GUIDE.md). The short version is:
import the generated `.mrpack` into Prism Launcher, start a world, press
`M + G`, and use Settings in the wizard header to choose Local or Remote
inference. Local mode downloads and verifies
the pinned base model snapshot on first use. The separate Z-Image prompt models
are optional and download only when explicitly requested; no model weights are
embedded in the JAR.

## Build

```bash
./gradlew test
./gradlew build
```

Build outputs:

- `build/libs/minesplat-fabric-1.21.1-0.4.0.jar`
- `build/distributions/minesplat-prism-1.21.1-0.4.0.mrpack`

Run a development client with `./gradlew runClient`. Gradle 8.9 and Java 21 are
required; the included wrapper pins Gradle. Use `./gradlew runClientCnb` for a
development run that copies the optional C&B distribution into `run-cnb/mods`.

The universal JAR contains the official Linux and Windows x86-64 runtime assets
from TripoSplatVulkan release `v0.2.0`, source commit
`4bb05dec707f1f34f47aac2d679c1bd7021eb779`. The submodule lives at
`third_party/TripoSplatVulkan`; initialize it with
`git submodule update --init --recursive`. Native compilation is not part of
the MineSplat build. `validateTripoSplatRuntime` verifies every bundled runtime
file against `runtime-manifest.json` during `check`.

The development-only `tools/PaletteAverage.java` calculates numeric face colors
from legally installed vanilla textures. Only numeric averages in
`palette-1.21.1.json` are shipped; Minecraft textures are not redistributed.

## API contract

Generation always sends `num_gaussians=32768`, `guidance=3.0`, and
`erode_radius=1`. Settings provide Base (512px, 10 TripoSplat steps), High
(512px, 20 steps), and XHigh (1024px, 20 steps); prompt generation uses eight
Z-Image steps in every mode. The voxel presets change only resolution
(32, 64, 128, 256, 512, and 1024). This matches the TripoSplat
server maximum. The 1024 preset is exceptionally heavy and benefits from at
least 12–16 GiB allocated to the Prism instance.

The client validates `/health` as `triposplat-vulkan` API `v1`, uses asynchronous
HTTP and polling, validates TSVOX v2 before touching Litematica, and never
overwrites an existing schematic or MineSplat blueprint.

Local models are downloaded directly by the mod with resumable HTTP transfers,
size checks, and SHA-256 verification. The base snapshot is pinned to revision
`de3b99ab2627d565a8d5fc40f2db52557b82b974`; three files are normalized by the
bundled `v0.2.0` converter after download. The optional prompt set contains the
Z-Image diffusion model, Qwen text encoder, and VAE (6,696,835,812 bytes).
The default directory is
`minecraft/minesplat/models/<revision>/`; a custom directory can be selected in
the MineSplat screen. Switching modes never silently falls back to the other
backend.

## Optional Chisels & Bits output

MineSplat detects exactly Chisels & Bits 21.1.33 at runtime. Without it, the
Litematica workflow remains fully functional and no C&B classes are linked.
With it installed, MineSplat saves sparse `.msbp` blueprints under
`minecraft/minesplat/blueprints/`. In a Creative singleplayer world these can be
positioned with a colored hologram, rotated with `R` / `Shift+R`, moved with the
arrow and Page Up/Down keys, and confirmed with right-click.

One TSVOX voxel maps to one C&B bit. World mutation runs on the integrated
server thread in bounded batches and rolls back MineSplat-created host blocks
if placement fails.

## License

MineSplat is MIT licensed. Bundled TripoSplatVulkan and dependency licenses and
notices are included under `assets/minesplat/triposplat/licenses/` in the JAR.
