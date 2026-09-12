# MineSplat

MineSplat turns an image or text description into a 3D Minecraft build. Choose
the build size and allowed blocks, then use the result with Litematica or create
a detailed Chisels & Bits miniature.

MineSplat only needs to be installed for the player using it. Minecraft
servers do not need it.

[Download](https://github.com/fropych/MineSplatMod/releases) ·
[Detailed user guide](docs/USER_GUIDE.md) ·
[Developer documentation](DEVELOP.md)

## Contents

- [Supported versions](#supported-versions)
- [Installation](#installation)
- [First-time setup](#first-time-setup)
- [Creating a build](#creating-a-build)
- [Quality and build size](#quality-and-build-size)
- [Block palettes](#block-palettes)
- [Litematica and Chisels & Bits](#litematica-and-chisels--bits)
- [Common problems](#common-problems)

## Supported versions

Download the MineSplat release that matches your Minecraft version. Files for
different Minecraft versions cannot be mixed.

| Minecraft | Java | Chisels & Bits, if used |
| --- | ---: | --- |
| 1.20.1 | 17 | `20.1.20` |
| 1.21.1 | 21 | `21.1.33` |
| 1.21.11 | 21 | `21.11.45` |

## Installation

The Prism Launcher pack is the simplest option:

1. Open [GitHub Releases](https://github.com/fropych/MineSplatMod/releases).
2. Download the Prism pack for your Minecraft version. Its name starts with
   `minesplat-prism-` and ends with `.mrpack`.
3. In Prism Launcher, select `Add Instance → Import` and choose the downloaded
   file.
4. Check the Java version in the table above and allocate 4–6 GB of memory.
5. Start the instance and open a world.

The pack already includes MineSplat, Litematica, and the required Fabric mods.
Chisels & Bits is optional and must be installed separately using the version
shown above. In Prism Launcher, open `Edit Instance → Mods → Download Mods`,
search for Chisels & Bits, and install that version.

For manual installation, see the [detailed user guide](docs/USER_GUIDE.md).

## First-time setup

`Local` is the default and recommended mode:

1. Open a world, press `M + G`, and select `Settings`.
2. Keep `Local` selected.
3. Select `Install base models` and wait for the installation to finish.
4. Select `Test local`.
5. If several graphics cards are shown, use the GPU button to choose one.
6. Select `Install prompt models` only if you want to create models from text.

Local generation requires 64-bit Windows or Linux, a graphics card that
supports Vulkan 1.2, and a current driver. NVIDIA graphics cards are tested
most often. CPU-only generation is not supported.

The base models use about 3.6 GB of disk space; keep about 6 GB free during
installation. Text prompts need an additional 6.7 GB of models. While creating
from text, keep about 8 GB of system RAM available in addition to the RAM
allocated to Minecraft. Interrupted downloads can continue later.

`Remote` uses a compatible server configured by you instead of your graphics
card. Enter its address in `Settings` and select `Test API`. If you do not
already have access to such a server, leave `Local` selected.

## Creating a build

1. Open a world and press `M + G`.
2. Choose `Image` and select or drop a PNG/JPEG, or choose `Prompt` and
   describe the object.
3. Select `Next: block settings`.
4. Enter a name, choose the build grid size, palette, excluded blocks, and
   either Litematica or Chisels & Bits.
5. Start creation and wait for the Result page.

Closing the MineSplat window does not stop creation. During the current game
session, changing only the grid size, palette, or excluded blocks avoids
generating the 3D model again and is usually much faster.

## Quality and build size

Quality is selected in `Settings`:

- `Base` — fastest; start with this mode.
- `High` — takes longer and can produce more detail.
- `XHigh` — uses a larger generated image for text prompts. For uploaded
  images, it behaves like `High`.

The setting named `Voxel resolution` controls the build grid size. A larger
number can preserve more detail, but also makes the build larger and uses more
time and memory.

- For Litematica, `64` means the build can be up to 64 blocks wide, high, and
  deep.
- For Chisels & Bits, `64` means a miniature up to 4 normal blocks in each
  direction, made from tiny bits.

`64` is the default and a good starting point. `1024` is extremely demanding;
use a lower value unless Minecraft has 12–16 GB of allocated RAM.

## Block palettes

- `All` — all suitable solid blocks.
- `Survival` — blocks intended for Survival builds.
- `Solid Colors` — wool, concrete, and terracotta.

Glass and other transparent blocks, plus sand and other falling blocks, are not
used. You can create a custom palette to choose the allowed blocks, and exclude
individual blocks from any build.

## Litematica and Chisels & Bits

### Litematica

MineSplat saves the schematic and shows the build guide in front of the player
through Litematica. It does not place real blocks. Use the normal Litematica
tools to position and build it. This works in singleplayer and multiplayer.

### Chisels & Bits

Chisels & Bits must be installed before selecting this option. MineSplat
creates a miniature that can be reopened from `Saved`.

Miniatures can be created in any open world, but placed only in Creative
singleplayer. The target area must be empty; MineSplat does not replace
existing blocks.

While positioning a miniature:

- use `R` / `Shift+R` to rotate it;
- use the arrow keys and Page Up/Down to move it;
- right-click to place it;
- press `Esc` to cancel.

## Common problems

- **Local is unavailable:** update the graphics driver and check that the
  graphics card supports Vulkan 1.2. Otherwise use Remote.
- **MineSplat says setup is required:** open `Settings`, install the base
  models, and select `Test local`.
- **Text prompts are unavailable:** install the prompt models in `Settings`.
- **The download reached 100% but did not finish:** MineSplat is checking or
  preparing the files. Wait for the status message to change.
- **Chisels & Bits is unavailable:** install the exact version listed for your
  Minecraft version and restart the game.
- **Minecraft runs out of memory:** lower the build grid size. Avoid `1024`
  unless enough memory is allocated.

MineSplat is licensed under the [MIT License](LICENSE).
