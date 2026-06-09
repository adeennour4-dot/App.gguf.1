# GGUF ZeroCopy v5 PRO — Project Map

## [TECH_STACK]

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Kotlin + C++ (JNI) | Kotlin 2.3+ |
| UI | Jetpack Compose + Material 3 | compose-bom:2026.05.00 |
| Build | Gradle + AGP | 9.5.1 / 9.1.1 |
| NDK | Android NDK | r29 (29.0.14206865) |
| Inference | llama.cpp | b9542 (June 2026) |
| GPU | Vulkan (GGML_VULKAN) | 1.3 |
| IPC | ASharedMemory (Ashmem) ring buffer | 512 KB |
| KV Cache | Q8_0 quantization | type_k + type_v |
| Optimization | ThinLTO + DotProd + ARMv8.4a | march=armv8.4a+dotprod |

## [ARCHITECTURE]

```
┌─────────────────────────────────────────────────────────┐
│  Kotlin / Compose UI (MainActivity.kt)                  │
│  ┌──────────┐ ┌──────────┐ ┌─────────┐ ┌─────────────┐ │
│  │  Chat    │ │  Models  │ │Settings │ │  Bench/Info │ │
│  │  Screen  │ │  Screen  │ │ Screen  │ │  Screens    │ │
│  └────┬─────┘ └────┬─────┘ └────┬────┘ └──────┬──────┘ │
│       │            │            │              │        │
│  ┌────▼────────────▼────────────▼──────────────▼──────┐ │
│  │              EngineCore (Singleton)                 │ │
│  │  JNI bridge · Config · Shared memory read           │ │
│  └─────────────────────┬──────────────────────────────┘ │
└────────────────────────┼────────────────────────────────┘
                         │ JNI
┌────────────────────────▼────────────────────────────────┐
│  C++ ipc-bridge (llama.cpp)                             │
│  ┌───────────┐  ┌──────────┐  ┌──────────────────────┐ │
│  │ Model     │  │ Context  │  │ Shared Buffer Ring   │ │
│  │ Loading   │  │ +Sampler │  │ (write_pos, flags,   │ │
│  │           │  │          │  │  tokens, tps, data)   │ │
│  └───────────┘  └──────────┘  └──────────────────────┘ │
│  ┌─────────────────────────────────────────────────────┐│
│  │ llama.cpp b9542 + GGML Vulkan + Q8_0 KV-Cache       ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
```

## [SYSTEM_FLOW]

1. **Boot** → `EngineCore.bootZeroCopyEngine()` → `ASharedMemory_create()` → mmap → ByteBuffer
2. **Load Model** → SAF file picker → copy to internal storage → `loadGgufModelNative()` → llama_model_load_from_file → llama_init_from_model
3. **Inference** → input prompt → `executeZeroCopyInference()` → tokenize → eval → generation loop → write tokens to shared buffer → set FLAG_DONE
4. **Stream** → Kotlin polls every 80ms: `readPartialStream()` → updates `streamedText` → Compose recomposition
5. **Done** → `isInferenceDone()` → `readTokenStream()` → add to `chatHistory` → display with TPS/token metadata
6. **Config** → Settings screen → `setNativeConfig()` → stored as globals → applied on next inference
7. **Abort** → `abortInferenceNative()` → `g_abort = true` → generation loop breaks
8. **Reset** → `resetContextNative()` → `llama_kv_cache_seq_rm()` → clear shared buffer
9. **Benchmark** → `benchmarkNative(512, 128)` → timed PP + TG → JSON result

## [FILES]

| File | Purpose | Status |
|------|---------|--------|
| `app/src/main/java/com/gguf/ipc/EngineCore.kt` | JNI bridge singleton, shared memory reader, config data classes | ✅ Fixed |
| `app/src/main/java/com/gguf/ipc/MainActivity.kt` | Compose UI — 5 screens (Chat, Models, Settings, Info, Bench) | ✅ Fixed |
| `app/src/main/cpp/ipc-bridge.cpp` | JNI native — model loading, inference, config, benchmark | ✅ Fixed |
| `app/src/main/cpp/CMakeLists.txt` | CMake build — fetches llama.cpp b9542 | ✅ OK |
| `app/src/main/AndroidManifest.xml` | Permissions, Vulkan features, activity | ✅ Updated |
| `app/build.gradle.kts` | AGP 9.1.1, NDK r29, Compose BOM 2026.05, ThinLTO | ✅ OK |
| `build.gradle.kts` | Root project plugins | ✅ OK |
| `settings.gradle.kts` | Project settings | ✅ OK |
| `res/values/strings.xml` | App name | ✅ Updated |
| `res/values/themes.xml` | Dark theme | ✅ Updated |
| `res/values/colors.xml` | Color palette | ✅ Updated |
| `res/drawable/ic_launcher_*.xml` | Launcher icons | ✅ OK |
| `res/mipmap-anydpi-v26/ic_launcher*.xml` | Adaptive icons | ✅ OK |

## [ORPHANS & PENDING]

| Item | Status | Notes |
|------|--------|-------|
| Regex: `EngineCore\.Config` | ✅ Fixed | Added Config data class |
| Regex: `EngineCore\.RepeatPenaltyConfig` | ✅ Fixed | Added data class |
| Regex: `EngineCore\.bootZeroCopyEngine()` | ✅ Fixed | Wrapper around boot() |
| Regex: `EngineCore\.loadModel()` | ✅ Fixed | Calls loadGgufModelNative() |
| Regex: `EngineCore\.setEngineConfig()` | ✅ Fixed | Calls setNativeConfig() |
| Regex: `EngineCore\.setSystemPromptNative()` | ✅ Fixed | JNI implemented |
| Regex: `EngineCore\.setRepeatPenalty()` | ✅ Fixed | JNI implemented |
| Regex: `EngineCore\.readPartialStream()` | ✅ Fixed | Pure Kotlin from shared mem |
| Regex: `EngineCore\.getTokensGenerated()` | ✅ Fixed | Reads from buffer header |
| Regex: `EngineCore\.isInferenceDone()` | ✅ Fixed | Reads flag from buffer |
| Regex: `EngineCore\.readTokenStream()` | ✅ Fixed | Pure Kotlin from shared mem |
| Regex: `EngineCore\.benchmarkNative()` | ✅ Fixed | JNI implemented |
| Regex: `EngineCore\.getModelInfoNative()` | ✅ Fixed | JNI returns JSON |
| Regex: `EngineCore\.abortInferenceNative()` | ✅ Fixed | Sets atomic flag |
| Regex: `EngineCore\.resetContextNative()` | ✅ Fixed | Clears KV cache + buffer |
| llvm-mca / DotProd regression | 🔲 Needed | Verify on physical device |
| llama.cpp API compat (b9542) | 🔲 Verify | If build errors, adjust API calls |
| Chat persistence (save/load) | 🔲 Future | Simple JSON file-based |
| RAG / document ingestion | 🔲 Future | Toolneuron-inspired |
| TTS / STT | 🔲 Future | sherpa-onnx integration |
| Plugin system | 🔲 Future | Sandboxed plugin runtime |
| HuggingFace model browser | 🔲 Future | In-app HF model store |
| Encryption (AES-256-GCM) | 🔲 Future | Android KeyStore-backed |
