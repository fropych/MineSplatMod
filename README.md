# MineSplat

MineSplat is a client-side Fabric mod for Minecraft 1.20.1, 1.21.1, and
1.21.11. It turns an image or text prompt into a fixed 32,768-Gaussian model,
voxelizes it, maps TSVOX v2 colors to safe vanilla blocks in OKLab, and exports
either a `.litematic` or an optional Chisels & Bits miniature.

Inference can use either a user-supplied remote TripoSplat API v1 or the
TripoSplatVulkan runtime bundled in the mod. Local mode starts that runtime as a
loopback REST sidecar, so both modes use the same API client and generation
workflow. Local inference is available on Windows and Linux x86-64, requires a
Vulkan 1.2-capable GPU with a current driver, and has no CPU fallback. Other
platforms retain remote mode.

## Supported targets

Each Minecraft line has its own JAR, Prism `.mrpack`, dependency lock, and
vanilla block palette. Do not mix files between target versions.

| Minecraft | Java | Yarn | Fabric API | Litematica | MaLiLib | Mod Menu | Chisels & Bits |
| --- | ---: | --- | --- | --- | --- | --- | --- |
| 1.20.1 | 17 | `1.20.1+build.10` | `0.92.11+1.20.1` | `0.15.4` | `0.16.3` | `7.2.2` | `20.1.20` |
| 1.21.1 | 21 | `1.21.1+build.3` | `0.116.15+1.21.1` | `0.19.61` | `0.21.10` | `11.0.3` | `21.1.33` |
| 1.21.11 | 21 | `1.21.11+build.6` | `0.141.6+1.21.11` | `0.26.13` | `0.27.18` | `17.0.0` | `21.11.45` |

Fabric Loader is locked to `0.19.3` on every line. Mod Menu is optional in the
standalone JAR and included in each Prism pack. Chisels & Bits is optional and
must be installed separately at the exact version shown for the selected
Minecraft target.

`main` is the current 1.21.11 feature line. `1.21.1/stable` and
`1.20.1/stable` receive compatible fixes through reviewed backport commits;
version-specific changes remain on their own line.

## Install and use

See the bilingual [user guide](docs/USER_GUIDE.md). The short version is:
download the `.mrpack` matching your Minecraft version from
[GitHub Releases](https://github.com/fropych/MineSplatMod/releases), import it
into Prism Launcher, start a world, press `M + G`, and use Settings in the
wizard header to choose Local or Remote inference. Local mode downloads and verifies
the pinned base model snapshot on first use. The separate Z-Image prompt models
are optional and download only when explicitly requested; no model weights are
embedded in the JAR.

## Build

```bash
./gradlew test
./gradlew build
```

Build outputs:

- `build/libs/minesplat-fabric-<minecraft>-<mod-version>.jar`
- `build/distributions/minesplat-prism-<minecraft>-<mod-version>.mrpack`

The target is read from the checked-out line's `gradle.properties`; a checkout
builds exactly one Minecraft version. Run a development client with
`./gradlew runClient`. Gradle runs on Java 21. The 1.21.11 line pins Gradle
9.4.0, while the stable 1.21.1 and 1.20.1 lines pin Gradle 9.2.1; the 1.20.1
line produces Java 17 bytecode. Use
`./gradlew runClientCnb` for a development run that copies the target's exact
optional C&B distribution into `run-cnb/mods`.

Every Minecraft-specific JAR is universal with respect to local inference: it
contains the official Linux and Windows x86-64 runtime assets from
TripoSplatVulkan release `v0.2.1`, source commit
`01831b39aa0512413dbb2a167635648d38c788ca`. The submodule lives at
`third_party/TripoSplatVulkan`; initialize it with
`git submodule update --init --recursive`. Native compilation is not part of
the MineSplat build. `validateTripoSplatRuntime` verifies every bundled runtime
file against `runtime-manifest.json` during `check`.

The development-only `tools/VanillaPaletteGenerator.java` calculates numeric
per-face colors directly from a legally installed Minecraft client JAR. It
keeps only opaque, non-falling full-cube states and never copies Minecraft
textures into the project output. Block categories are maintained separately
from version-specific colors; the reproducible TXT-to-JSON workflow is
documented in `tools/palette/README.md`.

Built-in `All`, `Survival`, and `Solid Colors` palettes are read-only. Custom
palettes can be created and edited in the block-settings screen. They are saved
as version-independent block-ID differences under
`config/minesplat/palettes/*.json`; the global block blacklist is applied on
top of the selected palette.

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
bundled `v0.2.1` converter after download. The optional prompt set contains the
Z-Image diffusion model, Qwen text encoder, and VAE (6,696,835,812 bytes).
The default directory is
`minecraft/minesplat/models/<revision>/`; a custom directory can be selected in
the MineSplat screen. Switching modes never silently falls back to the other
backend.

## Optional Chisels & Bits output

MineSplat detects only the exact C&B release selected for its Minecraft target:
`20.1.20` on 1.20.1, `21.1.33` on 1.21.1, and `21.11.45` on 1.21.11. Without
it, the Litematica workflow remains fully functional and no C&B classes are
linked.

With it installed, MineSplat saves sparse `.msbp` blueprints under
`minecraft/minesplat/blueprints/`. In a Creative singleplayer world these can be
positioned with a colored hologram, rotated with `R` / `Shift+R`, moved with the
arrow and Page Up/Down keys, and confirmed with right-click.

One TSVOX voxel maps to one C&B bit. World mutation runs on the integrated
server thread in bounded batches and rolls back MineSplat-created host blocks
if placement fails. To avoid coarse C&B lighting shadows, placement also adds
one invisible level-15 light block near the center of every occupied 5×5×5
host-block section. Existing world blocks are never replaced.

## Releases

GitHub Actions builds and verifies pushes and pull requests for `main`,
`1.21.1/stable`, and `1.20.1/stable`. A release is created only by pushing an
annotated tag with this exact shape:

```text
mc<minecraft>-<mod-version>
```

For example, MineSplat 0.5.0 is released as `mc1.20.1-0.5.0`,
`mc1.21.1-0.5.0`, and `mc1.21.11-0.5.0`. The tag, branch, and
`gradle.properties` target must agree. The workflow rebuilds from the tagged
commit, publishes the matching JAR and `.mrpack` plus `SHA256SUMS`, and never
publishes `-sources.jar`. A manual `workflow_dispatch` validates the same path
without creating a release. Published tags are immutable; corrections use a
new mod version.

Maintainer release procedure:

1. Set `mod_version` on the target branch, push it, and wait for CI to pass.
2. Run the `Release` workflow manually from that same branch with the intended
   tag as `release_tag`; this is a build-only dry run.
3. Create the annotated tag at the tested commit and push only that tag:

   ```bash
   git tag -a mc1.21.11-0.5.0 -m "MineSplat 0.5.0 for Minecraft 1.21.11"
   git push origin mc1.21.11-0.5.0
   ```

4. Wait for the tag workflow and verify the three release assets against
   `SHA256SUMS`. Repeat from each stable branch for its own target tag.

## License

MineSplat is MIT licensed. Bundled TripoSplatVulkan and dependency licenses and
notices are included under `assets/minesplat/triposplat/licenses/` in the JAR.
