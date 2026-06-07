
#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <vector>
#include <android/log.h>
#include "llama.h"

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
    int fd = ASharedMemory_create("gguf_pro_shm", sizeof(SharedBuffer));
    if (fd < 0) return -1;
    g_buf = (SharedBuffer*)mmap(NULL, sizeof(SharedBuffer), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    memset(g_buf, 0, sizeof(SharedBuffer));
    return fd;
}

JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) return JNI_FALSE;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
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
    std::vector<llama_token> tokens(8192);
    int n_toks = llama_tokenize(vocab, input, strlen(input), tokens.data(), 8192, true, false);

    for (int i = 0; i < n_toks; i += 512) {
        int n_eval = std::min(512, n_toks - i);
        llama_decode(g_ctx, llama_batch_get_one(&tokens[i], n_eval));
    }

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
            auto now = std::chrono::high_resolution_clock::now();
            double dur = std::chrono::duration<double>(now - start).count();
            if (dur > 0) g_buf->tps_scaled = (uint32_t)((i + 1) / dur * 100);
        }
        if (llama_decode(g_ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    g_buf->flags = 1;
    env->ReleaseStringUTFChars(prompt, input);
}

JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_isInferenceDoneNative(JNIEnv*, jobject) { return (g_buf && (g_buf->flags & 1)); }
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_resetContextNative(JNIEnv*, jobject) { if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true); }
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_abortInferenceNative(JNIEnv*, jobject) { g_abort = true; }
JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(JNIEnv*, jobject) { 
    if (!g_ctx) return 0;
    return (int)llama_memory_seq_pos_max(llama_get_memory(g_ctx), 0) * 100 / 4096;
}
}
