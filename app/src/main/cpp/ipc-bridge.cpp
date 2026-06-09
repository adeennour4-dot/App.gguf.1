#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <vector>
#include <cstring>
#include <chrono>
#include <android/log.h>
#include "llama.h"

#define TAG "GGUF_PRO_V5"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Shared memory layout ──
static constexpr size_t HEADER_SIZE = 16;
static constexpr size_t STREAM_SIZE = 524288;
static constexpr uint32_t FLAG_DONE   = 1;
static constexpr uint32_t FLAG_ACTIVE = 2;

struct SharedBuffer {
    volatile uint32_t write_pos;   // +0
    volatile uint32_t flags;       // +4
    volatile uint32_t tokens_gen;  // +8
    volatile uint32_t tps_scaled;  // +12 (tokens/sec * 100)
    char data[STREAM_SIZE - HEADER_SIZE];  // +16
};

// ── Global state ──
static SharedBuffer*     g_buf      = nullptr;
static llama_model*      g_model    = nullptr;
static llama_context*    g_ctx      = nullptr;
static llama_sampler*    g_sampler  = nullptr;
static std::atomic<bool> g_abort{false};
static bool              g_context_active = false;

// ── Config store ──
static int    g_n_ctx        = 8192;
static int    g_max_tokens   = 4096;
static float  g_temperature  = 0.7f;
static float  g_top_p        = 0.9f;
static float  g_min_p        = 0.05f;
static int    g_n_gpu_layers = 99;
static int    g_n_threads    = 4;
static int    g_seed         = -1;
static std::string g_system_prompt;
static float  g_repeat_penalty = 1.1f;
static float  g_freq_penalty   = 0.0f;
static float  g_pres_penalty   = 0.0f;

// ── Helpers ──
static void rebuild_sampler() {
    if (g_sampler) {
        // llama_sampler_free(g_sampler);  // depends on llama.cpp version
        g_sampler = nullptr;
    }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_min_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_temperature));
    // Note: top-p/k are baked into min-p behaviour here; if a dedicated
    // top-p sampler is wanted, add llama_sampler_init_top_p(g_top_p, 1).
}

static void cleanup_model() {
    g_context_active = false;
    if (g_sampler) { g_sampler = nullptr; }
    if (g_ctx)      { llama_free(g_ctx);     g_ctx    = nullptr; }
    if (g_model)    { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" {

// ═══════════════════════════════════════════════════════════════════
// Shared Memory
// ═══════════════════════════════════════════════════════════════════
JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_initializeSharedMemoryNative(JNIEnv*, jobject) {
    if (g_buf) {
        munmap((void*)g_buf, sizeof(SharedBuffer));
        g_buf = nullptr;
    }
    int fd = ASharedMemory_create("gguf_pro_shm", sizeof(SharedBuffer));
    if (fd < 0) return -1;

    void* mapped = mmap(nullptr, sizeof(SharedBuffer),
                        PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) { close(fd); return -1; }

    g_buf = (SharedBuffer*)mapped;
    memset(g_buf, 0, sizeof(SharedBuffer));
    return fd;
}

// ═══════════════════════════════════════════════════════════════════
// Config
// ═══════════════════════════════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setNativeConfig(
    JNIEnv*, jobject,
    jint nCtx, jint maxNewTokens, jfloat temperature, jfloat topP,
    jfloat minP, jint nGpuLayers, jint nThreads, jint seed
) {
    g_n_ctx        = nCtx;
    g_max_tokens   = maxNewTokens;
    g_temperature  = temperature;
    g_top_p        = topP;
    g_min_p        = minP;
    g_n_gpu_layers = nGpuLayers;
    g_n_threads    = nThreads;
    g_seed         = seed;
    LOGI("Config: ctx=%d max=%d temp=%.2f top_p=%.2f min_p=%.2f layers=%d threads=%d",
         nCtx, maxNewTokens, temperature, topP, minP, nGpuLayers, nThreads);
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setSystemPromptNative(JNIEnv* env, jobject, jstring prompt) {
    const char* str = env->GetStringUTFChars(prompt, nullptr);
    g_system_prompt = str ? str : "";
    env->ReleaseStringUTFChars(prompt, str);
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setRepeatPenaltyNative(
    JNIEnv*, jobject, jfloat rp, jfloat fp, jfloat pp
) {
    g_repeat_penalty = rp;
    g_freq_penalty   = fp;
    g_pres_penalty   = pp;
}

// ═══════════════════════════════════════════════════════════════════
// Model Loading
// ═══════════════════════════════════════════════════════════════════
JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);

    cleanup_model();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = g_n_gpu_layers;

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = g_n_ctx;
    cparams.type_k    = GGML_TYPE_Q8_0;
    cparams.type_v    = GGML_TYPE_Q8_0;
    cparams.n_threads = g_n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model); g_model = nullptr;
        return JNI_FALSE;
    }

    rebuild_sampler();
    g_context_active = true;
    LOGI("Model loaded: %s", filePath);
    return JNI_TRUE;
}

// ═══════════════════════════════════════════════════════════════════
// Inference
// ═══════════════════════════════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_executeZeroCopyInference(JNIEnv* env, jobject, jstring prompt) {
    if (!g_ctx || !g_buf || !g_model) return;

    const char* input = env->GetStringUTFChars(prompt, nullptr);
    if (!input) return;

    // Reset buffer
    g_buf->write_pos  = 0;
    g_buf->flags      = 0;
    g_buf->tokens_gen = 0;
    g_buf->tps_scaled = 0;
    g_abort = false;

    auto vocab = llama_model_get_vocab(g_model);

    // Build full prompt
    std::string full_prompt;
    if (!g_system_prompt.empty())
        full_prompt = g_system_prompt + "\n\nUser: " + input + "\n\nAssistant: ";
    else
        full_prompt = input;

    std::vector<llama_token> tokens(8192);
    int n_toks = llama_tokenize(
        vocab, full_prompt.data(), (int)full_prompt.size(),
        tokens.data(), (int)tokens.size(), true, false);
    if (n_toks < 0) {
        env->ReleaseStringUTFChars(prompt, input);
        return;
    }

    rebuild_sampler();

    // Eval prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_toks);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        env->ReleaseStringUTFChars(prompt, input);
        return;
    }

    g_buf->flags |= FLAG_ACTIVE;
    auto start_time = std::chrono::high_resolution_clock::now();

    // Generation loop
    char piece[256];
    int generated = 0;
    int limit = g_max_tokens < 2048 ? g_max_tokens : 2048;

    for (int i = 0; i < limit; i++) {
        if (g_abort) break;

        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        int n = llama_token_to_piece(vocab, tok, piece, (int)sizeof(piece), 0, false);
        if (n > 0) {
            uint32_t pos = g_buf->write_pos;
            if (pos + (uint32_t)n < STREAM_SIZE - HEADER_SIZE) {
                memcpy(g_buf->data + pos, piece, (size_t)n);
                g_buf->write_pos = pos + (uint32_t)n;
                generated = i + 1;
                g_buf->tokens_gen = (uint32_t)generated;

                auto now = std::chrono::high_resolution_clock::now();
                double sec = std::chrono::duration<double>(now - start_time).count();
                if (sec > 0.0)
                    g_buf->tps_scaled = (uint32_t)((double)generated / sec * 100.0);
            }
        }

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, next) != 0) break;
    }

    g_buf->flags = FLAG_DONE;
    LOGI("Inference done: %d tokens", generated);
    env->ReleaseStringUTFChars(prompt, input);
}

// ═══════════════════════════════════════════════════════════════════
// Control
// ═══════════════════════════════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_abortInferenceNative(JNIEnv*, jobject) {
    g_abort = true;
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_resetContextNative(JNIEnv*, jobject) {
    if (g_ctx) {
        auto mem = llama_get_memory(g_ctx);
        llama_memory_seq_rm(mem, 0, -1, -1);
    }
    if (g_buf) {
        g_buf->write_pos  = 0;
        g_buf->flags      = 0;
        g_buf->tokens_gen = 0;
        g_buf->tps_scaled = 0;
    }
    g_abort = false;
}

// ═══════════════════════════════════════════════════════════════════
// KV Cache Usage
// ═══════════════════════════════════════════════════════════════════
JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(JNIEnv*, jobject) {
    if (!g_ctx || !g_buf) return 0;
    // Use the existing API style from the original codebase
    int used = 0;
    auto mem = llama_get_memory(g_ctx);
    used = (int)llama_memory_seq_pos_max(mem, 0) + 1;
    return (used * 100) / (g_n_ctx > 0 ? g_n_ctx : 8192);
}

// ═══════════════════════════════════════════════════════════════════
// Model Info (JSON)
// ═══════════════════════════════════════════════════════════════════
JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_EngineCore_getModelInfoNative(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{}");

    char desc_buf[256];
    llama_model_desc(g_model, desc_buf, sizeof(desc_buf));

    uint64_t model_size = llama_model_size(g_model);
    char size_buf[64];
    if (model_size > 1073741824ULL)
        snprintf(size_buf, sizeof(size_buf), "%.2f GB", (double)model_size / 1073741824.0);
    else if (model_size > 1048576ULL)
        snprintf(size_buf, sizeof(size_buf), "%.2f MB", (double)model_size / 1048576.0);
    else
        snprintf(size_buf, sizeof(size_buf), "%llu B", (unsigned long long)model_size);

    char buf[2048];
    snprintf(buf, sizeof(buf),
        "{"
        "\"desc\":\"%s\","
        "\"n_params\":%lld,"
        "\"n_layer\":%d,"
        "\"n_embd\":%d,"
        "\"n_head\":%d,"
        "\"n_ctx_train\":%d,"
        "\"size\":\"%s\","
        "\"n_gpu_layers\":%d"
        "}",
        desc_buf,
        (long long)llama_model_n_params(g_model),
        llama_model_n_layer(g_model),
        llama_model_n_embd(g_model),
        llama_model_n_head(g_model),
        llama_model_n_ctx_train(g_model),
        size_buf,
        g_n_gpu_layers
    );
    return env->NewStringUTF(buf);
}

// ═══════════════════════════════════════════════════════════════════
// Benchmark
// ═══════════════════════════════════════════════════════════════════
JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_EngineCore_benchmarkNative(JNIEnv* env, jobject, jint ppTokens, jint tgTokens) {
    if (!g_ctx || !g_model) {
        return env->NewStringUTF("{\"error\":\"Model not loaded\"}");
    }

    auto vocab   = llama_model_get_vocab(g_model);
    auto bos     = llama_vocab_bos(vocab);
    auto sampler = g_sampler;

    // Dummy tokens for PP
    std::vector<llama_token> dummy((size_t)ppTokens, bos);

    // Warmup
    llama_batch batch = llama_batch_get_one(dummy.data(), (int)dummy.size());
    llama_decode(g_ctx, batch);
    {
        auto mem = llama_get_memory(g_ctx);
        llama_memory_seq_rm(mem, 0, -1, -1);
    }

    // Timed PP
    auto pp_start = std::chrono::high_resolution_clock::now();
    batch = llama_batch_get_one(dummy.data(), (int)dummy.size());
    llama_decode(g_ctx, batch);
    auto pp_end = std::chrono::high_resolution_clock::now();
    double pp_ms = std::chrono::duration<double, std::milli>(pp_end - pp_start).count();
    double pp_tps = (pp_ms > 0.0) ? (double)ppTokens / (pp_ms / 1000.0) : 0.0;

    // Timed TG
    auto tg_start = std::chrono::high_resolution_clock::now();
    llama_token last = bos;
    for (int i = 0; i < tgTokens; i++) {
        llama_batch b = llama_batch_get_one(&last, 1);
        if (llama_decode(g_ctx, b) != 0) break;
        last = llama_sampler_sample(sampler, g_ctx, -1);
    }
    auto tg_end = std::chrono::high_resolution_clock::now();
    double tg_ms = std::chrono::duration<double, std::milli>(tg_end - tg_start).count();
    double tg_tps = (tg_ms > 0.0) ? (double)tgTokens / (tg_ms / 1000.0) : 0.0;

    // Clean up
    {
        auto mem = llama_get_memory(g_ctx);
        llama_memory_seq_rm(mem, 0, -1, -1);
    }

    char result[512];
    snprintf(result, sizeof(result),
        "{\"pp_tps\":%.1f,\"tg_tps\":%.1f,\"pp_ms\":%.1f,\"tg_ms\":%.1f,"
        "\"pp_tokens\":%d,\"tg_tokens\":%d}",
        pp_tps, tg_tps, pp_ms, tg_ms, (int)ppTokens, (int)tgTokens);
    return env->NewStringUTF(result);
}

} // extern "C"
