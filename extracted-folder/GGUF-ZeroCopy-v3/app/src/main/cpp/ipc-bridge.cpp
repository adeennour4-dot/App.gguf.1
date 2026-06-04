/**
 * ipc-bridge.cpp  —  GGUF ZeroCopy Engine v3
 *
 * What changed vs v2:
 *   - llama.cpp b5576: supports Qwen3/3.5, Gemma 4 (incl. E4B MoE), ZAYA-1-8B
 *   - llama_chat_apply_template() used for all chat formatting
 *   - n_ctx configurable at runtime (default 8192, supports up to 32768)
 *   - MAX_NEW_TOKENS raised to 4096
 *   - TOKEN_STREAM_SIZE raised to 256 KB
 *   - System prompt supported via setSystemPromptNative()
 *   - Context window, temperature, top-p, min-p, seed all configurable at runtime
 *   - Conversation history reset via resetContextNative()
 *   - KV-cache context shift when context fills (no hard cutoff)
 */

#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "GGUF_Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Shared ring buffer layout (must match Kotlin EngineCore.kt constants)
//
//   Offset 0 : uint32_t  write_pos       — bytes written so far
//   Offset 4 : uint32_t  flags           — bit 0 = inference done
//   Offset 8 : char      token_stream[]  — UTF-8 output bytes
// ---------------------------------------------------------------------------
static constexpr size_t TOKEN_STREAM_SIZE = 262144;   // 256 KB — enough for long outputs

struct SharedRingBuffer {
    volatile uint32_t write_pos;
    volatile uint32_t flags;
    char token_stream[TOKEN_STREAM_SIZE];
};
static_assert(sizeof(SharedRingBuffer) == 8 + TOKEN_STREAM_SIZE, "Layout mismatch");

// ---------------------------------------------------------------------------
// Runtime configuration (set by Kotlin before loading model or before inference)
// ---------------------------------------------------------------------------
struct EngineConfig {
    int   n_ctx         = 8192;   // context window; use 4096 for tight-memory devices
    int   n_batch       = 512;
    int   n_threads     = 4;
    int   n_gpu_layers  = 99;     // 99 = all layers on GPU; set 0 for CPU-only
    int   max_new_tokens= 4096;
    float temperature   = 0.7f;
    float top_p         = 0.9f;
    float min_p         = 0.05f;
    uint32_t seed       = LLAMA_DEFAULT_SEED;
    // System prompt — set once per model load; applied via chat template
    std::string system_prompt =
        "You are a helpful, concise assistant running on-device. "
        "Respond clearly and directly.";
};

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------
static SharedRingBuffer* g_buffer   = nullptr;
static int               g_shm_fd  = -1;
static llama_model*      g_model   = nullptr;
static llama_context*    g_ctx     = nullptr;
static llama_sampler*    g_sampler = nullptr;
static EngineConfig      g_cfg;

// Conversation history for multi-turn (kept between calls until reset)
// Each entry is a {role, content} pair rendered by chat template.
struct Message { std::string role; std::string content; };
static std::vector<Message> g_history;

// ---------------------------------------------------------------------------
// Helper: rebuild the sampler chain from current g_cfg
// ---------------------------------------------------------------------------
static void rebuild_sampler() {
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_cfg.min_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(g_cfg.top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_cfg.temperature));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(g_cfg.seed));
}

// ---------------------------------------------------------------------------
// Helper: apply the model's built-in chat template to the current history.
// Returns the formatted prompt string ready for tokenisation.
// Falls back to simple "User: … Assistant:" if the model has no template.
// ---------------------------------------------------------------------------
static std::string apply_chat_template(const std::string& new_user_message) {
    if (!g_model) return new_user_message;

    // Build a temporary message list with the new user message appended
    std::vector<llama_chat_message> msgs;
    msgs.reserve(g_history.size() + 2);

    // System prompt as first message (most models expect this)
    if (!g_cfg.system_prompt.empty()) {
        msgs.push_back({"system", g_cfg.system_prompt.c_str()});
    }
    for (const auto& m : g_history) {
        msgs.push_back({m.role.c_str(), m.content.c_str()});
    }
    msgs.push_back({"user", new_user_message.c_str()});

    // Let llama.cpp render the template
    std::vector<char> buf(32768);
    int written = llama_chat_apply_template(
        g_model,
        nullptr,           // use the model's built-in template
        msgs.data(),
        (int)msgs.size(),
        true,              // add_ass = add the assistant turn start token
        buf.data(),
        (int)buf.size()
    );
    if (written < 0) {
        // Template rendering failed — fall back to raw message
        LOGE("llama_chat_apply_template failed (%d), using raw prompt", written);
        return new_user_message;
    }
    if (written >= (int)buf.size()) {
        buf.resize(written + 1);
        llama_chat_apply_template(g_model, nullptr, msgs.data(), (int)msgs.size(),
                                  true, buf.data(), (int)buf.size());
    }
    return std::string(buf.data(), written);
}

// ===========================================================================
// JNI: initializeSharedMemoryNative
// ===========================================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_initializeSharedMemoryNative(JNIEnv*, jobject) {
    if (g_shm_fd >= 0) return g_shm_fd;

    const size_t total_size = sizeof(SharedRingBuffer);
    g_shm_fd = ASharedMemory_create("llama_ipc_ring", total_size);
    if (g_shm_fd < 0) { LOGE("ASharedMemory_create failed"); return -1; }

    ASharedMemory_setProt(g_shm_fd, PROT_READ | PROT_WRITE);
    g_buffer = static_cast<SharedRingBuffer*>(
        mmap(nullptr, total_size, PROT_READ | PROT_WRITE, MAP_SHARED, g_shm_fd, 0));

    if (g_buffer == MAP_FAILED) {
        LOGE("mmap failed"); close(g_shm_fd); g_shm_fd = -1; g_buffer = nullptr; return -1;
    }
    memset(g_buffer, 0, total_size);
    LOGI("Shared ring buffer mapped: fd=%d size=%zu", g_shm_fd, total_size);
    return g_shm_fd;
}

// ===========================================================================
// JNI: setEngineConfigNative
// Call before loadGgufModelNative to customise context size etc.
// ===========================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setEngineConfigNative(
        JNIEnv* env, jobject,
        jint n_ctx, jint max_new_tokens,
        jfloat temperature, jfloat top_p, jfloat min_p,
        jint n_gpu_layers, jint seed)
{
    g_cfg.n_ctx          = (int)n_ctx;
    g_cfg.max_new_tokens = (int)max_new_tokens;
    g_cfg.temperature    = (float)temperature;
    g_cfg.top_p          = (float)top_p;
    g_cfg.min_p          = (float)min_p;
    g_cfg.n_gpu_layers   = (int)n_gpu_layers;
    g_cfg.seed           = (uint32_t)seed;
    LOGI("Config updated: n_ctx=%d max_new=%d temp=%.2f top_p=%.2f min_p=%.2f gpu_layers=%d",
         g_cfg.n_ctx, g_cfg.max_new_tokens, g_cfg.temperature,
         g_cfg.top_p, g_cfg.min_p, g_cfg.n_gpu_layers);
}

// ===========================================================================
// JNI: setSystemPromptNative
// ===========================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setSystemPromptNative(JNIEnv* env, jobject, jstring j_prompt) {
    const char* p = env->GetStringUTFChars(j_prompt, nullptr);
    if (p) { g_cfg.system_prompt = p; env->ReleaseStringUTFChars(j_prompt, p); }
    LOGI("System prompt updated (%zu chars)", g_cfg.system_prompt.size());
}

// ===========================================================================
// JNI: resetContextNative — clears conversation history + KV cache
// ===========================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_resetContextNative(JNIEnv*, jobject) {
    g_history.clear();
    if (g_ctx) llama_kv_cache_clear(g_ctx);
    LOGI("Context reset");
}

// ===========================================================================
// JNI: loadGgufModelNative
// ===========================================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring file_path) {
    // Free previous model
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_history.clear();

    const char* path = env->GetStringUTFChars(file_path, nullptr);
    if (!path) return JNI_FALSE;
    LOGI("Loading model: %s", path);

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = g_cfg.n_gpu_layers;
    mp.use_mmap     = true;

    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(file_path, path);
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx               = g_cfg.n_ctx;
    cp.n_batch             = g_cfg.n_batch;
    cp.n_threads           = g_cfg.n_threads;
    cp.n_threads_batch     = g_cfg.n_threads;
    cp.flash_attn          = true;   // enable flash attention — faster, less VRAM

    g_ctx = llama_new_context_with_model(g_model, cp);
    if (!g_ctx) {
        LOGE("Failed to create context — try reducing n_ctx");
        llama_model_free(g_model); g_model = nullptr;
        return JNI_FALSE;
    }

    rebuild_sampler();
    LOGI("Model loaded OK. n_ctx=%d flash_attn=true gpu_layers=%d",
         g_cfg.n_ctx, g_cfg.n_gpu_layers);
    return JNI_TRUE;
}

// ===========================================================================
// JNI: executeZeroCopyInference
// One user message in → one assistant response streamed to shared memory.
// Adds the exchange to g_history for multi-turn continuity.
// ===========================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_executeZeroCopyInference(JNIEnv* env, jobject, jstring j_prompt) {
    if (!g_buffer || !g_ctx || !g_model || !g_sampler) {
        LOGE("Engine not initialized"); return;
    }

    // Reset output buffer
    memset(g_buffer->token_stream, 0, TOKEN_STREAM_SIZE);
    g_buffer->write_pos = 0;
    g_buffer->flags     = 0;

    const char* raw_prompt = env->GetStringUTFChars(j_prompt, nullptr);
    if (!raw_prompt) return;
    std::string user_msg(raw_prompt);
    env->ReleaseStringUTFChars(j_prompt, raw_prompt);

    // Apply the model's chat template (handles Qwen3 <|im_start|>, Gemma <start_of_turn>, etc.)
    std::string formatted = apply_chat_template(user_msg);
    LOGI("Formatted prompt length: %zu chars", formatted.size());

    // Tokenise
    int n_ctx_avail = llama_n_ctx(g_ctx);
    std::vector<llama_token> tokens(n_ctx_avail);
    int n_tokens = llama_tokenize(
        g_model,
        formatted.c_str(), (int32_t)formatted.size(),
        tokens.data(), (int32_t)tokens.size(),
        true, true
    );
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(g_model, formatted.c_str(), (int32_t)formatted.size(),
                                  tokens.data(), (int32_t)tokens.size(), true, true);
    }
    if (n_tokens <= 0) { LOGE("Tokenization returned %d", n_tokens); g_buffer->flags=1; return; }
    tokens.resize(n_tokens);
    LOGI("Prompt tokens: %d  context: %d", n_tokens, n_ctx_avail);

    // Context-shift if the prompt is too long (keep most recent tokens)
    if (n_tokens >= n_ctx_avail - g_cfg.max_new_tokens) {
        int keep = n_ctx_avail - g_cfg.max_new_tokens - 1;
        if (keep < 1) keep = 1;
        tokens.erase(tokens.begin(), tokens.begin() + (n_tokens - keep));
        n_tokens = (int)tokens.size();
        llama_kv_cache_clear(g_ctx);
        LOGI("Context shift: keeping last %d prompt tokens", n_tokens);
    }

    // Evaluate prompt batch
    llama_kv_cache_clear(g_ctx);
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode failed for prompt"); g_buffer->flags=1; return;
    }

    // Decode loop — write tokens into shared memory as they come
    std::string assistant_response;
    char piece_buf[256];

    for (int i = 0; i < g_cfg.max_new_tokens; ++i) {
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), tok)) {
            LOGI("EOS after %d tokens", i);
            break;
        }

        int piece_len = llama_token_to_piece(g_model, tok, piece_buf, (int)sizeof(piece_buf), 0, true);
        if (piece_len < 0) piece_len = 0;

        if (piece_len > 0) {
            // Write to shared ring buffer
            uint32_t pos = g_buffer->write_pos;
            if (pos + (uint32_t)piece_len < TOKEN_STREAM_SIZE - 1) {
                memcpy(g_buffer->token_stream + pos, piece_buf, piece_len);
                g_buffer->write_pos = pos + piece_len;
                assistant_response.append(piece_buf, piece_len);
            } else {
                LOGI("Ring buffer full at token %d", i);
                break;
            }
        }

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, next) != 0) { LOGE("decode failed at token %d", i); break; }
    }

    // Store in history for multi-turn
    g_history.push_back({"user",      user_msg});
    g_history.push_back({"assistant", assistant_response});

    // Trim history if it gets very long (keep last 20 exchanges = 40 messages)
    while (g_history.size() > 40) g_history.erase(g_history.begin(), g_history.begin() + 2);

    g_buffer->flags = 1;
    LOGI("Inference done. write_pos=%u history_turns=%zu", g_buffer->write_pos, g_history.size()/2);
}

// ===========================================================================
// JNI: getWritePosNative / isInferenceDoneNative  (unchanged)
// ===========================================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_getWritePosNative(JNIEnv*, jobject) {
    return g_buffer ? (jint)g_buffer->write_pos : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_isInferenceDoneNative(JNIEnv*, jobject) {
    if (!g_buffer) return JNI_TRUE;
    return (g_buffer->flags & 1) ? JNI_TRUE : JNI_FALSE;
}
