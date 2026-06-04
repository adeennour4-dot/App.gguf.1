# GGUF ZeroCopy Engine — v3

A production-grade Android app for local GGUF model inference using:
- **llama.cpp b5576** — supports Qwen3/3.5, Gemma 4 (incl. E4B MoE), Zyphra ZAYA-1-8B, and all earlier models
- **Vulkan GPU offloading** (`GGML_VULKAN=ON`) for Adreno and Mali GPUs
- **Flash Attention** enabled by default for faster inference and lower memory use
- **ASharedMemory / Ashmem** zero-copy IPC between C++ engine and Kotlin UI
- **Chat template support** via `llama_chat_apply_template()` — automatically uses each model's built-in template (Qwen's `<|im_start|>`, Gemma's `<start_of_turn>`, etc.)
- **Multi-turn conversation** with automatic history management
- **Runtime settings panel**: context window, max tokens, temperature, top-p, min-p, GPU layers, system prompt
- **Context reset** button to start fresh without reloading the model
- **Jetpack Compose Material 3** UI with `<think>` token accordion for reasoning models

---

## What's new in v3 vs v2

| | v2 | v3 |
|---|---|---|
| llama.cpp tag | b5046 | **b5576** |
| Qwen3 / Qwen3.5 | ⚠ partial | ✅ full (chat template) |
| Gemma 4 / E4B | ❌ | ✅ |
| ZAYA-1-8B | ❌ | ✅ |
| Context window | 2048 (hardcoded) | **8192 default, up to 32768** |
| Max output tokens | 512 | **4096** |
| Token stream buffer | 64 KB | **256 KB** |
| Chat template | ❌ raw prompt | ✅ llama_chat_apply_template |
| System prompt | ❌ | ✅ runtime-configurable |
| Multi-turn history | ❌ | ✅ (last 20 exchanges) |
| Flash attention | ❌ | ✅ |
| Settings UI | ❌ | ✅ |

---

## Model compatibility

| Model | GGUF variant to use | Recommended n_ctx |
|---|---|---|
| Qwen3-8B-Instruct | Q4_K_M | 8192 |
| Qwen3.5-7B-Instruct | Q4_K_M | 8192 |
| Gemma-4-9B-IT | Q4_K_M | 8192 |
| Gemma-4-E4B-IT (MoE) | Q4_K_M | 8192 |
| Zyphra/ZAYA-1-8B | Q4_K_M | 8192 |
| Phi-4-mini-Instruct | Q4_K_M | 4096 |
| Llama-3.1-8B-Instruct | Q4_K_M | 8192 |

All models use automatic chat template detection — no manual prompt formatting needed.

---

## Settings explained

| Setting | What it does | Notes |
|---|---|---|
| **n_ctx** (Context Window) | Total tokens the model can see at once (prompt + history + output) | 8192 is safe for 6–8GB RAM devices. Use 4096 if you get OOM. **Requires model reload to change.** |
| **Max New Tokens** | Maximum tokens generated per turn | 4096 is plenty for most responses |
| **Temperature** | Randomness: 0 = deterministic, 1.0 = creative | 0.6–0.7 recommended for instruction models |
| **Top-P** | Nucleus sampling — trims improbable tokens | 0.9 is a safe default |
| **Min-P** | Removes tokens below this fraction of top probability | 0.05 keeps quality high |
| **GPU Layers** | How many transformer layers to run on the Vulkan GPU | 99 = all layers. Set 0 if Vulkan crashes. **Requires model reload.** |
| **System Prompt** | Text prepended as the "system" role in the chat template | Applied to every new conversation turn |

**Quick presets** set n_ctx, temperature, and system prompt to values tuned for:
- **Qwen3/ZAYA**: 0.6 temp — these models are strong with low temp
- **Gemma 4**: 0.7 temp — slightly more creative default
- **Reasoning**: 16K context + lower temp — for models with `<think>` reasoning chains

---

## How to build (GitHub Actions)

1. Push to GitHub.
2. Go to **Actions → Build GGUF ZeroCopy APK → Run workflow**.
3. Wait 15–25 minutes (first run downloads and compiles llama.cpp).
4. Download `GGUF-ZeroCopy-Vulkan-Engine-debug` artifact.
5. `adb install app-debug.apk`

---

## How to use

1. Tap **⚙ Settings**, configure, tap **Apply Settings**.
2. Tap **📂 Load .GGUF** and pick your model (Q4_K_M recommended for 8B models).
3. Type a prompt and tap **▶ Run**.
4. Tokens stream in real time. Tap **↺ Reset** to clear conversation history.
5. For reasoning models (ZAYA, Qwen3 thinking mode), the `<think>` block appears as a collapsible accordion.

---

## Known limitations

**Vulkan on Adreno**: Can be unstable on some driver versions. Remove `-DGGML_VULKAN=ON` from `app/build.gradle.kts` cmake args to fall back to optimized ARM NEON CPU inference.

**File copy overhead**: SAF URIs can't be passed to C++. The app copies the model to `cacheDir` first. A 4GB model takes ~30s on UFS3 storage and uses 4GB internal storage.

**n_ctx memory cost**: Each 1024 tokens of context uses ~200MB RAM for a 7B model (Q4_K_M, Vulkan). If the device is low on memory, reduce n_ctx to 4096.

**MoE models (Gemma E4B, Qwen3 MoE)**: Active-parameter count is low but total parameter count is high. Full offload (gpu_layers=99) requires enough VRAM for all expert weights. If OOM, try gpu_layers=20–30 for partial offload.

---

## Architecture

```
C++ ipc-bridge.cpp                  Kotlin EngineCore.kt
──────────────────                  ──────────────────────
ASharedMemory_create()  ──fd──▶    SharedMemory.fromFileDescriptor()
mmap(PROT_READ|WRITE)              .mapReadOnly()
                                            │
token_stream[write_pos++]          ByteBuffer.get() at HEADER_SIZE offset
g_buffer->flags = 1 (done)        poll isInferenceDoneNative() every 80ms
```

Chat template rendering:
```
User message → llama_chat_apply_template() → formatted prompt → llama_tokenize()
```

Sampler chain: `min_p(0.05) → top_p(0.9) → temperature(0.7) → dist(seed)`
