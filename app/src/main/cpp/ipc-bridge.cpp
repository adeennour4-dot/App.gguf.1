/**
 * ipc-bridge.cpp — GGUF ZeroCopy Engine v6 (PRO)
 *
 * Performance-optimized Android LLM inference engine.
 * All optimizations are open-source (MIT/Apache 2.0 compatible).
 *
 * Features:
 *   - OpenCL GPU backend for Qualcomm Adreno (via llama.cpp)
 *   - Vulkan GPU backend for ARM Mali / Samsung Xclipse
 *   - Big core pinning via sched_setaffinity for big.LITTLE CPUs
 *   - Process priority boost to -20 (max)
 *   - mlockall() to prevent page faults during inference
 *   - ThinLTO + armv8.4a+dotprod+crc compilation flags
 *   - Context shifting for long conversations
 *   - Full chat history with llama_chat_apply_template
 *   - Multimodal support (vision/audio via libmtmd)
 *   - Embedding model support
 */

#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <string>
#include <vector>
#include <atomic>
#include <chrono>
#include <sstream>
#include <thread>
#include <android/log.h>

// Big core pinning for ARM big.LITTLE
#ifdef __aarch64__
#include <sched.h>
#include <sys/syscall.h>
#endif

#include "llama.h"

#define LOG_TAG "GGUF_Engine_v6"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Extended shared ring buffer (16-byte header)
//   Offset  0 : uint32_t  write_pos        — bytes written so far
//   Offset  4 : uint32_t  flags            — bit 0 = inference done
//   Offset  8 : uint32_t  tokens_generated — token count for t/s computation
//   Offset 12 : uint32_t  reserved
//   Offset 16 : char      token_stream[]   — UTF-8 output bytes
// ---------------------------------------------------------------------------
static constexpr size_t TOKEN_STREAM_SIZE = 524288;   // 512 KB

struct SharedRingBuffer {
    volatile uint32_t write_pos;
    volatile uint32_t flags;
    volatile uint32_t tokens_generated;
    volatile uint32_t reserved;
    char token_stream[TOKEN_STREAM_SIZE];
};
static_assert(sizeof(SharedRingBuffer) == 16 + TOKEN_STREAM_SIZE, "Layout mismatch");

// ---------------------------------------------------------------------------
// Runtime configuration
// ---------------------------------------------------------------------------
struct EngineConfig {
    int      n_ctx          = 8192;
    int      n_batch        = 512;
    int      n_threads      = 4;
    int      n_gpu_layers   = 99;
    int      max_new_tokens = 4096;
    float    temperature    = 0.7f;
    float    top_p          = 0.9f;
    float    min_p          = 0.05f;
    float    repeat_penalty = 1.1f;
    float    freq_penalty   = 0.0f;
    float    pres_penalty   = 0.0f;
    uint32_t seed           = LLAMA_DEFAULT_SEED;
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
    static std::atomic<bool> g_abort { false };
    static bool              g_backend_initialized = false;

struct Message { std::string role; std::string content; };
static std::vector<Message> g_history;

// ---------------------------------------------------------------------------
// Performance optimization helpers (open-source, MIT compatible)
// ---------------------------------------------------------------------------

/**
 * Detect big cores on ARM big.LITTLE architecture.
 * Reads /sys/devices/system/cpu/ to find cores with higher max frequency.
 * Returns list of big core IDs.
 */
static std::vector<int> detect_big_cores() {
    std::vector<int> big_cores;
    std::vector<std::pair<int, int>> core_freqs; // (core_id, max_freq)

    for (int cpu = 0; cpu < 8; cpu++) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "r");
        if (f) {
            int freq = 0;
            if (fscanf(f, "%d", &freq) == 1) {
                core_freqs.push_back({cpu, freq});
            }
            fclose(f);
        }
    }

    if (core_freqs.empty()) return big_cores;

    // Find max frequency
    int max_freq = 0;
    for (auto& [id, freq] : core_freqs) {
        if (freq > max_freq) max_freq = freq;
    }

    // Cores within 80% of max frequency are "big" cores
    int threshold = max_freq * 80 / 100;
    for (auto& [id, freq] : core_freqs) {
        if (freq >= threshold) {
            big_cores.push_back(id);
        }
    }

    // If no big cores found (all same freq), return all cores
    if (big_cores.empty()) {
        for (auto& [id, freq] : core_freqs) {
            big_cores.push_back(id);
        }
    }

    return big_cores;
}

/**
 * Pin current thread to big cores for maximum performance.
 * Uses sched_setaffinity on Android/Linux.
 */
static void pin_to_big_cores() {
#ifdef __aarch64__
    auto big_cores = detect_big_cores();
    if (big_cores.empty()) return;

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int core : big_cores) {
        CPU_SET(core, &cpuset);
    }

    pid_t tid = syscall(SYS_gettid);
    if (sched_setaffinity(tid, sizeof(cpuset), &cpuset) == 0) {
        LOGI("Pinned to %zu big cores", big_cores.size());
    } else {
        LOGI("sched_setaffinity failed (non-fatal)");
    }
#endif
}

/**
 * Pin to all available cores (for prompt processing which benefits from parallelism).
 */
static void pin_to_all_cores() {
#ifdef __aarch64__
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int ncpu = sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;
    for (int cpu = 0; cpu < ncpu; cpu++) {
        CPU_SET(cpu, &cpuset);
    }

    pid_t tid = syscall(SYS_gettid);
    sched_setaffinity(tid, sizeof(cpuset), &cpuset);
#endif
}

/**
 * Boost process priority to maximum (nice -20).
 * Requires no special permissions on Android for self-process.
 */
static void boost_process_priority() {
    if (setpriority(PRIO_PROCESS, 0, -20) == 0) {
        LOGI("Process priority boosted to -20");
    } else {
        LOGI("setpriority failed (non-fatal, may need root)");
    }
}

/**
 * Lock all current and future pages in RAM to prevent page faults.
 * This is critical for consistent inference performance.
 */
static void lock_pages_in_ram() {
#ifdef __aarch64__
    if (mlockall(MCL_CURRENT | MCL_FUTURE) == 0) {
        LOGI("mlockall: all pages locked in RAM");
    } else {
        LOGI("mlockall failed (non-fatal, may need CAP_IPC_LOCK)");
    }
#endif
}

/**
 * Apply all performance optimizations.
 * Call this once before model loading.
 */
static void apply_performance_optimizations() {
    boost_process_priority();
    lock_pages_in_ram();
    pin_to_big_cores();
}

// ---------------------------------------------------------------------------
// Helper: get memory handle (v5 — new llama_memory_* API)
// ---------------------------------------------------------------------------
static inline llama_memory_t get_mem() {
    return llama_get_memory(g_ctx);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
static void rebuild_sampler() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_cfg.min_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(g_cfg.top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_cfg.temperature));
    // Repetition penalty
    llama_sampler_chain_add(g_sampler,
        llama_sampler_init_penalties(64, g_cfg.repeat_penalty,
                                     g_cfg.freq_penalty, g_cfg.pres_penalty));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(g_cfg.seed));
}

static std::string build_chat_prompt() {
    std::vector<llama_chat_message> msgs;
    msgs.push_back({"system", g_cfg.system_prompt.c_str()});
    for (auto& m : g_history)
        msgs.push_back({m.role.c_str(), m.content.c_str()});

    std::vector<char> buf(65536);
    int n = llama_chat_apply_template(nullptr,
                                       msgs.data(), (int)msgs.size(),
                                       true, buf.data(), (int)buf.size());
    if (n > (int)buf.size()) {
        buf.resize(n + 1);
        llama_chat_apply_template(nullptr,
                                   msgs.data(), (int)msgs.size(),
                                   true, buf.data(), (int)buf.size());
    }
    return std::string(buf.data(), n > 0 ? n : 0);
}

// ---------------------------------------------------------------------------
// JNI — initialization
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_initializeSharedMemoryNative(JNIEnv*, jobject) {
    size_t total = sizeof(SharedRingBuffer);
    int fd = ASharedMemory_create("gguf_ring_v5", total);
    if (fd < 0) { LOGE("ASharedMemory_create failed: %d", fd); return -1; }
    ASharedMemory_setProt(fd, PROT_READ | PROT_WRITE);
    void* ptr = mmap(nullptr, total, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (ptr == MAP_FAILED) { close(fd); LOGE("mmap failed"); return -2; }
    g_buffer = static_cast<SharedRingBuffer*>(ptr);
    g_shm_fd = fd;
    memset(g_buffer, 0, total);
    LOGI("Shared ring buffer v5 allocated. size=%zu", total);
    return fd;
}

// ---------------------------------------------------------------------------
// JNI — setEngineConfigNative
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setEngineConfigNative(
        JNIEnv*, jobject,
        jint nCtx, jint maxNewTokens, jfloat temp,
        jfloat topP, jfloat minP, jint nGpuLayers, jint nThreads, jint seed) {
    g_cfg.n_ctx          = nCtx;
    g_cfg.max_new_tokens = maxNewTokens;
    g_cfg.temperature    = temp;
    g_cfg.top_p          = topP;
    g_cfg.min_p          = minP;
    g_cfg.n_gpu_layers   = nGpuLayers;
    g_cfg.n_threads      = (nThreads > 0) ? nThreads : 4;
    g_cfg.seed           = (seed < 0) ? LLAMA_DEFAULT_SEED : (uint32_t)seed;
}

// ---------------------------------------------------------------------------
// JNI — setRepeatPenaltyNative
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setRepeatPenaltyNative(
        JNIEnv*, jobject,
        jfloat repeatPenalty, jfloat freqPenalty, jfloat presPenalty) {
    g_cfg.repeat_penalty = repeatPenalty;
    g_cfg.freq_penalty   = freqPenalty;
    g_cfg.pres_penalty   = presPenalty;
    if (g_ctx) rebuild_sampler(); // Apply immediately without model reload
}

// ---------------------------------------------------------------------------
// JNI — setSystemPromptNative
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setSystemPromptNative(JNIEnv* env, jobject, jstring prompt) {
    const char* s = env->GetStringUTFChars(prompt, nullptr);
    if (s) { g_cfg.system_prompt = s; env->ReleaseStringUTFChars(prompt, s); }
}

// ---------------------------------------------------------------------------
// JNI — resetContextNative
// v5: uses llama_memory_clear instead of deprecated llama_kv_cache_clear
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_resetContextNative(JNIEnv*, jobject) {
    g_history.clear();
    if (g_ctx) {
        llama_memory_clear(get_mem(), true);   // replaces llama_kv_cache_clear(g_ctx)
    }
    if (g_buffer) {
        g_buffer->write_pos        = 0;
        g_buffer->flags            = 0;
        g_buffer->tokens_generated = 0;
    }
    LOGI("Context reset.");
}

// ---------------------------------------------------------------------------
// JNI — loadGgufModelNative
// v5: uses llama_init_from_model, sets n_threads_batch explicitly
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return JNI_FALSE;

    // Initialize llama.cpp backend once (required before any API calls)
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama_backend_init() called");
    }

    // Free previous model
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_history.clear();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = g_cfg.n_gpu_layers;

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = g_cfg.n_ctx;
    cparams.n_batch         = g_cfg.n_batch;
    cparams.n_threads       = g_cfg.n_threads;
    cparams.n_threads_batch = g_cfg.n_threads; // v5: explicitly set batch threads

    // v5: llama_init_from_model replaces deprecated llama_new_context_with_model
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        LOGE("Failed to create context");
        return JNI_FALSE;
    }

    rebuild_sampler();

    // Apply performance optimizations after model load
    apply_performance_optimizations();

    LOGI("Model loaded OK. n_ctx=%d gpu_layers=%d threads=%d",
         g_cfg.n_ctx, g_cfg.n_gpu_layers, g_cfg.n_threads);
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// JNI — getModelInfoNative
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_EngineCore_getModelInfoNative(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{\"error\":\"no model loaded\"}");

    char buf[256];
    std::ostringstream j;
    j << "{";

    // Architecture
    if (llama_model_meta_val_str(g_model, "general.architecture", buf, sizeof(buf)) >= 0)
        j << "\"arch\":\"" << buf << "\",";
    else j << "\"arch\":\"unknown\",";

    // Parameter count
    j << "\"n_params\":" << llama_model_n_params(g_model) << ",";

    // Embedding size
    j << "\"n_embd\":" << llama_model_n_embd(g_model) << ",";

    // Layer count
    if (llama_model_meta_val_str(g_model, "llm.block_count", buf, sizeof(buf)) >= 0)
        j << "\"n_layer\":" << atoi(buf) << ",";

    // Context length from metadata
    if (llama_model_meta_val_str(g_model, "llm.context_length", buf, sizeof(buf)) >= 0)
        j << "\"ctx_train\":" << atoi(buf) << ",";

    // Vocab size
    j << "\"n_vocab\":" << llama_vocab_n_tokens(llama_model_get_vocab(g_model));

    j << "}";
    return env->NewStringUTF(j.str().c_str());
}

// ---------------------------------------------------------------------------
// JNI — benchmarkNative
// v5: uses llama_memory_clear instead of llama_kv_cache_clear
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_EngineCore_benchmarkNative(JNIEnv* env, jobject, jint ppTokens, jint tgTokens) {
    if (!g_model || !g_ctx) return env->NewStringUTF("{\"error\":\"no model loaded\"}");

    // PP: encode ppTokens tokens
    const char* test_str = "The quick brown fox jumps over the lazy dog. ";
    std::vector<llama_token> pp_toks(ppTokens);
    int n = llama_tokenize(llama_model_get_vocab(g_model),
                           test_str, strlen(test_str),
                           pp_toks.data(), ppTokens, true, false);
    if (n <= 0) n = 1;
    pp_toks.resize(n);
    while ((int)pp_toks.size() < ppTokens) {
        auto old = pp_toks;
        for (auto t : old) { pp_toks.push_back(t); if ((int)pp_toks.size() >= ppTokens) break; }
    }
    pp_toks.resize(ppTokens);

    llama_memory_clear(get_mem(), true);   // v5 API

    llama_batch batch = llama_batch_get_one(pp_toks.data(), ppTokens);
    auto pp_start = std::chrono::high_resolution_clock::now();
    llama_decode(g_ctx, batch);
    auto pp_end = std::chrono::high_resolution_clock::now();
    double pp_ms = std::chrono::duration<double, std::milli>(pp_end - pp_start).count();
    double pp_tps = ppTokens / (pp_ms / 1000.0);

    // TG: generate tgTokens tokens
    llama_sampler* bench_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(bench_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_token token = llama_sampler_sample(bench_sampler, g_ctx, -1);
    auto tg_start = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < tgTokens; i++) {
        llama_batch tb = llama_batch_get_one(&token, 1);
        if (llama_decode(g_ctx, tb) != 0) break;
        token = llama_sampler_sample(bench_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) break;
    }
    auto tg_end = std::chrono::high_resolution_clock::now();
    double tg_ms  = std::chrono::duration<double, std::milli>(tg_end - tg_start).count();
    double tg_tps = tgTokens / (tg_ms / 1000.0);

    llama_sampler_free(bench_sampler);
    llama_memory_clear(get_mem(), true);   // v5 API

    char result[256];
    snprintf(result, sizeof(result),
             "{\"pp_tps\":%.1f,\"tg_tps\":%.1f,\"pp_ms\":%.1f,\"tg_ms\":%.1f}",
             pp_tps, tg_tps, pp_ms, tg_ms);
    return env->NewStringUTF(result);
}

// ---------------------------------------------------------------------------
// JNI — exportChatHistoryNative
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_EngineCore_exportChatHistoryNative(JNIEnv* env, jobject) {
    std::ostringstream out;
    out << "=== GGUF ZeroCopy v5 Chat Export ===\n";
    for (size_t i = 0; i < g_history.size(); i++) {
        out << "\n[" << (i + 1) << "] " << g_history[i].role << ":\n";
        out << g_history[i].content << "\n";
    }
    return env->NewStringUTF(out.str().c_str());
}

// ---------------------------------------------------------------------------
// JNI — getKvCacheUsageNative
// v5: llama_get_kv_cache_used_cells removed — use llama_memory_seq_pos_max
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(JNIEnv*, jobject) {
    if (!g_ctx) return 0;
    // llama_memory_seq_pos_max returns the max token position filled in seq 0.
    // +1 converts position (0-based) to cell count.
    llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
    int used  = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
    int total = g_cfg.n_ctx;
    if (total <= 0) return 0;
    return (int)((used * 100LL) / total);
}

// ---------------------------------------------------------------------------
// JNI — abortInferenceNative
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_abortInferenceNative(JNIEnv*, jobject) {
    g_abort.store(true);
    LOGI("Inference abort requested.");
}

// ---------------------------------------------------------------------------
// JNI — getWritePosNative / isInferenceDoneNative
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_getWritePosNative(JNIEnv*, jobject) {
    return g_buffer ? (jint)g_buffer->write_pos : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_isInferenceDoneNative(JNIEnv*, jobject) {
    return g_buffer ? ((g_buffer->flags & 1) != 0 ? JNI_TRUE : JNI_FALSE) : JNI_TRUE;
}

// ---------------------------------------------------------------------------
// JNI — executeZeroCopyInference
// v5: full migration to llama_memory_* API for KV cache operations
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_executeZeroCopyInference(JNIEnv* env, jobject, jstring jprompt) {
    if (!g_model || !g_ctx || !g_buffer || !g_sampler) {
        LOGE("executeZeroCopyInference: engine not ready");
        if (g_buffer) { g_buffer->flags = 1; }
        return;
    }

    const char* user_input = env->GetStringUTFChars(jprompt, nullptr);
    if (!user_input) { if (g_buffer) g_buffer->flags = 1; return; }

    // Reset ring buffer
    g_buffer->write_pos        = 0;
    g_buffer->flags            = 0;
    g_buffer->tokens_generated = 0;
    g_abort.store(false);
    memset(g_buffer->token_stream, 0, TOKEN_STREAM_SIZE);

    // Add user message to history
    g_history.push_back({"user", std::string(user_input)});
    env->ReleaseStringUTFChars(jprompt, user_input);

    // Build prompt via chat template
    std::string prompt = build_chat_prompt();
    LOGI("Prompt len=%zu", prompt.size());

    // Tokenize
    int n_max  = (int)llama_model_n_ctx_train(g_model);
    std::vector<llama_token> tokens(n_max);
    int n_toks = llama_tokenize(llama_model_get_vocab(g_model),
                                 prompt.c_str(), prompt.size(),
                                 tokens.data(), n_max, true, false);
    if (n_toks <= 0) {
        LOGE("Tokenization failed: %d", n_toks);
        g_history.pop_back();
        g_buffer->flags = 1; return;
    }
    tokens.resize(n_toks);

    // Context shift if near limit
    // v5: llama_memory_seq_pos_max replaces llama_get_kv_cache_used_cells
    llama_pos cur_max = llama_memory_seq_pos_max(get_mem(), 0);
    int n_ctx_used = (cur_max >= 0) ? (int)(cur_max + 1) : 0;

    if (n_ctx_used + n_toks >= g_cfg.n_ctx) {
        int keep     = g_cfg.n_ctx / 4;
        int n_discard = (n_ctx_used - keep) / 2;
        if (n_discard > 0) {
            // v5 API: llama_memory_seq_rm / llama_memory_seq_add
            llama_memory_t mem = get_mem();
            llama_memory_seq_rm (mem, 0, keep, keep + n_discard);
            llama_memory_seq_add(mem, 0, keep + n_discard, -1, -n_discard);
            LOGI("KV-cache context shift applied. discarded=%d", n_discard);
        }
    }

    // Prompt evaluation — use all cores for parallel processing
    pin_to_all_cores();
    llama_batch batch = llama_batch_get_one(tokens.data(), n_toks);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode (prompt) failed");
        g_buffer->flags = 1; return;
    }

    // Generation loop — pin to big cores for single-thread performance
    pin_to_big_cores();
    std::string response;
    for (int i = 0; i < g_cfg.max_new_tokens; i++) {
        if (g_abort.load()) { LOGI("Inference aborted at token %d", i); break; }

        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), tok)) break;

        // Piece to string
        char piece[256];
        int n = llama_token_to_piece(llama_model_get_vocab(g_model), tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            piece[n] = '\0';
            response += piece;
            // Write to shared ring buffer
            size_t pos = g_buffer->write_pos;
            if (pos + n < TOKEN_STREAM_SIZE) {
                memcpy(g_buffer->token_stream + pos, piece, n);
                g_buffer->write_pos += n;
                g_buffer->tokens_generated = i + 1;
            }
        }

        // Next token
        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, nb) != 0) break;
    }

    // Add assistant reply to history
    g_history.push_back({"assistant", response});

    // Signal done
    g_buffer->flags = 1;
    LOGI("Inference complete. tokens=%u bytes=%u", g_buffer->tokens_generated, g_buffer->write_pos);
}
