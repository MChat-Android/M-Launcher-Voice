# M Asisten — Source Layout Audit

Date: 2026-11-26
Repository: MChat-Android/M-Launcher-Voice
Audited ref: main
Workflow: .github/workflows/blank.yml
Workflow blob SHA: e763ee94f17c8e76a88fa5800e16d56289f4d863
Audit branch: offline-ai/source-layout-audit

## Purpose

Map the generated Android source and build pipeline before extracting files into a conventional Android project layout. This is a source-structure audit only: no application code, dependencies, permissions, package identity, signing configuration, Firebase behavior, UI, or APK output was changed.

## Generated project files

The workflow's `Create Android Project` step begins at line 61. It creates the Gradle project and writes source files using shell heredocs:

| Generated file | Workflow lines | Responsibility |
|---|---:|---|
| `settings.gradle.kts` | 65–82 | Gradle settings and repositories |
| `build.gradle.kts` | 84–91 | Android/Kotlin/Compose/Google Services plugin versions |
| `app/build.gradle.kts` | 93–159 | Android configuration and dependencies |
| `app/src/main/AndroidManifest.xml` | 161–256 | Permissions, package components, launcher/home activities |
| `MainActivity.kt` | 258–1474 | Account, Firebase, VIP+, home UI, payment/admin UI and setup |
| `FloatingVoiceService.kt` | 1476–5086 | Floating widget, speech, TTS, command engine, device actions |

After source generation, the workflow configures Firebase at 5088–5106, generates icons at 5108–5140, decodes the release keystore at 5142–5154, builds at 5156–5162, verifies the signature at 5164–5168, copies the APK to `masisten.apk` at 5170–5173, and uploads artifact `M-Asisten-APK` at 5175–5180.

## Source responsibilities and boundaries

### MainActivity.kt

- Activity and Firebase Google sign-in flow begin in the first part of the file.
- Admin custom-claim and regular VIP membership checks are in the activity.
- Main Compose home screen includes the account, package, VIP+ purchase/request and administration UI.
- Widget activation performs permission checks and server-side VIP validation before choosing Standard or VIP mode.
- This file is coupled to Firebase Auth, Firestore, Google Sign-In, Android permissions and Compose UI. Extracting it should initially be a byte-preserving move, not a redesign.

### FloatingVoiceService.kt

- Service lifecycle, requested mode verification and VIP membership listener are near lines 1520–1700.
- Floating widget rendering and VIP+ badge/frame are near lines 1700–1900.
- Android TextToSpeech initialization is near lines 1987–2032.
- SpeechRecognizer setup and offline-language support checks are near lines 2130–2324.
- User-facing status and TTS response handling are near lines 2326–2374.
- Deterministic command normalization and execution start at line 2376 and continue through most of the remaining service, ending near line 5078.
- Service cleanup is at lines 5080–5084.

## Offline capability facts observed in source

1. No local generative LLM runtime, tokenizer, model loader, model file, or model download/checksum manager was found in the audited workflow-generated source. The current command engine is deterministic Kotlin command matching.
2. Speech recognition explicitly uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` and checks `isOnDeviceRecognitionAvailable`. It does not intentionally fall back to cloud recognition.
3. On Android 13+, the code checks installed, pending and supported on-device languages. If a needed language is not installed, it asks Android to download the speech language pack; that initial download requires connectivity. A pending/downloaded pack is not proof of current offline readiness.
4. On older supported Android versions, the code passes the device language and `EXTRA_PREFER_OFFLINE=true`, but cannot perform the same detailed language-support query.
5. TTS uses Android's installed TextToSpeech engine and device language, with English (US) fallback. The code cannot guarantee that a matching voice is installed or that the engine will speak offline.
6. Speech and TTS are managed by the floating service. There is not yet a separate speech abstraction or resource coordinator for sharing memory with a future LLM.
7. Firebase account and VIP entitlement checks use server-backed token/Firestore requests. Therefore account/licensing flows are not fully offline. Keep these separate from the offline AI inference requirement; do not remove or weaken the existing entitlement verification as part of AI work.

## Build and release invariants

- Android application ID / namespace: `com.m.launchervoice`.
- User-visible application label: `M Asisten`.
- APK download filename: `masisten.apk`.
- Artifact name: `M-Asisten-APK`.
- Compile SDK 35, min SDK 23, Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21.
- Release signing uses GitHub Actions secrets and a runner-temporary keystore. Never commit keystore material, passwords, OAuth secrets or Firebase service credentials.
- Preserve Firebase configuration validation, launcher icon generation and APK signature verification.

## Safe next-stage plan

1. On this isolated branch, create conventional project files by extracting the exact heredoc bodies without editing their contents.
2. Change the workflow only to stop regenerating files that now exist in the repository. Preserve all setup, Firebase, icon, signing, build, verification and artifact steps.
3. Compare extracted files against the heredoc-generated equivalents byte-for-byte (accounting only for known heredoc indentation stripping). Confirm no source behavior changes.
4. Run security checks and a signed release build on the branch. Verify APK signature, package ID, app label and artifact filename.
5. Only after a successful baseline extraction build, introduce small, separately tested AI architecture changes. Do not add an LLM runtime or model until licensing, Android compatibility, memory profile and model provenance are verified.

## Limits of this audit

This report is based on the workflow source, not a device runtime session. It does not prove offline speech language-pack availability on a specific phone, TTS offline behavior, LLM compatibility, 4 GB RAM performance, thermal behavior, battery use, or APK build success for the current audited HEAD. A successful build is not evidence of device compatibility.
