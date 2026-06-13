# GGUF ZeroCopy v7

Android LLM inference app with multi-engine support (llama.cpp, MNN, LiteRT-LM) for on-device private AI.

## Features

### Multi-Engine Support
| Engine | Format | License | Best For |
|--------|--------|---------|----------|
| **llama.cpp** | `.gguf` | MIT | GPU acceleration (Vulkan/OpenCL/CPU), widest model compatibility |
| **MNN** | `.mnn` | Apache 2.0 | CPU-optimized inference (faster than llama.cpp on CPU) |
| **LiteRT-LM** | `.tflite` / `.litertlm` | Apache 2.0 | Google models, NPU access |

### Performance Optimizations (All Open Source)
- **Big core pinning** — `sched_setaffinity()` to ARM big cores for maximum single-thread performance
- **Process priority boost** — `setpriority(PRIO_PROCESS, 0, -20)` for maximum throughput
- **RAM locking** — `mlockall()` to prevent page faults during inference
- **ThinLTO compilation** — `-O3 -flto=thin` for faster builds and better optimization
- **ARM instruction set** — `-march=armv8.6-a+dotprod+i8mm+fp16` for best ARM performance
- **Context shifting** — KV cache reuse for long conversations without re-prefilling
- **Prompt-cache optimization** — Only decode new tokens, not entire conversation

### Device-Aware Auto-Configuration
- Auto-detects CPU cores (big.LITTLE topology)
- Auto-detects SoC (Snapdragon/Exynos/MediaTek/Tensor)
- Suggests optimal GPU layers (99 for Snapdragon OpenCL, 0 for others)
- Suggests optimal thread count based on big cores
- Auto-sizes context window based on available RAM
- Checks available RAM before model loading

## Architecture

```
MainActivity.kt                (Compose UI, state management)
├── WelcomeScreen           (Initial screen with model loading)
├── ChatList                (Chat message display)
├── ChatBubble              (Individual message bubbles)
├── InputBar                (Message input with send/stop/benchmark)
├── SettingsSheet           (Configuration panel)
└── ModelListDialog         (Model library management)

EngineManager               (Engine selection & management)
├── LlamaCppEngine (GGUF, Vulkan/OpenCL/CPU)
├── MnnEngine (MNN, CPU-optimized)
└── LiteRtEngine (TFLite/LiteRT-LM, CPU/GPU/NPU)

InferenceEngine (interface)
├── loadModel()             Load model from file path
├── executeInference()      Run inference (non-blocking)
├── readPartialStream()     Read streaming tokens
├── readTokenStream()       Read complete response
├── abortInference()        Cancel current generation
├── getTokensGenerated()    Get token count
├── getKvCacheUsage()       Get memory usage percentage
└── benchmark()             Performance measurement

DeviceUtils                (CPU/GPU/RAM detection)
SettingsManager            (SharedPreferences persistence)
ModelManager               (Model library with metadata)
```

## Building

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Android API ≥ 26 (Oreo)
- arm64-v8a device
- 4GB+ RAM (8GB+ recommended for 7B models)
- Model files accessible via `content://` URI (file picker)

## Supported Models

### Text LLMs
| Model | Format | Engine |
|-------|--------|--------|
| Qwen3 / Qwen3.5 | GGUF / MNN | llama.cpp / MNN |
| Llama 3.2 | GGUF / MNN | llama.cpp / MNN |
| DeepSeek R1 | GGUF / MNN | llama.cpp / MNN |
| Phi-4 | GGUF / LiteRT-LM | llama.cpp / LiteRT-LM |
| Gemma 4 | GGUF | llama.cpp |

### Supported File Formats
- `.gguf` — GGML Universal Format (llama.cpp)
- `.mnn` — MNN model directory with config.json
- `.tflite` — TensorFlow Lite
- `.litertlm` — LiteRT-LM bundle

## Settings

| Setting | Range | Description |
|---------|-------|-------------|
| Context Window | 512-32768 | KV cache size |
| Max Tokens | 64-8192 | Maximum generation tokens |
| GPU Layers | 0-999 | Layers to offload to GPU |
| Threads | 0-16 | CPU threads (0 = auto) |
| Temperature | 0-2 | Sampling temperature |
| Top-P | 0-1 | Nucleus sampling threshold |
| Min-P | 0-1 | Minimum probability threshold |
| Repeat Penalty | 1.0+ | Penalty for repeated tokens |
| Frequency Penalty | 0+ | Penalty for frequent tokens |
| Presence Penalty | 0+ | Penalty for presence of tokens |
| Batch Size | 512-8192 | Batch size for prefill |
| System Prompt | text | System instruction for the model |

## License

This app is licensed under Apache 2.0.

All dependencies are open source:
- llama.cpp: MIT
- ggml: MIT  
- MNN: Apache 2.0
- LiteRT-LM: Apache 2.0
- Compose/AndroidX: Apache 2.0

You can sell this app commercially. See `LicenseNotices.kt` for full license texts.

## Version History

### v7.0-modern
- Modern Material 3 design polish
- Fixed duplicate color palette definitions
- Updated compileSdk/NDK for compatibility
- Improved MNN bridge with proper streaming
- Better error handling in all engines
- Removed unused stubs (EmbeddingHelper, MultimodalHelper)