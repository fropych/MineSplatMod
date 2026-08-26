# Developing MineSplat

This document covers repository structure, local builds, version branches,
runtime and palette maintenance, CI, and releases. User installation and usage
belong in [README.md](README.md) and [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Contents

- [Requirements](#requirements)
- [Version branches](#version-branches)
- [Build and development runs](#build-and-development-runs)
- [Runtime and model data](#runtime-and-model-data)
- [API contract](#api-contract)
- [Palette maintenance](#palette-maintenance)
- [End-to-end validation](#end-to-end-validation)
- [Continuous integration](#continuous-integration)
- [Release procedure](#release-procedure)
- [Related documentation](#related-documentation)

## Requirements

Use the included Gradle wrapper and a JDK 21 installation to run Gradle. The
1.20.1 branch compiles Java 17 bytecode even though Gradle itself runs on JDK
21. No system Gradle installation is required.

The TripoSplatVulkan submodule is not required for a normal MineSplat build;
the checked-in runtime assets and manifests are sufficient. Initialize it only
when inspecting or updating the upstream source:

```bash
git submodule update --init --recursive
```

## Version branches

MineSplat builds one Minecraft target per branch.

| Branch | Minecraft | Java target | Yarn | Gradle | Fabric Loom |
| --- | --- | ---: | --- | --- | --- |
| `main` | 1.21.11 | 21 | `1.21.11+build.6` | 9.4.0 | 1.16.3 |
| `1.21.1/stable` | 1.21.1 | 21 | `1.21.1+build.3` | 9.2.1 | 1.14.10 |
| `1.20.1/stable` | 1.20.1 | 17 | `1.20.1+build.10` | 9.2.1 | 1.14.10 |

`main` is the current feature line. Compatible fixes are backported to the two
stable branches as reviewed atomic commits. Do not merge the entire feature
line into an older Minecraft API surface.

The checked-out branch's `gradle.properties` is the source of truth for the
Minecraft, Java, Yarn, loader, dependency, and MineSplat versions. The current
target locks are:

| Minecraft | Fabric API | Litematica | MaLiLib | Mod Menu | Chisels & Bits |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 | `0.92.11+1.20.1` | `0.15.4` | `0.16.3` | `7.2.2` | `20.1.20` |
| 1.21.1 | `0.116.15+1.21.1` | `0.19.61` | `0.21.10` | `11.0.3` | `21.1.33` |
| 1.21.11 | `0.141.6+1.21.11` | `0.26.13` | `0.27.18` | `17.0.0` | `21.11.45` |

Fabric Loader is pinned to `0.19.3` on all three branches. Resource processing
copies target metadata into `fabric.mod.json` and
`minesplat-target.properties`, and selects the matching versioned block
palette.

## Build and development runs

Run a full clean build before handing off a change:

```bash
./gradlew clean build
```

Useful focused tasks:

```bash
./gradlew test
./gradlew runClient
./gradlew runClientCnb
```

`runClientCnb` prepares a separate development run with the target's exact
optional Chisels & Bits distribution under `run-cnb/mods`.

Release-relevant outputs are:

```text
build/libs/minesplat-fabric-<minecraft>-<mod-version>.jar
build/distributions/minesplat-prism-<minecraft>-<mod-version>.mrpack
```

The build also creates a sources JAR for development. Never deploy or publish
that file as the mod artifact.

`build` runs tests and validates target metadata, the selected palette, bundled
TripoSplat runtime assets, model manifests, the generated `.mrpack`, and the
exact release artifact names. The `.mrpack` contains MineSplat, Fabric API,
Litematica, MaLiLib, and Mod Menu. Chisels & Bits remains optional and is not
included.

## Runtime and model data

Each Minecraft-specific MineSplat JAR contains the Windows and Linux x86-64
runtime files published with TripoSplatVulkan `v0.2.1`, source commit
`01831b39aa0512413dbb2a167635648d38c788ca`. Native compilation is not part of
the MineSplat Gradle build.

The upstream source is pinned at `third_party/TripoSplatVulkan`. Bundled files
are described by
`src/main/resources/assets/minesplat/triposplat/runtime-manifest.json`; Gradle
verifies every size and SHA-256 during `check`. Runtime licenses and notices
live beside those assets.

Model weights are not embedded in the JAR. The model manifest pins the base
snapshot to revision
`de3b99ab2627d565a8d5fc40f2db52557b82b974` and defines two independent sets:

- `CORE`: five image-to-3D files; three source files are converted to the
  runtime's pinned F16/I32 formats after download;
- `TEXT`: the optional Z-Image diffusion model, Qwen text encoder, and VAE.

`LocalModelManager` downloads up to three files concurrently, resumes valid
`.part` files with HTTP Range, hashes fresh data while writing it, and exposes
separate downloading, verifying, and converting states. Source and converted
files are validated by exact size and SHA-256. The local sidecar binds to a
random `127.0.0.1` port and is stopped when the client exits or its selected
mode, model directory, or Vulkan device changes.

## API contract

Both Local and Remote use the same TripoSplat REST API v1 client. Local starts
the bundled runtime as a loopback sidecar; Remote uses the configured base URL.
There is no in-process native API and no automatic fallback between backends.

The health response must identify service `triposplat-vulkan` and API version
`v1`. Generation requests use:

- `num_gaussians=32768`;
- `guidance=3.0`;
- `erode_radius=1`;
- `Base`: 10 TripoSplat steps;
- `High`: 20 steps;
- `XHigh`: 20 steps;
- prompt images: 512×512 for Base/High, 1024×1024 for XHigh, always 8 image
  steps.

Supported voxel resolutions are `32`, `64`, `128`, `256`, `512`, and `1024`.
The client validates TSVOX v2 before constructing any Litematica or C&B output.
It keeps the generated PLY for the active session so resolution changes can
repeat voxelization without regenerating the 3D model. Palette and blacklist
changes at the same resolution remap the existing sparse grid locally.

Input limits are 25 MiB for a signature-validated PNG/JPEG and 8192 UTF-8 bytes
for a prompt. Text generation requires `/v1/text-generations` from
TripoSplatVulkan `v0.2.0` or newer.

Implementation details and threading boundaries are documented in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Palette maintenance

Palette colors must be generated from the exact legally installed Minecraft
client JAR for the target version. `tools/VanillaPaletteGenerator.java` keeps
only eligible opaque, non-falling full-cube states and calculates numeric
per-face colors. It does not copy Minecraft textures into project output.

Version-specific colors and reusable block categories are separate:

- `palette-<minecraft>.json` contains block states and generated colors;
- `block-categories.json` maps namespaced block IDs to `all`, `survival`, and
  `solid_colors`;
- `tools/palette/classification/block-categories.txt` is the reviewed source
  mapping.

A new Minecraft target must not be enabled until its block-name input and color
palette have been generated twice from the exact client JAR, reproduced,
classified, applied, and reviewed. The full command sequence and validation
rules are in [tools/palette/README.md](tools/palette/README.md).

Custom user palettes are stored as version-independent block-ID differences.
IDs missing from the current registry remain on disk but are omitted from the
effective palette.

## End-to-end validation

Remote validation needs a reachable TripoSplat API v1 base URL and at least one
PNG/JPEG reference image. Also test a bright object and an object with thin
details when changing generation, voxelization, or color mapping.

Local validation needs Windows or Linux x86-64 with a Vulkan GPU and roughly
6 GB free for first-time base model installation. Prompt validation needs the
additional prompt model set and roughly 7.2 GB more free space.

For each affected Minecraft target, verify:

1. image generation through Local and Remote where available;
2. prompt generation when `/v1/text-generations` is available;
3. at least two voxel resolutions without regenerating the PLY;
4. palette and blacklist remapping at the same resolution;
5. `.litematic` creation and selected placement;
6. optional `.msbp` save, reload, preview, collision check, placement, lighting,
   and rollback with the exact supported C&B version.

## Continuous integration

`.github/workflows/ci.yml` runs for pushes and pull requests targeting `main`,
`1.21.1/stable`, and `1.20.1/stable`. It performs `./gradlew --no-daemon clean
build`, resolves the one main JAR and matching `.mrpack`, and uploads those
verified files as workflow artifacts.

## Release procedure

Releases are per Minecraft target. The tag format is:

```text
mc<minecraft>-<mod-version>
```

A single MineSplat version therefore normally has three tags, one on each
supported branch. The tag target, branch ancestry, Minecraft version, and
`mod_version` must agree.

For every target:

1. Update `mod_version`, commit the change, push the branch, and wait for CI.
2. Run the `Release` workflow manually on that branch with the intended tag.
   This is a build-only dry run.
3. Create an annotated tag on the exact tested commit and push only that tag.
4. Wait for the tag-triggered workflow and verify its three published assets
   against `SHA256SUMS`.

Example for the feature line, using placeholders intentionally:

```bash
gh workflow run release.yml \
  --ref main \
  -f release_tag=mc1.21.11-X.Y.Z

git tag -a mc1.21.11-X.Y.Z COMMIT \
  -m "MineSplat X.Y.Z for Minecraft 1.21.11"
git push origin mc1.21.11-X.Y.Z
```

The workflow publishes exactly:

```text
minesplat-fabric-<minecraft>-<mod-version>.jar
minesplat-prism-<minecraft>-<mod-version>.mrpack
SHA256SUMS
```

It never publishes `-sources.jar`. Published tags are immutable; use a new mod
version for corrections. Only the Minecraft 1.21.11 release is marked `Latest`.

## Related documentation

- [User guide](docs/USER_GUIDE.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Palette pipeline](tools/palette/README.md)
- [TripoSplatVulkan source](https://github.com/fropych/TripoSplatVulkan)
