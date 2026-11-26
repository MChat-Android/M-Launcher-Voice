# Offline AI POC — LiteRT-LM isolation plan

Status: research-backed implementation gate; no production dependency or model has been added.

## Candidate selected for the first isolated proof of concept

Use Google LiteRT-LM Android **0.18.0** in a standalone POC application/module, not in the production `app` module.

Reasons:
- Official Android integration is available through Google Maven as `com.google.ai.edge.litertlm:litertlm-android:0.18.0`.
- The official Android guide documents the Kotlin API, streaming conversations, CPU/GPU backends, model loading from an absolute local file path, and lifecycle requirements.
- The package declares `minSdk 24`; M Asisten production currently has `minSdk 23`, so it must not be added to production without a separately designed compatibility strategy.
- The current production build uses Kotlin 2.0.21; LiteRT-LM 0.18.0 brings Kotlin 2.4 metadata and requires a newer Kotlin toolchain. This is another reason to isolate it.
- LiteRT-LM and the Google AI Edge sample repository are Apache-2.0 licensed. The model license is separate and must be checked independently.

## POC model candidate

Start with `litert-community/Qwen3-0.6B` in `.litertlm` format, documented as approximately 0.6 GB. Do not bundle it in the APK. Do not download the model during CI or into the development agent environment. The POC should accept a model already placed on the test device, then validate file size/hash before loading.

Before any public release, review the exact model card and license for the exact artifact revision, including redistribution and commercial-use terms. A repository-level license is not a substitute for the model's own terms.

## POC implementation requirements

1. A separate standalone Gradle project or isolated module, not included in the production settings or dependency graph.
2. Android minSdk 24 only inside the POC; production remains minSdk 23.
3. Pin the runtime version; do not use `latest.release`.
4. Load model from app-private storage using an absolute path. Never place model weights in assets or APK.
5. Initialize and run inference on a single-thread background executor; never block the main thread.
6. Stream output to a minimal test screen. Keep context/output bounded.
7. Ensure Conversation closes before Engine and each is closed exactly once. If initialization fails, do not close an Engine that never initialized successfully.
8. CPU backend is the baseline. GPU is optional and only tested with the documented optional native-library declarations; unavailable GPU must not break CPU mode.
9. No network calls during inference. Model transfer is a separate explicit setup step; airplane-mode inference must work after model installation.
10. POC must not connect Firebase, user accounts, VIP claims, payments, microphone, or production floating widget.

## Test gates

- Build: standalone POC compiles with pinned dependencies.
- Functional: load model, run at least 20 Indonesian prompts, stream responses, reset conversation, unload/reload.
- Offline: install model first, enable airplane mode, cold-start POC, infer successfully with network disabled.
- Reliability: repeat load/unload cycles; verify no double-close, crash, or OOM.
- Memory: record PSS/RSS/native heap, peak memory, model load time, first-token latency, tokens/sec, thermal state, battery impact, and free storage.
- Device tiers: test an actual 4 GB RAM Android phone as the low-memory target, plus at least one higher-memory device. A successful emulator build is not evidence of device performance.
- Safety: LLM output is not executed directly. Future integration must translate to a strict allowlisted intent schema, validate parameters, and confirm sensitive actions.

## Integration decision

Do not merge the POC runtime into M Asisten production until:
- build and runtime behavior are verified on target devices;
- memory/latency measurements are acceptable;
- exact model license and redistribution terms are approved;
- compatibility strategy preserves production minSdk 23, app identity, Firebase, account/VIP/VIP+, payments, and existing deterministic commands.

## Official references checked 2026-11-26

- Android LiteRT-LM integration guide: https://developer.android.com/agents/skills/device-ai/litert/litert-lm/skill
- LiteRT-LM Kotlin getting started: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- LiteRT-LM repository and Apache-2.0 license: https://github.com/google-ai-edge/LiteRT-LM
- Google AI Edge sample repository and Apache-2.0 license: https://github.com/google-ai-edge/litert-samples
