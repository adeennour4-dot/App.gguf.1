// ipc-bridge.cpp – Fully updated for latest llama.cpp API (June 2026)
#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <vector>
#include <thread>
#include <atomic>
#include <chrono>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <errno.h>

#include "llama.h"

#define LOG_TAG "IPC_BRIDGE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Shared memory configuration
constexpr const char* SHARED_MEM_NAME = "/llama_shared_mem";
constexpr size_t SHARED_MEM_SIZE = 512 * 1024; // 512 KB
constexpr size_t HEADER_SIZE = 16;

struct SharedMemory {
    std::atomic<uint32_t> state;      // 0:idle,1:prompt ready,2:generating,3:done,4:error
    uint32_t prompt_len;
    char data[SHARED_MEM_SIZE - HEADER_SIZE];
};

static llama_model*         g_model = nullptr;
static llama_context*       g_ctx   = nullptr;
static llama_sampling_context* g_sampling = nullptr;
static SharedMemory*        g_shm   = nullptr;
static int                  g_shm_fd = -1;
static std::atomic<bool>    g_generating{false};

// ----------------------------------------------------------------------------
// KV cache helpers (still present in latest API)
// ----------------------------------------------------------------------------
static void kv_cache_clear() {
    if (!g_ctx) return;
    llama_kv_cache_clear(g_ctx);
}

static int kv_cache_used_cells() {
    if (!g_ctx) return 0;
    return llama_get_kv_cache_used_cells(g_ctx);
}

static void kv_cache_shift(int keep, int n_discard) {
    if (!g_ctx) return;
    int n_ctx_used = kv_cache_used_cells();
    if (n_ctx_used <= keep + n_discard) return;
    llama_kv_cache_seq_rm(g_ctx, 0, keep, keep + n_discard);
    llama_kv_cache_seq_add(g_ctx, 0, keep + n_discard, n_ctx_used, -n_discard);
}

// ----------------------------------------------------------------------------
// Helper: get vocab from model (latest API)
// ----------------------------------------------------------------------------
static const llama_vocab* get_vocab() {
    return llama_model_get_vocab(g_model);
}

// ----------------------------------------------------------------------------
// Helper: apply chat template (6 args, no model pointer)
// ----------------------------------------------------------------------------
static int apply_chat_template(const std::vector<llama_chat_message>& msgs,
                               bool add_assistant, char* buf, int32_t len) {
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl || !tmpl[0]) tmpl = "{{ bos }}{{ user }} {{ message }} {{ assistant }}";
    return llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                     add_assistant, buf, len);
}

// ----------------------------------------------------------------------------
// JNI: nativeInitModel – loads model and creates context + sampler
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_adeennour4_appgguf_MainActivity_nativeInitModel(
        JNIEnv* env, jobject thiz,
        jstring model_path,
        jint n_ctx, jint n_threads,
        jfloat temperature, jfloat top_p, jfloat min_p, jfloat repeat_penalty) {

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s", path);

    // 1. Load model (new API)
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 99;
    model_params.use_mmap = true;
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // 2. Context parameters (sampling params no longer here)
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.n_batch = 512;   // for prompt processing

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // 3. Sampling parameters (new separate structure)
    llama_sampling_params sampling_params;
    sampling_params.temp = temperature;
    sampling_params.top_p = top_p;
    sampling_params.min_p = min_p;
    sampling_params.penalty_repeat = repeat_penalty;
    sampling_params.penalty_last_n = 64;
    sampling_params.n_probs = 0;

    g_sampling = llama_sampling_init(sampling_params);
    if (!g_sampling) {
        LOGE("Failed to init sampling");
        llama_free(g_ctx);
        llama_model_free(g_model);
        return JNI_FALSE;
    }

    LOGI("Model, context, sampler ready");
    return JNI_TRUE;
}

// ----------------------------------------------------------------------------
// JNI: nativeSetupSharedMemory
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_adeennour4_appgguf_MainActivity_nativeSetupSharedMemory(JNIEnv* env, jobject thiz) {
    g_shm_fd = shm_open(SHARED_MEM_NAME, O_CREAT | O_RDWR, 0666);
    if (g_shm_fd == -1) {
        LOGE("shm_open failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    if (ftruncate(g_shm_fd, SHARED_MEM_SIZE) == -1) {
        LOGE("ftruncate failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    g_shm = (SharedMemory*)mmap(nullptr, SHARED_MEM_SIZE,
                                PROT_READ | PROT_WRITE, MAP_SHARED,
                                g_shm_fd, 0);
    if (g_shm == MAP_FAILED) {
        LOGE("mmap failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    g_shm->state = 0;
    g_shm->prompt_len = 0;
    LOGI("Shared memory OK");
    return JNI_TRUE;
}

// ----------------------------------------------------------------------------
// Generation thread (runs in background)
// ----------------------------------------------------------------------------
static void generation_thread() {
    g_generating = true;
    kv_cache_clear();

    // Read prompt from shared memory
    std::string prompt(g_shm->data, g_shm->prompt_len);
    LOGI("Generating for: %.50s...", prompt.c_str());

    // Build chat messages
    std::vector<llama_chat_message> msgs;
    msgs.push_back({"user", prompt.c_str()});

    // Apply chat template
    int n_needed = apply_chat_template(msgs, false, nullptr, 0);
    if (n_needed < 0) {
        LOGE("apply_chat_template failed (1)");
        g_shm->state = 4;
        g_generating = false;
        return;
    }
    std::vector<char> formatted(n_needed + 1);
    int n_written = apply_chat_template(msgs, false, formatted.data(), n_needed + 1);
    if (n_written < 0) {
        LOGE("apply_chat_template failed (2)");
        g_shm->state = 4;
        g_generating = false;
        return;
    }

    // Tokenize using the new vocab API
    const llama_vocab* vocab = get_vocab();
    std::vector<llama_token> tokens(n_needed + 32);
    int n_tokens = llama_tokenize(vocab, formatted.data(), formatted.size(),
                                  tokens.data(), tokens.size(), true, false);
    if (n_tokens < 0) {
        LOGE("tokenization failed");
        g_shm->state = 4;
        g_generating = false;
        return;
    }
    tokens.resize(n_tokens);

    // Process prompt in batches
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch)) {
        LOGE("prompt decode failed");
        g_shm->state = 4;
        g_generating = false;
        return;
    }

    // Reset sampling
    llama_sampling_reset(g_sampling);

    // Generation loop
    const int max_tokens = 512;
    std::string result;
    for (int i = 0; i < max_tokens && g_generating; ++i) {
        llama_token new_token = llama_sampling_sample(g_sampling, g_ctx, nullptr);
        if (new_token == llama_token_eos(vocab)) break;

        // Convert token to text using new API (6 arguments)
        char piece[128];
        int len = llama_token_to_piece(vocab, new_token, piece, sizeof(piece), 0, true);
        if (len > 0) {
            result.append(piece, len);
            // Write incremental result to shared memory
            size_t copy_len = std::min(result.size(), SHARED_MEM_SIZE - HEADER_SIZE - 1);
            memcpy((char*)g_shm->data, result.c_str(), copy_len);
            g_shm->data[copy_len] = '\0';
            g_shm->state = 2;   // generating
        }

        // Feed token back
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next_batch)) {
            LOGE("token decode failed");
            g_shm->state = 4;
            break;
        }
    }

    g_shm->state = 3;   // done
    g_generating = false;
    LOGI("Generation finished");
}

extern "C" JNIEXPORT void JNICALL
Java_com_adeennour4_appgguf_MainActivity_nativeStartGeneration(JNIEnv* env, jobject thiz) {
    if (!g_ctx || !g_shm) {
        LOGE("Context or shared memory missing");
        return;
    }
    std::thread thr(generation_thread);
    thr.detach();
}

extern "C" JNIEXPORT void JNICALL
Java_com_adeennour4_appgguf_MainActivity_nativeStopGeneration(JNIEnv* env, jobject thiz) {
    g_generating = false;
    if (g_ctx) kv_cache_clear();
    if (g_shm) g_shm->state = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_adeennour4_appgguf_MainActivity_nativeCleanup(JNIEnv* env, jobject thiz) {
    g_generating = false;
    if (g_sampling) {
        llama_sampling_free(g_sampling);
        g_sampling = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    if (g_shm) {
        munmap(g_shm, SHARED_MEM_SIZE);
        g_shm = nullptr;
    }
    if (g_shm_fd != -1) {
        close(g_shm_fd);
        shm_unlink(SHARED_MEM_NAME);
        g_shm_fd = -1;
    }
    LOGI("Cleanup done");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_adeennour4_appgguf_MainActivity_nativeGetUsedTokens(JNIEnv* env, jobject thiz) {
    return kv_cache_used_cells();
}
