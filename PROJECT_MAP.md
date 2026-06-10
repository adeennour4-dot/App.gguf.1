# PROJECT MAP — GGUF-ZeroCopy v6

## [TECH_STACK]

| Layer | Technology | Version | Status |
|-------|-----------|---------|--------|
| Language | Kotlin | 2.1.x | ✅ |
| Android SDK | compileSdk | 35 | ✅ |
| Min SDK | minSdk | 26 | ✅ |
| Build | Gradle + AGP | 8.x / 8.10.0 | ✅ |
| UI | Jetpack Compose + Material3 | BOM 2026.05.00 | ✅ |
| Coroutines | kotlinx-coroutines | 1.10.1 | ✅ |
| **Engine: llama.cpp** | ggml-org/llama.cpp | **b9474** (pinned Jun 2 2026) | ✅ |
| | GGML_VULKAN | ON (if SPIRV-Headers found) | ✅ |
| | GGML_CPU_KLEIDIAI | OFF (b9474 kernel mismatch) | ⚠️ |
| | ARM arch | armv8.6-a+dotprod+i8mm+fp16 | ✅ |
| | GGML_OPENCL | OFF | ✅ |
| **Engine: MNN** | alibaba/MNN | **3.5.0** (pinned) | ✅ |
| **Engine: LiteRT-LM** | com.google.ai.edge.litertlm | **latest.release** | ✅ |
| CI | GitHub Actions | ubuntu-24.04 + NDK r27c | ✅ |

## [SYSTEM_FLOW]

```
User opens app → WelcomeScreen (no model loaded)
                    ↓ [tap "Load Model"]
              File picker (ACTION_OPEN_DOCUMENT)
                    ↓ [select .gguf / .mnn / .tflite / .litertlm]
              copyUriToFiles() → app-internal storage
                    ↓
              EngineManager.getEngineForFormat(path)
                    ↓
              setConfig() + loadModel()
                    ↓
              ChatScreen ← modelLoaded = true
                    ↓ [type message → tap send]
              executeInference(prompt) on Dispatchers.IO
                    ↓ (polling loop, delay 30ms)
              readPartialStream() → streamedText
                    ↓ [isInferenceDone]
              readTokenStream() → chat.add(ChatMessage)
```

## [ARCHITECTURE]

```
┌──────────────────────────────────────────────────────┐
│                    MainActivity.kt                    │
│  ┌─────────────┐  ┌──────────┐  ┌─────────────────┐  │
│  │ WelcomeScreen│  │ ChatList │  │ SettingsSheet   │  │
│  └─────────────┘  └──────────┘  └─────────────────┘  │
└───────────────────────┬──────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────┐
│  InferenceEngine (interface)                         │
│  ┌──────────────┐ ┌──────────┐ ┌──────────────────┐ │
│  │LlamaCppEngine│ │MnnEngine │ │ LiteRtEngine     │ │
│  │ (GGUF)       │ │ (.mnn)   │ │ (.tflite/.litertlm)│ │
│  └──────┬───────┘ └────┬─────┘ └────────┬─────────┘ │
└─────────┼──────────────┼────────────────┼────────────┘
          │              │                │
┌─────────▼─────┐ ┌──────▼──────┐ ┌──────▼──────────┐
│  ipc-bridge   │ │ mnn-bridge  │ │ LiteRT-LM AAR   │
│  (C++ JNI)    │ │ (C++ JNI)   │ │ (reflection)    │
│  llama.cpp    │ │ MNN-LLM     │ │ litert-lm-native │
│  ggml-cpu     │ │ libMNN.so   │ │ (native .so)    │
│  ggml-vulkan  │ │             │ │                  │
└───────────────┘ └─────────────┘ └──────────────────┘

Shared Memory (ashmem): ipc-bridge ↔ Kotlin polling
Ring buffer: write_pos, flags, tokens_generated, token_stream[512KB]
```

## [ENGINE CONFIG CHAIN]

```
SettingsManager (prefs) → InferenceEngine.Config
                               ↓
LlamaCppEngine.setConfig() → EngineCore.Config → JNI → C++ EngineConfig
                                                    g_cfg.n_ctx, n_batch, n_threads...
                                                    ↓
                                              loadGgufModelNative()
                                                    ↓
                                              llama_context_params
                                              n_threads_batch = all_cores
```

## [ORPHANS & PENDING]

### High Priority
- **LiteRT-LM runtime**: Reflection code loads classes & native lib, but `engine.initialize()` may still fail for Gemma 4 models if speculative decoding isn't properly enabled or native lib isn't bundled
- **MNN model path**: `Llm::createLLM(path)` expects a **directory** with `config.json`, not a single `.mnn` file — need to verify mnn-bridge.cpp handles this

### Medium Priority
- **OpenCL backend**: Disabled (NDK lacks OpenCL headers). Could add FetchContent for OpenCL-Headers + ICD-Loader for broader Adreno GPU support
- **n_batch persistence**: Not stored in SettingsManager (hardcoded default 2048)
- **maxNewTokens cap**: Not clamped relative to nCtx (e.g., nCtx=2048 with maxNewTokens=4096 is impossible)

### Low Priority
- **Benchmark**: Only llama.cpp has benchmarkNative; MNN and LiteRT-LM return placeholders
- **Chat export**: Only llama.cpp implemented
- **EmbeddingHelper/MultimodalHelper**: Present but unused stubs
- **No git repo**: No version history

## [VERIFIABLE GOALS]

- [x] CI compiles llama.cpp (b9474, KleidiAI, Vulkan, ARMv8.6-a)
- [x] CI compiles MNN (3.5.0, LLM engine ON)
- [x] CI compiles LiteRT-LM reflection stub
- [x] Polling delay 80ms→30ms
- [x] "Processing..." indicator during prompt eval
- [x] n_batch 512→2048, n_threads_batch = all_cores
- [x] Default n_ctx floor 2048 (fix 128 clamp)
- [ ] `loadModel()` succeeds for `.litertlm` with real model file
- [ ] `loadModel()` succeeds for `.mnn` with real model directory
- [ ] `loadModel()` succeeds for `.gguf` with real model file
