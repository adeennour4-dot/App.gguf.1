 
#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "GGUF_V5_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static constexpr size_t STREAM_SIZE = 524288;
struct SharedBuffer {
    volatile uint32_t write_pos;
    volatile uint32_t flags;
    volatile uint32_t tokens_gen;
    volatile uint32_t tps_scaled;
    char data[STREAM_SIZE];
};

static SharedBuffer* g_buf = nullptr;
static llama_model*  g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_sampler = nullptr;
static std::atomic<bool> g_abort{false};

extern "C" {

JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_initializeSharedMemoryNative(JNIEnv*, jobject) {
    int fd = ASharedMemory_create("gguf_v5_shm", sizeof(SharedBuffer));
    if (fd < 0) return -1;
    g_buf = (SharedBuffer*)mmap(NULL, sizeof(SharedBuffer), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    memset(g_buf, 0, sizeof(SharedBuffer));
    return fd;
}

JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    
    // ATOMIC TEARDOWN for Samsung OOM Fix
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true; // Essential for S23 FE memory pressure
    
    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) return JNI_FALSE;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096; // Lowered for 8GB RAM devices
    g_ctx = llama_init_from_model(g_model, cparams);
    
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_executeZeroCopyInference(JNIEnv* env, jobject, jstring prompt) {
    if (!g_ctx || !g_buf) return;
    const char* input = env->GetStringUTFChars(prompt, nullptr);
    g_buf->write_pos = 0; g_buf->flags = 0; g_abort = false;

    auto vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(4096);
    int n_toks = llama_tokenize(vocab, input, strlen(input), tokens.data(), 4096, true, false);
    llama_decode(g_ctx, llama_batch_get_one(tokens.data(), n_toks));

    auto start = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < 1024; i++) {
        if (g_abort) break;
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[128];
        int n = llama_token_to_piece(vocab, tok, piece, 128, 0, false);
        if (g_buf->write_pos + n < STREAM_SIZE) {
            memcpy(g_buf->data + g_buf->write_pos, piece, n);
            g_buf->write_pos += n;
            g_buf->tokens_gen = i + 1;
            auto now = std::chrono::high_resolution_clock::now();
            double dur = std::chrono::duration<double>(now - start).count();
            if (dur > 0) g_buf->tps_scaled = (uint32_t)((i + 1) / dur * 100);
        }
        llama_decode(g_ctx, llama_batch_get_one(&tok, 1));
    }
    g_buf->flags = 1;
    env->ReleaseStringUTFChars(prompt, input);
}

JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_resetContextNative(JNIEnv*, jobject) {
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
}
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_abortInferenceNative(JNIEnv*, jobject) { g_abort = true; }
JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_isInferenceDoneNative(JNIEnv*, jobject) { return (g_buf && (g_buf->flags & 1)) ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(JNIEnv*, jobject) { 
    if (!g_ctx) return 0;
    return (int)llama_memory_seq_pos_max(llama_get_memory(g_ctx), 0) * 100 / 4096;
}
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_getModelInfoNative(JNIEnv* env, jobject) { return env->NewStringUTF("{\"arch\":\"v5_pro\"}"); }
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_benchmarkNative(JNIEnv* env, jobject, jint, jint) { return env->NewStringUTF("{}"); }
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_exportChatHistoryNative(JNIEnv* env, jobject) { return env->NewStringUTF(""); }
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setSystemPromptNative(JNIEnv*, jobject, jstring) {}
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setEngineConfigNative(JNIEnv*, jobject, jint, jint, jfloat, jfloat, jfloat, jint, jint, jint) {}
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setRepeatPenaltyNative(JNIEnv*, jobject, jfloat, jfloat, jfloat) {}
}
