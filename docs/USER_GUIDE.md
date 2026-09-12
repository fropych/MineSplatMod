# MineSplat user guide

## Quick Prism Launcher install

1. Install a current [Prism Launcher](https://prismlauncher.org/) and add the
   Microsoft account that owns Minecraft Java Edition.
2. Select `Add Instance → Import`.
3. Download the `.mrpack` that exactly matches the Minecraft version from
   [GitHub Releases](https://github.com/fropych/MineSplatMod/releases), for
   example `minesplat-prism-<minecraft>-<mod-version>.mrpack`. Packs for
   different targets are not interchangeable; the adjacent `SHA256SUMS`
   verifies the download.
4. Verify the instance uses Java 17 for Minecraft 1.20.1 or Java 21 for 1.21.1
   and 1.21.11. Use ARM64/AArch64 Java on Apple Silicon and x64 Java on an
   Intel Mac.
5. Allocate 4–6 GiB RAM.
6. Launch once and create or open a world.
7. Press `M + G` to open the MineSplat wizard. Inference is configured through
   `Settings` in its header.
8. On Windows/Linux x86-64, open Settings, choose `Local`, keep or select a
   model directory, choose `Base`, `High`, or `XHigh`, select
   `Install base models`, then `Test local`. Select `Install prompt models`
   separately only when local text generation is wanted. After the device list
   appears, click the GPU button to cycle through detected devices.
9. To use your own service, choose `Remote` in Settings, enter its HTTP/HTTPS
   base URL, and select `Test API`. It can also be checked with
   `curl <BASE_URL>/health`.
10. On the Source step choose Image and drop/select a PNG/JPEG, or choose Prompt
    and describe the object. Seed is under Advanced settings. Continue to Block
    settings, choose the name, voxel preset, palette, and output, then create
    it.

`All` contains the eligible opaque full-cube blocks for the current Minecraft
version, `Survival` excludes blocks unavailable in ordinary Survival, and
`Solid Colors` keeps wool, concrete, and terracotta. Falling blocks such as
sand and translucent blocks are excluded from every palette. Block sets and
colors are calculated separately for every supported Minecraft target.

The palette button opens a dedicated manager where built-in palettes can be
selected and custom palettes can be created, edited, duplicated, or deleted.
The editor provides a base category, block-ID search, and per-block toggles.
Custom palettes live in `minecraft/config/minesplat/palettes/*.json`; the
global blacklist is applied on top of the selected palette.

When Litematica output is selected, the `.litematic` is saved under
`<instance>/minecraft/schematics/minesplat/`, loaded by Litematica, and placed
in front of the player.

The local executable is bundled in the JAR. Five pinned base files are
downloaded and three are converted to the runtime format (3,609,105,870 final
bytes). The optional Z-Image/Qwen/VAE prompt set is an independent explicit
download of 6,696,835,812 bytes. Both sets are stored by default under
`minecraft/minesplat/models/<revision>/`. Downloads resume from `.part` files;
source and converted files are checked by size and SHA-256.
Local text generation also needs roughly 8 GiB of available system RAM for CPU
parameter offload.

Local mode requires x86-64 Windows or Linux, a Vulkan 1.2-capable GPU, and a
current driver; NVIDIA is the primary upstream-tested target. There is no CPU
backend and no automatic fallback to Remote. Use Remote on macOS, ARM64/AArch64,
or any unsupported platform.

Closing the window while work is in progress does not stop the task. If
job-status polling loses its connection, MineSplat retries after 1, 2, and 4
seconds, then offers `Retry connection`. API v1 cannot cancel a GPU job that
has already started; only a queued job can be cancelled.

Changing 32/64/128/256/512/1024 in the same session repeats only voxelization
of the existing PLY. Changing the palette or blacklist at the same resolution
rebuilds the output locally. Changing the seed, image, or prompt creates a new
Gaussian model. `1024³` matches the TripoSplat API maximum but is exceptionally
heavy: allocate at least 12–16 GiB to the instance. TSVOX, color matching, and
the Litematica container can use hundreds of megabytes, while server-side
voxelization can take substantially longer.

## Optional Chisels & Bits integration

The base `.mrpack` deliberately does not bundle Chisels & Bits, and normal
Litematica export works without it.

1. Open `Edit Instance → Mods → Download Mods` in Prism.
2. Install the exact Fabric release for your target: Chisels & Bits `20.1.20`
   on 1.20.1, `21.1.33` on 1.21.1, or `21.11.45` on 1.21.11. Accept the
   dependencies suggested by Prism.
3. Open a singleplayer world in Creative mode.
4. On the Blocks step select `Chisels & Bits miniature` and generate the
   blueprint.
5. On the Result page select `Place miniature`.
6. Position the colored hologram with `R` / `Shift+R`, the arrow keys, and
   Page Up/Down. Right-click confirms; Escape cancels.

All controls except mouse confirmation can be rebound under Controls.

Saved `.msbp` files are kept under
`<instance>/minecraft/minesplat/blueprints/` and can be reopened from
`Saved miniatures` after a restart. One TSVOX voxel maps to one C&B bit.
Resolutions 32/64/128/256/512/1024 occupy at most 2/4/8/16/32/64 host blocks
per side with the standard 16-bit grid. Placement requires every occupied host
block to be air and is supported only in Creative singleplayer. This output
path does not create or place a Litematica schematic. MineSplat also tries to
place one invisible level-15 light near the center of every occupied 5×5×5
host-block section where an air position is available. Existing world blocks
are never replaced.

## Manual install

Create an instance for the selected Minecraft target and install Fabric Loader
`0.19.3`, then use the dependency versions listed for it:

| Minecraft | Java | Fabric API | Litematica | MaLiLib | Mod Menu (optional) | C&B (optional) |
| --- | ---: | --- | --- | --- | --- | --- |
| 1.20.1 | 17 | `0.92.11+1.20.1` | `0.15.4` | `0.16.3` | `7.2.2` | `20.1.20` |
| 1.21.1 | 21 | `0.116.15+1.21.1` | `0.19.61` | `0.21.10` | `11.0.3` | `21.1.33` |
| 1.21.11 | 21 | `0.141.6+1.21.11` | `0.26.13` | `0.27.18` | `17.0.0` | `21.11.45` |

Add the matching `minesplat-fabric-<minecraft>-<mod-version>.jar` and allocate
4–6 GiB RAM. Every target has a separate JAR and `.mrpack`; runtime assets for
both Windows and Linux x86-64 are bundled inside each Minecraft-specific JAR.
Chisels & Bits remains optional and is not included in the `.mrpack`.

MineSplat is client-only. No MineSplat, Litematica, or MaLiLib installation is
needed on a Minecraft server. The local API binds only to `127.0.0.1`. TLS and
authentication for a Remote API remain the API owner's responsibility.

Prism documentation:
[creating instances](https://prismlauncher.org/wiki/getting-started/create-instance/)
and [managing loader mods](https://prismlauncher.org/wiki/help-pages/loader-mods/).
