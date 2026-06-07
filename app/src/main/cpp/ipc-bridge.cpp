#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include <atomic>
#include <chrono>
#include <vector>
#include <algorithm>
#include <android/log.h>

#include "llama.h"

#define TAG "GGUF_ULTRA_V5"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Zero-Copy Shared Memory Layout (16-byte header + 512KB Buffer)
// ---------------------------------------------------------------------------
static constexpr size_t STREAM_SIZE = 524288; // 512 KB

struct SharedBuffer {
    volatile uint32_t write_pos;    // Offset 0: Bytes written
    volatile uint32_t flags;        // Offset 4: Bit 0 = Done
    volatile uint32_t tokens_gen;   // Offset 8: Total tokens produced
    volatile uint32_t tps_scaled;   // Offset 12: Tokens Per Second * 100
    char data[STREAM_SIZE];         // Offset 16: UTF-8 Text
};

// ---------------------------------------------------------------------------
// Global Engine State
// ---------------------------------------------------------------------------
static SharedBuffer* g_buf      = nullptr;
static llama_model*  g_model    = nullptr;
static llama_context* g_ctx      = nullptr;
static llama_sampler* g_sampler  = nullptr;
static std::atomic<bool> g_abort { false };

// ---------------------------------------------------------------------------
// JNI: Initialize Shared Memory
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_initializeSharedMemoryNative(JNIEnv*, jobject) {
    size_t total_size = sizeof(SharedBuffer);
    int fd = ASharedMemory_create("gguf_ultra_shm", total_size);
    if (fd < 0) return -1;

    void* ptr = mmap(NULL, total_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (ptr == MAP_FAILED) return -2;

    g_buf = (SharedBuffer*)ptr;
    memset(g_buf, 0, total_size);
    
    LOGI("Ultra Shared Memory Initialized. Size: %zu bytes", total_size);
    return fd;
}

// ---------------------------------------------------------------------------
// JNI: Load Model (With Samsung S23 FE Stability Fix)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);

    // 1. ATOMIC CLEANUP: Ensure previous model is 100% gone before allocating new RAM
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model = nullptr; }

    // 2. Model Params optimized for 8GB RAM devices
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;    // CPU-only for maximum stability on Samsung drivers
    mparams.use_mmap     = true; // CRITICAL: Prevents OOM by reading from disk on-demand
    mparams.use_mlock    = false;

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);

    if (!g_model) {
        LOGE("Failed to load model file.");
        return JNI_FALSE;
    }

    // 3. Context Params
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = 4096; // Balanced context window
    cparams.n_batch = 512;  // Efficient batching
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    // Use latest init function (b9542)
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Context initialization failed.");
        return JNI_FALSE;
    }

    // 4. Initialize Sampler chain
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.75f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(1234));

    LOGI("Model Ready. System Online.");
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// JNI: Execute Inference (With Chunked Decoding Crash-Fix)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_executeZeroCopyInference(JNIEnv* env, jobject, jstring jprompt) {
    if (!g_ctx || !g_buf || !g_model) {
        LOGE("Engine components missing.");
        return;
    }

    const char* user_input = env->GetStringUTFChars(jprompt, nullptr);
    
    // Reset buffer for new turn
    g_buf->write_pos = 0;
    g_buf->flags     = 0;
    g_buf->tokens_gen = 0;
    g_buf->tps_scaled = 0;
    memset(g_buf->data, 0, STREAM_SIZE);
    g_abort = false;

    // 1. Tokenization
    auto vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(8192);
    int n_toks = llama_tokenize(vocab, user_input, strlen(user_input), tokens.data(), 8192, true, false);
    
    if (n_toks <= 0) {
        g_buf->flags = 1;
        env->ReleaseStringUTFChars(jprompt, user_input);
        return;
    }

    // 2. FIX: CHUNKED PROMPT EVALUATION
    // This prevents the "Input Crash" by evaluating the prompt in 512-token chunks
    for (int i = 0; i < n_toks; i += 512) {
        int n_eval = std::min(512, n_toks - i);
        if (llama_decode(g_ctx, llama_batch_get_one(&tokens[i], n_eval)) != 0) {
            LOGE("Fatal: Prompt evaluation failure at chunk starting index %d", i);
            g_buf->flags = 1;
            env->ReleaseStringUTFChars(jprompt, user_input);
            return;
        }
    }

    // 3. Generation Loop
    auto start_time = std::chrono::high_resolution_clock::now();
    
    for (int i = 0; i < 2048; i++) {
        if (g_abort.load()) break;

        // Sample next token
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        // Convert token to UTF-8 text
        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, 256, 0, false);
        
        if (n > 0) {
            // Write to shared ring buffer
            size_t current_pos = g_buf->write_pos;
            if (current_pos + n < STREAM_SIZE) {
                memcpy(g_buf->data + current_pos, piece, n);
                g_buf->write_pos += n;
                g_buf->tokens_gen = i + 1;

                // Update Telemetry (TPS)
                auto now = std::chrono::high_resolution_clock::now();
                double dur = std::chrono::duration<double>(now - start_time).count();
                if (dur > 0.1) {
                    g_buf->tps_scaled = (uint32_t)(( (i + 1) / dur ) * 100);
                }
            }
        }

        // Evaluate next token
        if (llama_decode(g_ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }

    // 4. Mark finished
    g_buf->flags = 1;
    env->ReleaseStringUTFChars(jprompt, user_input);
    LOGI("Inference Completed.");
}

// ---------------------------------------------------------------------------
// JNI: Utility Helpers
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_resetContextNative(JNIEnv*, jobject) {
    if (g_ctx) {
        // v5 Migration: llama_kv_cache_clear is now llama_memory_clear
        llama_memory_clear(llama_get_memory(g_ctx), true);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gguf_ipc_EngineCore_abortInferenceNative(JNIEnv*, jobject) {
    g_abort = true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(JNIEnv*, jobject) {
    if (!g_ctx) return 0;
    // Calculate fill percentage using new position tracking API
    llama_memory_t mem = llama_get_memory(g_ctx);
    int used = (int)llama_memory_seq_pos_max(mem, 0) + 1;
    return (used * 100) / 4096;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_isInferenceDoneNative(JNIEnv*, jobject) {
    return (g_buf && (g_buf->flags & 1));
}
