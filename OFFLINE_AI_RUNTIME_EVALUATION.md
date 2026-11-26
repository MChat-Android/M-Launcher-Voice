# Offline AI Runtime Evaluation

Date: 2026-11-26
Branch: `offline-ai/source-layout-audit`

## Current application constraints

- Existing application: `com.m.launchervoice`, display name `M Asisten`.
- Current `minSdk = 23`, compile/target SDK 35, Java 17.
- Keep the deterministic voice-command engine working when no model is installed, no network is available, or model loading fails.
- Do not bundle multi-gigabyte model files in the APK.
- Model downloads must be explicit/on-demand, integrity checked, stored privately, and removable.
- Do not send user prompts or transcripts to a cloud model.
- No automatic cloud fallback.
- Do not promise generative AI performance on every 4 GB device.

## Runtime candidates examined

### llama.cpp / GGUF

The upstream Android documentation describes an Android Studio example using a Kotlin API, app-private model paths, and streaming token output. It also documents NDK cross-compilation for Android arm64-v8a. Its portable NDK example uses Android platform 28, disables OpenMP, llamafile, and OpenSSL, and warns that context size affects memory use.

Potential advantages:
- GGUF ecosystem and downloadable quantized model files.
- Android NDK integration can keep inference local and model storage separate from the APK.
- Native runtime gives control over context size, thread count, and memory lifecycle.

Open questions before adoption:
- Verify the exact upstream Android binding/runtime license and pin a reviewed release/commit.
- Confirm minimum supported Android API and supported ABIs for the chosen integration.
- Confirm the selected model's license permits commercial redistribution/download and use.
- Measure actual peak memory, load time, generation speed, heat, and battery on representative 4 GB devices.
- Build reproducible CI artifacts and verify native libraries are packaged correctly.

### Google LiteRT-LM

Official Google AI Edge documentation describes LiteRT-LM as an on-device LLM runtime with Kotlin APIs, CPU/GPU execution, streaming conversation, and tool-use support. The official getting-started material currently documents Android API 24+ and a Gradle dependency using the Android LiteRT-LM artifact.

Compatibility concern:
- This app currently declares minSdk 23. Adding a runtime requiring API 24 without an explicit compatibility strategy could break installation or build behavior on API 23 devices.
- Model format, model distribution terms, runtime version pinning, hardware acceleration coverage, and peak RAM on low-memory devices must be validated before adoption.

## Decision

Do not add either runtime dependency to the production app yet.

Run a contained proof-of-concept on the migration branch. Compare:
1. A pinned llama.cpp Android binding/runtime with a small quantized GGUF model.
2. A pinned LiteRT-LM Android integration with an officially supported downloadable model.

For each candidate, record:
- license and version/commit;
- minimum Android API and ABI;
- APK/AAB native library size;
- model file size and license;
- cold load time and first-token latency;
- tokens/second;
- peak process PSS/RSS and native heap;
- behavior under low-memory pressure;
- thermal/battery behavior;
- offline behavior after model and required language assets are installed.

A candidate is not production-ready until its licensing, compatibility, and device benchmarks pass. Keep the existing command engine as the always-available fallback.

## Sources

- llama.cpp Android documentation: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- llama.cpp build documentation: https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md
- Google AI Edge LiteRT-LM Android guide: https://developers.google.com/edge/litert-lm/android
- Google AI Edge LiteRT overview: https://developers.google.com/edge/litert/overview
