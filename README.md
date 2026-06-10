# GGUF ZeroCopy v6

Android LLM inference app with multi-engine support (llama.cpp, MNN, LiteRT-LM) and multimodal capabilities.

## What's New in v6

### Multi-Engine Support
| Engine | Format | License | Best For |
|--------|--------|---------|----------|
| **llama.cpp** | .gguf | MIT | GPU acceleration (Vulkan/OpenCL), widest compatibility |
| **MNN** | .mnn | Apache 2.0 | CPU-optimized (8.6x faster than llama.cpp CPU) |
| **LiteRT-LM** | .tflite/.litertlm | Apache 2.0 | Google models, NPU access |

### Performance Optimizations (All Open Source)
- **Big core pinning** — `sched_setaffinity()` to ARM big cores for maximum single-thread performance
- **Process priority boost** — `setpriority(PRIO_PROCESS, 0, -20)` for maximum throughput
- **RAM locking** — `mlockall()` to prevent page faults during inference
- **ThinLTO compilation** — `-O3 -flto=thin` for faster builds and better optimization
- **ARM instruction set** — `-march=armv8.4a+dotprod+crc` for best ARM performance
- **No stack protector** — `-fno-stack-protector` (safe for inference workload)
- **OpenCL for Adreno** — Qualcomm-contributed GPU backend (579 t/s prefill on 1.5B)
- **Vulkan for Mali/Xclipse** — GPU acceleration for ARM and Samsung GPUs

### Device-Aware Auto-Configuration
- Auto-detects CPU cores (big.LITTLE topology)
- Auto-detects SoC (Snapdragon/Exynos/MediaTek/Tensor)
- Suggests optimal GPU layers (99 for Snapdragon OpenCL, 0 for others)
- Suggests optimal thread count based on big cores
- Auto-sizes context window based on available RAM
- Refuses to load model if insufficient RAM

### Multimodal Support
- **Vision**: LLaVA, Gemma 3 Vision, Qwen3-VL, Moondream 2 (via libmtmd)
- **Audio**: whisper.cpp, Qwen2-Audio, Qwen2.5-Omni
- **Embeddings**: nomic-embed-text, all-minilm, Qwen3-Embedding, BGE-M3
- **Documents**: PDF, text, markdown, code files

### UI Improvements
- Material 3 dark theme with bottom navigation
- Multi-session chat with persistence
- Settings persistence (survives restarts)
- New "ZC" monogram app icon
- Supported formats notice in UI
- License notices screen

### Bug Fixes
- Restored context shifting for long conversations
- Restored chat history with llama_chat_apply_template
- Restored full sampler chain (top_p, penalties)
- Fixed token limit (uses config, not hardcoded)
- Added settings persistence
- Added chat session persistence
- Added RAM check before model loading

## Architecture

```
EngineManager
├── LlamaCppEngine (GGUF, Vulkan/OpenCL/CPU)
├── MnnEngine (MNN, CPU-optimized)
└── LiteRtEngine (TFLite/LiteRT-LM, CPU/GPU/NPU)

InferenceEngine (interface)
├── loadModel()
├── executeInference()
├── readPartialStream()
├── getModelInfo()
├── benchmark()
└── ...

DeviceUtils (CPU/GPU/RAM detection)
SettingsManager (SharedPreferences persistence)
ChatManager (JSON file persistence)
MultimodalHelper (image/audio/document handling)
EmbeddingHelper (embedding model support)
```

## Supported Models

### Text LLMs
| Model | Format | Engine |
|-------|--------|--------|
| Qwen3 / Qwen3.5 | GGUF / MNN | llama.cpp / MNN |
| Gemma 4 | GGUF / LiteRT-LM | llama.cpp / LiteRT-LM |
| Llama 3.2 | GGUF / MNN | llama.cpp / MNN |
| DeepSeek R1 | GGUF / MNN | llama.cpp / MNN |
| Phi-4 | GGUF / LiteRT-LM | llama.cpp / LiteRT-LM |

### Vision Models
| Model | Format | Engine |
|-------|--------|--------|
| LLaVA 1.5/1.6 | GGUF + mmproj | llama.cpp |
| Gemma 3 Vision | GGUF + mmproj | llama.cpp |
| Qwen3-VL | GGUF / MNN | llama.cpp / MNN |
| Moondream 2 | GGUF + mmproj | llama.cpp |

### Audio Models
| Model | Format | Engine |
|-------|--------|--------|
| whisper.cpp | GGML | whisper.cpp |
| Qwen2-Audio | MNN | MNN |

### Embedding Models
| Model | Size | Dims | Format |
|-------|------|------|--------|
| all-minilm-L6-v2 | 46MB | 384 | GGUF |
| nomic-embed-text v1.5 | 274MB | 768 | GGUF |
| Qwen3-Embedding-0.6B | 400MB | 4096 | GGUF |
| BGE-M3 | 1.2GB | 1024 | GGUF |

## Building

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Android API ≥ 27 (Oreo)
- arm64-v8a device
- 4GB+ RAM (8GB+ recommended for 7B models)
- Model files accessible via `content://` URI

## License

This app is licensed under Apache 2.0.

All dependencies are open source:
- llama.cpp: MIT
- ggml: MIT
- MNN: Apache 2.0
- LiteRT-LM: Apache 2.0
- whisper.cpp: MIT
- clip.cpp: MIT
- Compose/AndroidX: Apache 2.0

You can sell this app commercially. See LicenseNotices.kt for full license texts.
