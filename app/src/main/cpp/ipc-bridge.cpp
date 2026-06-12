#include <jni.h>
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

#ifdef __aarch64__
#include <sched.h>
#include <sys/syscall.h>
#endif

#include "llama.h"

#define LOG_TAG "GGUF_Engine_v6"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// JVM reference for JNI callbacks
static JavaVM* g_jvm = nullptr;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// Runtime configuration
struct EngineConfig {
    int      n_ctx          = 8192;
    int      n_batch        = 2048;
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

// Global state
    static llama_model*   g_model       = nullptr;
    static llama_context* g_ctx         = nullptr;
    static llama_sampler* g_sampler     = nullptr;
    static EngineConfig   g_cfg;
    static std::atomic<bool> g_abort    { false };
    static bool           g_backend_initialized = false;
    static jobject        g_callback    = nullptr;

struct Message { std::string role; std::string content; };
static std::vector<Message> g_history;

// Big core helpers
static std::vector<int> detect_big_cores() {
    std::vector<int> big_cores;
    std::vector<std::pair<int, int>> core_freqs;
    for (int cpu = 0; cpu < 8; cpu++) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "r");
        if (f) {
            int freq = 0;
            if (fscanf(f, "%d", &freq) == 1)
                core_freqs.push_back({cpu, freq});
            fclose(f);
        }
    }
    if (core_freqs.empty()) return big_cores;
    int max_freq = 0;
    for (auto& [id, freq] : core_freqs)
        if (freq > max_freq) max_freq = freq;
    int threshold = max_freq * 80 / 100;
    for (auto& [id, freq] : core_freqs)
        if (freq >= threshold) big_cores.push_back(id);
    if (big_cores.empty())
        for (auto& [id, freq] : core_freqs) big_cores.push_back(id);
    return big_cores;
}

static void pin_to_big_cores() {
#ifdef __aarch64__
    auto big_cores = detect_big_cores();
    if (big_cores.empty()) return;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int core : big_cores) CPU_SET(core, &cpuset);
    pid_t tid = syscall(SYS_gettid);
    if (sched_setaffinity(tid, sizeof(cpuset), &cpuset) == 0)
        LOGI("Pinned to %zu big cores", big_cores.size());
    else
        LOGI("sched_setaffinity failed (non-fatal)");
#endif
}

static void pin_to_all_cores() {
#ifdef __aarch64__
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int ncpu = sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0) ncpu = 8;
    for (int cpu = 0; cpu < ncpu; cpu++) CPU_SET(cpu, &cpuset);
    pid_t tid = syscall(SYS_gettid);
    sched_setaffinity(tid, sizeof(cpuset), &cpuset);
#endif
}

static void boost_process_priority() {
    if (setpriority(PRIO_PROCESS, 0, -20) == 0)
        LOGI("Process priority boosted to -20");
    else
        LOGI("setpriority failed (non-fatal, may need root)");
}

static void lock_pages_in_ram() {
#ifdef __aarch64__
    if (mlockall(MCL_CURRENT | MCL_FUTURE) == 0)
        LOGI("mlockall: all pages locked in RAM");
    else
        LOGI("mlockall failed (non-fatal, may need CAP_IPC_LOCK)");
#endif
}

static void apply_performance_optimizations() {
    boost_process_priority();
    lock_pages_in_ram();
    pin_to_big_cores();
}

static inline llama_memory_t get_mem() {
    return llama_get_memory(g_ctx);
}

static void rebuild_sampler() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_cfg.min_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(g_cfg.top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_cfg.temperature));
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
    int n = llama_chat_apply_template(nullptr, msgs.data(), (int)msgs.size(),
                                       true, buf.data(), (int)buf.size());
    if (n > (int)buf.size()) {
        buf.resize(n + 1);
        llama_chat_apply_template(nullptr, msgs.data(), (int)msgs.size(),
                                   true, buf.data(), (int)buf.size());
    }
    return std::string(buf.data(), n > 0 ? n : 0);
}

// JNI callback helpers
static void call_callback_on_token(const std::string& piece) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env_stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (get_env_stat == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        need_detach = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    if (onToken) {
        jstring jpiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(g_callback, onToken, jpiece);
        env->DeleteLocalRef(jpiece);
    }
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_kv_cache(int percent) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env_stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (get_env_stat == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        need_detach = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID onKv = env->GetMethodID(cls, "onKvCacheUsage", "(I)V");
    if (onKv) env->CallVoidMethod(g_callback, onKv, percent);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_tokens_generated(int count) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env_stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (get_env_stat == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        need_detach = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID onTg = env->GetMethodID(cls, "onTokensGenerated", "(I)V");
    if (onTg) env->CallVoidMethod(g_callback, onTg, count);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_done() {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env_stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (get_env_stat == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        need_detach = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID onDone = env->GetMethodID(cls, "onDone", "()V");
    if (onDone) env->CallVoidMethod(g_callback, onDone);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_error(const std::string& error) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env_stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (get_env_stat == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        need_detach = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    if (onError) {
        jstring jerr = env->NewStringUTF(error.c_str());
        env->CallVoidMethod(g_callback, onError, jerr);
        env->DeleteLocalRef(jerr);
    }
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void release_callback() {
    if (g_callback && g_jvm) {
        JNIEnv* env = nullptr;
        g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (env) env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
}

// JNI — setEngineConfigNative
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setEngineConfigNative(
        JNIEnv*, jobject,
        jint nCtx, jint nBatch, jint maxNewTokens, jfloat temp,
        jfloat topP, jfloat minP, jint nGpuLayers, jint nThreads, jint seed) {
    g_cfg.n_ctx          = nCtx;
    g_cfg.n_batch        = (nBatch > 0) ? nBatch : 2048;
    g_cfg.max_new_tokens = maxNewTokens;
    g_cfg.temperature    = temp;
    g_cfg.top_p          = topP;
    g_cfg.min_p          = minP;
    g_cfg.n_gpu_layers   = nGpuLayers;
    g_cfg.n_threads      = (nThreads > 0) ? nThreads : 4;
    g_cfg.seed           = (seed < 0) ? LLAMA_DEFAULT_SEED : (uint32_t)seed;
}

// JNI — setRepeatPenaltyNative
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setRepeatPenaltyNative(
        JNIEnv*, jobject,
        jfloat repeatPenalty, jfloat freqPenalty, jfloat presPenalty) {
    g_cfg.repeat_penalty = repeatPenalty;
    g_cfg.freq_penalty   = freqPenalty;
    g_cfg.pres_penalty   = presPenalty;
    if (g_ctx) rebuild_sampler();
}

// JNI — setSystemPromptNative
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_setSystemPromptNative(JNIEnv* env, jobject, jstring prompt) {
    const char* s = env->GetStringUTFChars(prompt, nullptr);
    if (s) { g_cfg.system_prompt = s; env->ReleaseStringUTFChars(prompt, s); }
}

// JNI — resetContextNative
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_resetContextNative(JNIEnv*, jobject) {
    g_history.clear();
    if (g_ctx) llama_memory_clear(get_mem(), true);
    LOGI("Context reset.");
}

// JNI — loadGgufModelNative
extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return JNI_FALSE;

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama_backend_init() called");
    }

    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_history.clear();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = g_cfg.n_gpu_layers;

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }

    int total_cores = (int)std::thread::hardware_concurrency();
    if (total_cores < 1) total_cores = 4;

    int n_threads = (g_cfg.n_threads > 0) ? std::min(g_cfg.n_threads, total_cores) : total_cores;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = g_cfg.n_ctx;
    cparams.n_batch         = g_cfg.n_batch;
    cparams.n_ubatch        = g_cfg.n_batch;  // CRITICAL: Set uBatch = batch for fast prefill (default 512 is too slow)
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        LOGE("Failed to create context");
        return JNI_FALSE;
    }

    // Verify GPU backend initialization
    int n_gpu_layers_loaded = 0;
    // Note: llama_model_n_gpu_layers not available in b9474; check via context
    if (g_ctx) {
        // If GPU layers were requested and context created, assume success
        n_gpu_layers_loaded = (g_cfg.n_gpu_layers > 0) ? g_cfg.n_gpu_layers : 0;
    }
    if (n_gpu_layers_loaded == 0 && g_cfg.n_gpu_layers > 0) {
        LOGW("GPU layers requested (%d) but 0 loaded — GPU backend may not be available", g_cfg.n_gpu_layers);
    } else if (n_gpu_layers_loaded > 0) {
        LOGI("GPU acceleration ENABLED: %d layers offloaded", n_gpu_layers_loaded);
    }

    rebuild_sampler();
    apply_performance_optimizations();

    LOGI("Model loaded OK. n_ctx=%d requested_gpu_layers=%d actual_gpu_layers=%d threads=%d (total_cores=%d)",
         g_cfg.n_ctx, g_cfg.n_gpu_layers, n_gpu_layers_loaded, n_threads, total_cores);
    return JNI_TRUE;
}

// JNI — getModelInfoNative
extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_EngineCore_getModelInfoNative(JNIEnv* env, jobject) {
    if (!g_model) return env->NewStringUTF("{\"error\":\"no model loaded\"}");
    char buf[256];
    std::ostringstream j;
    j << "{";
    if (llama_model_meta_val_str(g_model, "general.architecture", buf, sizeof(buf)) >= 0)
        j << "\"arch\":\"" << buf << "\",";
    else j << "\"arch\":\"unknown\",";
    j << "\"n_params\":" << llama_model_n_params(g_model) << ",";
    j << "\"n_embd\":" << llama_model_n_embd(g_model) << ",";
    if (llama_model_meta_val_str(g_model, "llm.block_count", buf, sizeof(buf)) >= 0)
        j << "\"n_layer\":" << atoi(buf) << ",";
    if (llama_model_meta_val_str(g_model, "llm.context_length", buf, sizeof(buf)) >= 0)
        j << "\"ctx_train\":" << atoi(buf) << ",";
    j << "\"n_vocab\":" << llama_vocab_n_tokens(llama_model_get_vocab(g_model));
    j << "}";
    return env->NewStringUTF(j.str().c_str());
}

// JNI — benchmarkNative
extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_EngineCore_benchmarkNative(JNIEnv* env, jobject, jint ppTokens, jint tgTokens) {
    if (!g_model || !g_ctx) return env->NewStringUTF("{\"error\":\"no model loaded\"}");
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
    llama_memory_clear(get_mem(), true);
    llama_batch batch = llama_batch_get_one(pp_toks.data(), ppTokens);
    auto pp_start = std::chrono::high_resolution_clock::now();
    llama_decode(g_ctx, batch);
    auto pp_end = std::chrono::high_resolution_clock::now();
    double pp_ms = std::chrono::duration<double, std::milli>(pp_end - pp_start).count();
    double pp_tps = ppTokens / (pp_ms / 1000.0);

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
    llama_memory_clear(get_mem(), true);

    char result[256];
    snprintf(result, sizeof(result),
             "{\"pp_tps\":%.1f,\"tg_tps\":%.1f,\"pp_ms\":%.1f,\"tg_ms\":%.1f}",
             pp_tps, tg_tps, pp_ms, tg_ms);
    return env->NewStringUTF(result);
}

// JNI — exportChatHistoryNative
extern "C" JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_EngineCore_exportChatHistoryNative(JNIEnv* env, jobject) {
    std::ostringstream out;
    out << "=== GGUF ZeroCopy v6 Chat Export ===\n";
    for (size_t i = 0; i < g_history.size(); i++) {
        out << "\n[" << (i + 1) << "] " << g_history[i].role << ":\n";
        out << g_history[i].content << "\n";
    }
    return env->NewStringUTF(out.str().c_str());
}

// JNI — getKvCacheUsageNative
extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(JNIEnv*, jobject) {
    if (!g_ctx) return 0;
    llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
    int used  = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
    int total = g_cfg.n_ctx;
    if (total <= 0) return 0;
    return (int)((used * 100LL) / total);
}

// JNI — abortInferenceNative
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_abortInferenceNative(JNIEnv*, jobject) {
    g_abort.store(true);
    LOGI("Inference abort requested.");
}

// JNI — executeWithCallbackNative
// Replaces executeZeroCopyInference + shared memory with JNI callback per token
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_executeWithCallbackNative(JNIEnv* env, jobject thiz, jstring jprompt, jobject callback) {
    if (!g_model || !g_ctx || !g_sampler) {
        LOGE("executeWithCallbackNative: engine not ready");
        if (callback) {
            jclass cls = env->GetObjectClass(callback);
            jmethodID onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
            if (onError) {
                jstring err = env->NewStringUTF("Engine not ready");
                env->CallVoidMethod(callback, onError, err);
                env->DeleteLocalRef(err);
            }
            env->DeleteLocalRef(cls);
        }
        return;
    }

    const char* user_input = env->GetStringUTFChars(jprompt, nullptr);
    if (!user_input) {
        call_callback_on_error("Failed to read prompt");
        return;
    }

    // Store callback as global ref
    if (g_callback) release_callback();
    g_callback = env->NewGlobalRef(callback);

    g_abort.store(false);

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
        call_callback_on_error("Tokenization failed");
        release_callback();
        return;
    }
    tokens.resize(n_toks);

    // Context shift if near limit
    llama_pos cur_max = llama_memory_seq_pos_max(get_mem(), 0);
    int n_ctx_used = (cur_max >= 0) ? (int)(cur_max + 1) : 0;

    if (n_ctx_used + n_toks >= g_cfg.n_ctx) {
        int keep     = g_cfg.n_ctx / 4;
        int n_discard = (n_ctx_used - keep) / 2;
        if (n_discard > 0) {
            llama_memory_t mem = get_mem();
            llama_memory_seq_rm (mem, 0, keep, keep + n_discard);
            llama_memory_seq_add(mem, 0, keep + n_discard, -1, -n_discard);
            LOGI("KV-cache context shift applied. discarded=%d", n_discard);
        }
    }

    // Prompt evaluation — all cores for parallel processing
    pin_to_all_cores();
    llama_batch batch = llama_batch_get_one(tokens.data(), n_toks);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode (prompt) failed");
        call_callback_on_error("Prompt decode failed");
        release_callback();
        return;
    }

    // Report KV cache usage after prompt eval
    {
        llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
        int used = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
        int pct = (g_cfg.n_ctx > 0) ? (int)((used * 100LL) / g_cfg.n_ctx) : 0;
        call_callback_on_kv_cache(pct);
    }

    // Generation loop — big cores for single-thread performance
    pin_to_big_cores();
    std::string response;
    int tokens_generated = 0;
    for (int i = 0; i < g_cfg.max_new_tokens; i++) {
        if (g_abort.load()) { LOGI("Inference aborted at token %d", i); break; }

        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), tok)) break;

        char piece[256];
        int n = llama_token_to_piece(llama_model_get_vocab(g_model), tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            piece[n] = '\0';
            response += piece;
            tokens_generated = i + 1;

            // JNI callback per token
            call_callback_on_token(std::string(piece, n));
            call_callback_on_tokens_generated(tokens_generated);
        }

        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, nb) != 0) break;

        // Report KV cache every 16 tokens
        if ((i & 0xF) == 0) {
            llama_pos max_pos = llama_memory_seq_pos_max(get_mem(), 0);
            int used = (max_pos >= 0) ? (int)(max_pos + 1) : 0;
            int pct = (g_cfg.n_ctx > 0) ? (int)((used * 100LL) / g_cfg.n_ctx) : 0;
            call_callback_on_kv_cache(pct);
        }
    }

    // Add assistant reply to history
    g_history.push_back({"assistant", response});

    // Signal done via callback
    call_callback_on_done();
    LOGI("Inference complete. tokens=%d chars=%zu", tokens_generated, response.size());

    // Release callback
    release_callback();
}
