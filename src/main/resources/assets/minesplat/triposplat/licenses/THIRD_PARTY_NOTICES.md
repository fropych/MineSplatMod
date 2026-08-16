# Third-party notices

TripoSplatVulkan incorporates or links the following projects:

- **VAST TripoSplat** — model architecture and compatible weights, MIT;
  copyright VAST/TripoAI contributors.
- **ggml v0.17.0** — tensor runtime, CPU parameter-offload support and Vulkan
  backend, MIT; upstream commit
  `9be313313c8ecb9488911bd64550190e3ed80f38`, with the patches documented in
  `docs/ggml-backend.md` and a fixed build identity
  `9be3133+triposplat-vulkan`.
- **stable-diffusion.cpp** — Z-Image inference runtime, MIT; fork commit
  `356c29e6a2e57c18fe1771117ed20d82287ae7b5`, based on upstream tag
  `master-813-bfbef5b`, commit
  `bfbef5b7e64e89a0205894853de25d19a7ba54b9`. The fork only adds selectable
  embedded-tokenizer data; TripoSplatVulkan compiles its `qwen2` profile. It is
  linked statically without its CLI, server, WebP, WebM or nested ggml targets.
- **Darts clone** — double-array trie used by the stable-diffusion.cpp
  tokenizer, BSD-3-Clause; copyright Susumu Yata.
- **kuba--/zip** — ZIP reader used by the stable-diffusion.cpp model loader,
  MIT.
- **miniz 2.2.0** — public-domain deflate and ZIP implementation embedded by
  kuba--/zip.
- **CivetWeb 1.17** — embedded HTTP server, MIT; upstream commit
  `3309a6cac05335aa4371a0c3750b42fbe05d3cb4`; copyright the CivetWeb
  developers, Sergey Lyubka, No Face Press and F-Secure.
- **nlohmann/json 3.11.3** — JSON processing, MIT; upstream commit
  `9cca280a4d0ccf0c08f47a99aa71d1b0e52f8d03`; copyright Niels Lohmann and
  contributors.
- **stb_image / stb_image_write** — image decoding and PNG serialization,
  upstream commit
  `3ecc60f25ae1391cf6434578ece782afa1458b56`, MIT or public-domain dual
  license; copyright Sean Barrett and contributors. This distribution uses
  the MIT option.
- **libwebp / SharpYUV** — WebP decoding, BSD-3-Clause; copyright Google Inc.
  and contributors. The release executable links these libraries statically.

The optional weights downloaded by `download-text-models` are not distributed
inside TripoSplatVulkan archives:

- **Z-Image-Turbo Q4_K** from `leejet/Z-Image-Turbo-GGUF`, revision
  `c61c0e422dc8b541b7548cf33a4ef8302b0f8085`, Apache-2.0;
- **Qwen3-4B-Instruct-2507 Q4_K_M** from
  `unsloth/Qwen3-4B-Instruct-2507-GGUF`, revision
  `a06e946bb6b655725eafa393f4a9745d460374c9`, Apache-2.0;
- **FLUX.1-schnell VAE** from `black-forest-labs/FLUX.1-schnell`, revision
  `93fae7d7f6189cc408fdd7cec36c91447b8506a2`, Apache-2.0.

Users download these files directly from their publishers and remain
responsible for the model terms shown on the corresponding Hugging Face model
cards.

Complete license texts are shipped from the source dependency directories or
the root `licenses/` directory. The project-level [MIT license](LICENSE)
applies only to TripoSplatVulkan first-party code.
