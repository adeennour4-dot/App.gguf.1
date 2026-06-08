#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <vector>
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

static SharedBuffer* g_buf = nullptr;
static llama_model*  g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_sampler = nullptr;
static std::atomic<bool> g_abort{false};

extern "C" {

JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_initializeSharedMemoryNative(JNIEnv*, jobject) {
    int fd = ASharedMemory_create("gguf_pro_shm", sizeof(SharedBuffer));
    if (fd < 0) return -1;
    g_buf = (SharedBuffer*)mmap(NULL, sizeof(SharedBuffer), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    memset(g_buf, 0, sizeof(SharedBuffer));
    return fd;
}

JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) return JNI_FALSE;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 8192;
    
    // SPECIAL FEATURE: 8-bit KV Cache Quantization (Saves 50% VRAM)
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;

    g_ctx = llama_init_from_model(g_model, cparams);
    
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.8f));
    
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_executeZeroCopyInference(JNIEnv* env, jobject, jstring prompt) {
    if (!g_ctx || !g_buf) return;
    const char* input = env->GetStringUTFChars(prompt, nullptr);
    
    g_buf->write_pos = 0;
    g_buf->flags = 0;
    g_abort = false;

    auto vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(8192);
    int n_toks = llama_tokenize(vocab, input, strlen(input), tokens.data(), 8192, true, false);
    
    llama_batch batch = llama_batch_get_one(tokens.data(), n_toks);
    llama_decode(g_ctx, batch);

    auto start_time = std::chrono::high_resolution_clock::now();

    for (int i = 0; i < 2048; i++) {
        if (g_abort) break;
        
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, 256, 0, false);
        
        if (g_buf->write_pos + n < STREAM_SIZE) {
            memcpy(g_buf->data + g_buf->write_pos, piece, n);
            g_buf->write_pos += n;
            g_buf->tokens_gen = i + 1;
            
            // Calculate real-time TPS
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

JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(JNIEnv*, jobject) {
    if (!g_ctx) return 0;
    llama_memory_t mem = llama_get_memory(g_ctx);
    int used = (int)llama_memory_seq_pos_max(mem, 0) + 1;
    return (used * 100) / 8192;
}

}
