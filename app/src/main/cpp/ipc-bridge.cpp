#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <vector>
#include <chrono>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define TAG "GGUF_PRO_V5"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static constexpr size_t STREAM_SIZE = 524288;
struct SharedBuffer {
    volatile uint32_t write_pos;   // 0
    volatile uint32_t flags;       // 4
    volatile uint32_t tokens_gen;  // 8
    volatile uint32_t tps_scaled;  // 12 (Tokens per sec * 100)
    char data[STREAM_SIZE];
};

// State Variables
static SharedBuffer* g_buf = nullptr;
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_sampler = nullptr;
static std::atomic<bool> g_abort{false};

// Dynamic configuration fields passed by Kotlin Auto-Detection
static int g_n_ctx = 4096;
static int g_max_new_tokens = 2048;
static float g_temperature = 0.7f;
static float g_top_p = 0.9f;
static float g_min_p = 0.05f;
static int g_n_gpu_layers = 0;
static int g_n_threads = 4;

extern "C" {

// Config Receiver from Settings/Auto-Detect Engine
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setEngineConfigNative(
    JNIEnv* env, jobject thiz,
    jint nCtx, jint maxNewTokens, jfloat temperature,
    jfloat topP, jfloat minP, jint nGpuLayers, jint nThreads, jint seed
) {
    g_n_ctx = nCtx;
    g_max_new_tokens = maxNewTokens;
    g_temperature = temperature;
    g_top_p = topP;
    g_min_p = minP;
    g_n_gpu_layers = nGpuLayers;
    g_n_threads = nThreads;
    LOGI("Engine parameters set: GPU Layers=%d, Threads=%d, Context=%d", g_n_gpu_layers, g_n_threads, g_n_ctx);
}

JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_initializeSharedMemoryNative(JNIEnv* env, jobject thiz) {
    int fd = ASharedMemory_create("gguf_pro_shm", sizeof(SharedBuffer));
    if (fd < 0) return -1;
    g_buf = (SharedBuffer*)mmap(NULL, sizeof(SharedBuffer), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    memset(g_buf, 0, sizeof(SharedBuffer));
    return fd;
}

JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject thiz, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = g_n_gpu_layers; // Dynamically adjusted!

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) return JNI_FALSE;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = g_n_ctx;
    cparams.n_threads = g_n_threads;
    cparams.n_threads_batch = g_n_threads;
    
    // 8-bit KV Cache Quantization (Saves 50% VRAM)
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) return JNI_FALSE;
    
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_min_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_temperature));
    
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_executeZeroCopyInference(JNIEnv* env, jobject thiz, jstring prompt) {
    if (!g_ctx || !g_buf) return;
    const char* input = env->GetStringUTFChars(prompt, nullptr);
    
    g_buf->write_pos = 0;
    g_buf->flags = 0;
    g_abort = false;

    auto vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(g_n_ctx);
    int n_toks = llama_tokenize(vocab, input, strlen(input), tokens.data(), g_n_ctx, true, false);
    
    if (n_toks < 0) {
        env->ReleaseStringUTFChars(prompt, input);
        return;
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), n_toks);
    llama_decode(g_ctx, batch);

    auto start_time = std::chrono::high_resolution_clock::now();

    for (int i = 0; i < g_max_new_tokens; i++) {
        if (g_abort) break;
        
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, 256, 0, false);
        
        if (g_buf->write_pos + n < STREAM_SIZE) {
            memcpy(g_buf->data + g_buf->write_pos, piece, n);
            g_buf->write_pos += n;
            g_buf->tokens_gen = i + 1;
            
            auto now = std::chrono::high_resolution_clock::now();
            double duration = std::chrono::duration<double>(now - start_time).count();
            if (duration > 0) g_buf->tps_scaled = (uint32_t)((i + 1) / duration * 100);
        }

        llama_batch b = llama_batch_get_one(&tok, 1);
        llama_decode(g_ctx, b);
    }

    g_buf->flags = 1; 
    env->ReleaseStringUTFChars(prompt, input);
}

JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_abortInferenceNative(JNIEnv* env, jobject thiz) {
    g_abort = true;
}

JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(JNIEnv* env, jobject thiz) {
    if (!g_ctx) return 0;
    llama_memory_t mem = llama_get_memory(g_ctx);
    int used = (int)llama_memory_seq_pos_max(mem, 0) + 1;
    return (used * 100) / g_n_ctx;
}

// Dummy fallbacks for auxiliary v5 declarations to avoid layout link errors on initialization
JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_getWritePosNative(JNIEnv* env, jobject thiz) { return g_buf ? g_buf->write_pos : 0; }
JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_isInferenceDoneNative(JNIEnv* env, jobject thiz) { return g_buf ? (g_buf->flags == 1) : true; }
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_getModelInfoNative(JNIEnv* env, jobject thiz) { return env->NewStringUTF("<text>"); }
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_benchmarkNative(JNIEnv* env, jobject thiz, jint p, jint t) { return env->NewStringUTF("<bench>"); }
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setRepeatPenaltyNative(JNIEnv* env, jobject thiz, jfloat r, jfloat f, jfloat p) {}
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_exportChatHistoryNative(JNIEnv* env, jobject thiz) { return env->NewStringUTF("<history>"); }
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setSystemPromptNative(JNIEnv* env, jobject thiz, jstring prompt) {}
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_resetContextNative(JNIEnv* env, jobject thiz) {}

}
