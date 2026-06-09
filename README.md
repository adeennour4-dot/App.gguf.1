# GGUF ZeroCopy v5 PRO

On-Device Inference, Re-engineered.

GGUF ZeroCopy is a high-performance, production-ready inference engine for Android. Using a zero-copy shared memory architecture, it delivers real-time LLM responses with minimal latency and maximum hardware utilization. Toolneuron-inspired UI with 5-screen navigation.

---

## What's New in v5 PRO

- **Extreme Performance**: Upgraded to llama.cpp b9542 with LLVM ThinLTO and DotProd acceleration
- **KV-Cache Quantization**: Q8_0 Cache Quantization — 2x more context (16k+) in the same RAM footprint
- **Glassmorphic Cyber-UI**: Bottom navigation (Chat / Models / Settings / Info / Bench) with real-time TPS telemetry
- **Vulkan Unified Memory**: Direct GPU-memory mapping for ultra-low latency streaming
- **Robust Foundation**: Gradle 9.5.1, NDK r29, Compose BOM 2026.05.00, AGP 9.1.1
- **All APIs Fixed**: 15 missing EngineCore methods and JNI implementations fully resolved
- **Benchmark Suite**: Prompt-processing + token-generation speed measurement with visual results
- **Thinking Mode**: Collapsible `<think>` blocks with animated reasoning indicator
- **Code Rendering**: Markdown code blocks rendered with syntax-friendly styling
- **Quick Presets**: One-tap config for Qwen3, Gemma 4, Reasoning, and Creative modes

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Kotlin / Compose UI (5 screens)                        │
│  ┌──────┐ ┌──────┐ ┌────────┐ ┌────┐ ┌─────┐          │
│  │ Chat │ │Models│ │Settings│ │Info│ │Bench│           │
│  └──┬───┘ └──┬───┘ └───┬────┘ └─┬──┘ └──┬──┘          │
│     │        │          │        │       │              │
│  ┌──▼────────▼──────────▼────────▼───────▼──────────┐  │
│  │              EngineCore (Singleton)               │  │
│  │  JNI bridge · Config · Shared memory reader       │  │
│  └─────────────────────┬────────────────────────────┘  │
└────────────────────────┼──────────────────────────────┘
                         │ JNI
┌────────────────────────▼──────────────────────────────┐
│  C++ ipc-bridge (llama.cpp b9542)                      │
│  ┌───────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │ Model     │  │ Context  │  │ Shared Buffer Ring  │  │
│  │ Loading   │  │ +Sampler │  │ (512KB, zero-copy)  │  │
│  └───────────┘  └──────────┘  └────────────────────┘  │
│  ┌──────────────────────────────────────────────────┐  │
│  │ llama.cpp + GGML Vulkan + Q8_0 KV-Cache + ThinLTO│  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

---

## Build Specs

| Component   | Version              |
|-------------|----------------------|
| Gradle      | 9.5.1                |
| AGP         | 9.1.1                |
| NDK         | 29.0.14206865        |
| llama.cpp   | b9542 (June 2026)    |
| Compose BOM | 2026.05.00           |
| Kotlin      | 2.3.21               |
| Min SDK     | 27                   |
| Target SDK  | 36                   |

---

## Quick Setup

### 1. Prerequisites

- Android Studio Ladybug (or newer)
- NDK r29 installed via SDK Manager
- ~8 GB of free RAM for building (llama.cpp is large)

### 2. Build

```bash
# Debug APK
./gradlew assembleDebug --parallel --offline

# Release APK
./gradlew assembleRelease
```

The first build will download llama.cpp (~1 GB) via CMake FetchContent — expect 15–30 minutes.

### 3. CI/CD

This project includes a GitHub Actions workflow (`.github/workflows/build.yml`) that:
- Installs SDK 36, NDK r29, CMake 3.22.1
- Caches Gradle and CMake dependencies
- Builds debug or release APK (configurable via workflow dispatch)
- Uploads the APK as a build artifact (30-day retention)

Push to `main`/`master` or trigger manually via **Actions → Build APK → Run workflow**.

---

## Screens

| Screen    | Description                                         |
|-----------|-----------------------------------------------------|
| **Chat**  | Streaming chat with thinking mode, code blocks, copy |
| **Models**| Load/manage GGUF models, view device info + RAM      |
| **Settings** | Temperature, top-p, min-p, penalties, system prompt |
| **Info**  | Model metadata, architecture, session stats          |
| **Bench** | PP + TG speed benchmark with visual results          |

---

## Configuration Reference

| Setting         | Optimal  | Description                          |
|-----------------|----------|--------------------------------------|
| n_ctx           | 8192     | Context window size                  |
| n_gpu_layers    | 99       | Forces full Vulkan offload           |
| type_k / type_v | Q8_0     | KV-Cache quantization (Pro Feature)  |
| optimization    | ThinLTO  | Cross-module function inlining       |

---

## Optimization Tips

- **Vulkan Stability**: If the device crashes during boot, ensure the driver supports Vulkan 1.3
- **Memory**: Always load models via `ACTION_OPEN_DOCUMENT` — this lets the engine mmap the FD directly (true zero-copy path)
- **Build Speed**: Use `./gradlew assembleDebug --parallel --offline` with a warm Gradle cache
- **Swap Models**: The "Swap" button in the top bar unloads the previous model before loading a new one

---

## Project Map

See `PROJECT_MAP.md` for a detailed file-by-file breakdown, architecture diagram, and pending features.

---

## License

Copyright © 2026 GGUF ZeroCopy Engine. Built for power users.
