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
| | GGML_VULKAN | OFF | ⚠️ |
| | GGML_OPENCL | OFF | ✅ |
| | GGML_CPU_KLEIDIAI | ON | ✅ |
| | ARM arch | armv8.6-a+dotprod+i8mm+fp16 | ✅ |
| | Chunked prefill | ON (uBatch=512) | ✅ |
| | Thread pinning | Big cores only | ✅ |
| **Engine: MNN** | alibaba/MNN | **3.5.0** (pinned) | ✅ |
| **Engine: LiteRT-LM** | com.google.ai.edge.litertlm | **latest.release** | ✅ |
| CI | GitHub Actions | ubuntu-24.04 + NDK r28c | ✅ |

## [SYSTEM_FLOW]

```
User opens app → WelcomeScreen (no model loaded)
                    ↓ [tap "Load Model"]
              File picker (ACTION_OPEN_DOCUMENT)
                    ↓ [select .gguf / .mnn / .tflite / .litertlm]
              copyUriToFiles() → app-internal storage (models/)
                    ↓
              ModelManager.addModel() → Persist to SharedPreferences
                    ↓
              EngineManager.getEngineForFormat(path)
                    ↓
              setConfig() + loadModel()
                    ↓
              ChatScreen ← modelLoaded = true
                    ↓ [type message → tap send]
              executeInference(prompt) on Dispatchers.IO
                    ↓
              llama.cpp: Chunked prefill (512 tokens/batch)
                    ↓ (JNI callback per token)
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
│  (C++ JNI)    │ │ (C++ JNI)   │ │ (reflection)   │
│  llama.cpp    │ │ MNN-LLM     │ │                │
│  ggml-cpu     │ │ libMNN.so   │ │                │
│  ggml-kleidiai│ │             │ │                │
└───────────────┘ └─────────────┘ └──────────────────┘

Streaming: JNI callback per token (push-based, no polling overhead)
Shared memory: None (removed in v7)
```

## [ENGINE CONFIG CHAIN]

```
SettingsManager (prefs) → InferenceEngine.Config
                               ↓
LlamaCppEngine.setConfig() → EngineCore.Config → JNI → C++ EngineConfig
                                                    g_cfg.n_ctx, n_batch, n_ubatch=512
                                                    n_threads = big_core_count
                                                    ↓
                                              loadGgufModelNative()
                                                    ↓
                                              llama_context_params
                                              n_threads = big_core_count
                                              n_ubatch = 512 (chunked prefill)
```

## [KEY FIXES IN v7]

1. **Chunked prefill**: Prompt processed in 512-token chunks instead of one massive batch
2. **Thread pinning**: Uses only big cores, not all cores (avoids LITTLE core contention)
3. **Model persistence**: Models saved to SharedPreferences, survive app restart
4. **Settings clamping**: All values properly clamped to valid ranges
5. **MNN synchronous**: Fixed to run synchronously with proper result reading
6. **Duplicate resource fix**: Removed duplicate app_name from colors.xml
7. **JNI_EDETACH fix**: Corrected typo in mnn-bridge.cpp
8. **Palette dedup**: UiConstants.shared across all composables

## [ORPHANS & PENDING]

### High Priority
- **LiteRT-LM runtime**: Uses reflection, works only when AAR is properly bundled
- **Vision model support**: Stub exists but not wired to UI

### Medium Priority
- **Session management**: ChatManager exists but not integrated with UI
- **Model format validation**: No file validation before loading
- **Progress during model copy**: No progress percentage shown

### Low Priority
- **GPU acceleration**: Vulkan/OpenCL disabled (NDK lacks headers)
- **EmbeddingHelper/MultimodalHelper**: Removed (unused stubs)

## [VERIFIABLE GOALS]

- [x] CI compiles llama.cpp (b9474, KleidiAI, CPU backend)
- [x] CI compiles MNN (3.5.0, LLM engine ON)
- [x] Chunked prefill (uBatch=512)
- [x] Big-core-only thread pinning
- [x] Model persistence (SharedPreferences)
- [x] Settings value clamping
- [x] Duplicate resource fix
- [x] JNI_EDETACH fix
- [x] Palette deduplication (UiConstants)
- [ ] `loadModel()` succeeds for `.gguf` with real model file
- [ ] `loadModel()` succeeds for `.mnn` with real model directory
- [ ] `loadModel()` succeeds for `.litertlm` with real model file