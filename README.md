# MineSplat

MineSplat is a client-side Fabric mod for Minecraft 1.21.1. It uploads an image
to a user-run TripoSplat API v1, requests a fixed 32,768-Gaussian model,
voxelizes it, maps TSVOX v2 colors to safe vanilla blocks in OKLab, and exports
either a `.litematic` or an optional Chisels & Bits miniature.

The server is not bundled or started by the mod. MineSplat accepts any HTTP or
HTTPS base URL supplied by the player.

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
import the generated `.mrpack` into Prism Launcher, start a world and your API,
press `M + G`, choose an image, and create the schematic.

## Build

```bash
./gradlew test
./gradlew build
```

Build outputs:

- `build/libs/minesplat-fabric-1.21.1-0.2.0.jar`
- `build/distributions/minesplat-prism-1.21.1-0.2.0.mrpack`

Run a development client with `./gradlew runClient`. Gradle 8.9 and Java 21 are
required; the included wrapper pins Gradle. Use `./gradlew runClientCnb` for a
development run that copies the optional C&B distribution into `run-cnb/mods`.

The development-only `tools/PaletteAverage.java` calculates numeric face colors
from legally installed vanilla textures. Only numeric averages in
`palette-1.21.1.json` are shipped; Minecraft textures are not redistributed.

## API contract

Generation always sends `num_gaussians=32768`, `steps=20`, `guidance=3.0`, and
`erode_radius=1`. Only the seed is user-configurable. The voxel presets change
only resolution (32, 64, 128, 256, 512, and 1024). This matches the TripoSplat
server maximum. The 1024 preset is exceptionally heavy and benefits from at
least 12–16 GiB allocated to the Prism instance.

The client validates `/health` as `triposplat-vulkan` API `v1`, uses asynchronous
HTTP and polling, validates TSVOX v2 before touching Litematica, and never
overwrites an existing schematic or MineSplat blueprint.

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

MIT
