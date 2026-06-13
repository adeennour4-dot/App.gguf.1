# PROJECT MAP — GGUF-ZeroCopy v7

## [TECH_STACK]

| Layer | Technology | Version | Status |
|-------|-----------|---------|--------|
| Language | Kotlin | 2.0.x | ✅ |
| Android SDK | compileSdk | 35 | ✅ |
| Min SDK | minSdk | 26 | ✅ |
| Build | Gradle + AGP | 8.x | ✅ |
| UI | Jetpack Compose + Material3 | BOM 2026.05.00 | ✅ |
| Coroutines | kotlinx-coroutines | 1.10.1 | ✅ |
| **Engine: llama.cpp** | ggml-org/llama.cpp | **b9474** (pinned) | ✅ |
| | GGML_VULKAN | OFF (NDK lacks Vulkan headers) | ⚠️ |
| | GGML_OPENCL | OFF | ✅ |
| | ARM arch | armv8.6-a+dotprod+i8mm+fp16 | ✅ |
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
┌─────────▼─────┐ ┌──────▼──────┐ ┌─────▼──────────┐
│  ipc-bridge   │ │ mnn-bridge  │ │ LiteRT-LM AAR  │
│  (C++ JNI)    │ │ (C++ JNI)   │ │ (via reflection)│
│  llama.cpp    │ │ MNN-LLM     │ │                  │
│  ggml-cpu     │ │ libMNN.so   │ │                  │
└───────────────┘ └─────────────┘ └──────────────────┘

Streaming: JNI callback per token (push-based, no polling overhead)
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
- **LiteRT-LM runtime**: Uses reflection to load classes. May fail if AAR classes renamed.
- **MNN streaming**: Currently returns full response after completion (not streaming). Needs callback-based implementation.
- **OpenCL backend**: Disabled. Add FetchContent for OpenCL-Headers + ICD-Loader for Adreno GPU support.

### Medium Priority
- **n_batch persistence**: Stored in SettingsManager (fixed)
- **maxNewTokens cap**: Clamped relative to nCtx (fixed in v7)

### Low Priority
- **MNN benchmark**: Implemented but returns placeholder values
- **Chat export**: Only llama.cpp implemented
- **Vision support**: Stubs exist but not wired to UI

## [VERIFIABLE GOALS]

- [x] CI compiles llama.cpp (b9474, CPU backend)
- [x] CI compiles MNN (3.5.0, LLM engine ON)
- [x] CI compiles LiteRT-LM reflection stub
- [x] Polling delay 80ms→30ms
- [x] "Processing..." indicator during prompt eval
- [x] n_batch 512→2048, n_threads = all cores
- [x] Default n_ctx floor 2048
- [x] Duplicate color palette removed
- [ ] `loadModel()` succeeds for `.litertlm` with real model file
- [ ] `loadModel()` succeeds for `.mnn` with real model directory
- [ ] `loadModel()` succeeds for `.gguf` with real model file