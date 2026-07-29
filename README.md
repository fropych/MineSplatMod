# MineSplat

MineSplat is a client-side Fabric mod for Minecraft 1.21.1. It uploads an image
to a user-run TripoSplat API v1, requests a fixed 32,768-Gaussian model,
voxelizes it, maps TSVOX v2 colors to safe vanilla blocks in OKLab, saves a
`.litematic`, and places it in Litematica.

The server is not bundled or started by the mod. MineSplat accepts any HTTP or
HTTPS base URL supplied by the player.

## Locked runtime

- Minecraft 1.21.1
- Fabric Loader 0.16.2
- Fabric API 0.107.0+1.21.1
- Litematica 0.19.50
- MaLiLib 0.21.0
- Mod Menu 11.0.3 (optional for the JAR, included in the Prism pack)
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

- `build/libs/minesplat-fabric-1.21.1-0.1.0.jar`
- `build/distributions/minesplat-prism-1.21.1-0.1.0.mrpack`

Run a development client with `./gradlew runClient`. Gradle 8.9 and Java 21 are
required; the included wrapper pins Gradle.

The development-only `tools/PaletteAverage.java` calculates numeric face colors
from legally installed vanilla textures. Only numeric averages in
`palette-1.21.1.json` are shipped; Minecraft textures are not redistributed.

## API contract

Generation always sends `num_gaussians=32768`, `steps=20`, `guidance=3.0`, and
`erode_radius=1`. Only the seed is user-configurable. Preview, standard, and
detailed presets change only voxel resolution (32, 64, and 128).

The client validates `/health` as `triposplat-vulkan` API `v1`, uses asynchronous
HTTP and polling, validates TSVOX v2 before touching Litematica, and never
overwrites an existing schematic.

## License

MIT
