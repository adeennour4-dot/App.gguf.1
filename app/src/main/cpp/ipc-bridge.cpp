// ipc-bridge.cpp
// Fixed to work with the latest llama.cpp API

#define LLAMA_USE_KV_CACHE 1   // Required for kv_cache_* functions

#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <vector>
#include <unistd.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <atomic>
#include <thread>
#include <chrono>

#include "llama.h"

#define LOG_TAG "IPC_BRIDGE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Shared memory configuration
constexpr const char* SHARED_MEM_NAME = "/llama_shared_mem";
constexpr size_t SHARED_MEM_SIZE = 512 * 1024; // 512 KB
constexpr size_t HEADER_SIZE = 16;

struct SharedMemory {
    volatile uint32_t state;      // 0: idle, 1: prompt ready, 2: generating, 3: done, 4: error
    volatile uint32_t prompt_len;
    char data[SHARED_MEM_SIZE - HEADER_SIZE];
};

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static std::atomic<bool> g_generating{false};
static int g_shared_fd = -1;
static SharedMemory* g_shm = nullptr;

// ----------------------------------------------------------------------------
// Helper: get chat template from model
// ----------------------------------------------------------------------------
static std::string get_chat_template() {
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl && tmpl[0]) return std::string(tmpl);
    // fallback to a simple instruction template
    return "{{ bos }}{{ user }} {{ message }} {{ assistant }}";
}

// ----------------------------------------------------------------------------
// Apply chat template (new API: 6 arguments)
// ----------------------------------------------------------------------------
static int apply_chat_template(const std::vector<llama_chat_message>& msgs,
                               bool add_assistant, char* buf, int32_t len) {
    std::string tmpl = get_chat_template();
    return llama_chat_apply_template(tmpl.c_str(),
                                     msgs.data(), msgs.size(),
                                     add_assistant, buf, len);
}

// ----------------------------------------------------------------------------
// Clear KV cache (works with LLAMA_USE_KV_CACHE defined)
// ----------------------------------------------------------------------------
static void kv_cache_clear() {
    if (g_ctx) {
        llama_kv_cache_clear(g_ctx);
    }
}

// ----------------------------------------------------------------------------
// Shift KV cache (for context rolling)
// ----------------------------------------------------------------------------
static void kv_cache_shift(int keep, int n_discard) {
    if (!g_ctx) return;
    int n_ctx_used = llama_get_kv_cache_used_cells(g_ctx);
    if (n_ctx_used <= keep + n_discard) return;
    llama_kv_cache_seq_rm(g_ctx, 0, keep, keep + n_discard);
    llama_kv_cache_seq_add(g_ctx, 0, keep + n_discard, n_ctx_used, -n_discard);
}

// ----------------------------------------------------------------------------
// JNI: initialize model
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourpackage_MainActivity_nativeInitModel(
        JNIEnv* env, jobject thiz,
        jstring model_path,
        jint n_ctx, jint n_threads, jfloat temperature,
        jfloat top_p, jfloat min_p, jfloat repeat_penalty) {

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s", path);

    // Parameters for loading model
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 99;  // Use all GPU layers
    model_params.use_mmap = true;

    g_model = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Context parameters
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.temperature = temperature;
    ctx_params.top_p = top_p;
    ctx_params.min_p = min_p;
    ctx_params.penalty_repeat = repeat_penalty;
    ctx_params.penalty_last_n = 64;
    ctx_params.n_batch = 512;

    // Use the new API (llama_init_from_model instead of deprecated llama_new_context_with_model)
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_free_model(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model and context initialized successfully");
    return JNI_TRUE;
}

// ----------------------------------------------------------------------------
// JNI: shared memory setup
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourpackage_MainActivity_nativeSetupSharedMemory(JNIEnv* env, jobject thiz) {
    g_shared_fd = shm_open(SHARED_MEM_NAME, O_CREAT | O_RDWR, 0666);
    if (g_shared_fd == -1) {
        LOGE("shm_open failed");
        return JNI_FALSE;
    }
    if (ftruncate(g_shared_fd, SHARED_MEM_SIZE) == -1) {
        LOGE("ftruncate failed");
        return JNI_FALSE;
    }
    g_shm = (SharedMemory*)mmap(nullptr, SHARED_MEM_SIZE,
                                PROT_READ | PROT_WRITE, MAP_SHARED,
                                g_shared_fd, 0);
    if (g_shm == MAP_FAILED) {
        LOGE("mmap failed");
        return JNI_FALSE;
    }
    g_shm->state = 0;
    g_shm->prompt_len = 0;
    LOGI("Shared memory set up");
    return JNI_TRUE;
}

// ----------------------------------------------------------------------------
// JNI: start generation (runs in a separate thread)
// ----------------------------------------------------------------------------
static void generation_thread() {
    g_generating = true;
    kv_cache_clear();

    // Read prompt from shared memory
    std::string prompt(g_shm->data, g_shm->prompt_len);
    LOGI("Generating for prompt: %.50s...", prompt.c_str());

    // Build chat messages
    std::vector<llama_chat_message> msgs;
    msgs.push_back({"user", prompt.c_str()});

    // Apply chat template to get formatted prompt
    int n_needed = apply_chat_template(msgs, false, nullptr, 0);
    if (n_needed < 0) {
        LOGE("apply_chat_template failed (1)");
        g_shm->state = 4; // error
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

    // Tokenize
    std::vector<llama_token> tokens(n_needed + 32);
    int n_tokens = llama_tokenize(g_model, formatted.data(), formatted.size(),
                                  tokens.data(), tokens.size(), true, false);
    tokens.resize(n_tokens);

    // Main generation loop
    std::string result;
    const int max_tokens = 512;
    for (int i = 0; i < max_tokens && g_generating; ++i) {
        llama_token new_token = llama_sample_token(g_ctx, nullptr); // null logits = default sampling
        if (new_token == llama_token_eos(g_model)) break;

        std::string piece = llama_token_to_piece(g_ctx, new_token);
        result += piece;

        // Write incremental result to shared memory
        size_t copy_len = std::min(result.size(), SHARED_MEM_SIZE - HEADER_SIZE - 1);
        memcpy((char*)g_shm->data, result.c_str(), copy_len);
        g_shm->data[copy_len] = '\0';
        g_shm->state = 2; // generating

        // Feed token back
        llama_batch batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, batch)) {
            LOGE("llama_decode failed");
            g_shm->state = 4;
            break;
        }
    }

    // Finalize
    g_shm->state = 3; // done
    g_generating = false;
    LOGI("Generation finished");
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourpackage_MainActivity_nativeStartGeneration(JNIEnv* env, jobject thiz) {
    if (!g_ctx || !g_shm) {
        LOGE("Context or shared memory not ready");
        return;
    }
    std::thread thr(generation_thread);
    thr.detach();
}

// ----------------------------------------------------------------------------
// JNI: stop generation
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_yourpackage_MainActivity_nativeStopGeneration(JNIEnv* env, jobject thiz) {
    g_generating = false;
    if (g_ctx) {
        kv_cache_clear();
    }
    if (g_shm) {
        g_shm->state = 0;
    }
}

// ----------------------------------------------------------------------------
// JNI: cleanup
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_yourpackage_MainActivity_nativeCleanup(JNIEnv* env, jobject thiz) {
    g_generating = false;
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
    if (g_shm) {
        munmap(g_shm, SHARED_MEM_SIZE);
        g_shm = nullptr;
    }
    if (g_shared_fd != -1) {
        close(g_shared_fd);
        shm_unlink(SHARED_MEM_NAME);
        g_shared_fd = -1;
    }
    LOGI("Cleanup complete");
}

// ----------------------------------------------------------------------------
// JNI: get token count in KV cache (for UI)
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_yourpackage_MainActivity_nativeGetUsedTokens(JNIEnv* env, jobject thiz) {
    if (!g_ctx) return 0;
    return llama_get_kv_cache_used_cells(g_ctx);
}
