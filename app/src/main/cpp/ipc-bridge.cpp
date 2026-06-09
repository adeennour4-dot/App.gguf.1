#include <jni.h>
#include <android/sharedmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <vector>
#include <string>
#include <chrono>
#include <cstring>
#include <sstream>
#include <android/log.h>
#include "llama.h"

#define TAG "GGUF_PRO_V5"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Shared ring buffer ───────────────────────────────────────────────────────
static constexpr size_t STREAM_SIZE = 524288; // 512 KB
struct SharedBuffer {
    volatile uint32_t write_pos;   // offset 0
    volatile uint32_t flags;       // offset 4  (1 = done)
    volatile uint32_t tokens_gen;  // offset 8
    volatile uint32_t tps_scaled;  // offset 12 (tps * 100)
    char data[STREAM_SIZE];
};

// ─── Global state ─────────────────────────────────────────────────────────────
static SharedBuffer*  g_buf     = nullptr;
static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static llama_sampler* g_sampler = nullptr;
static std::atomic<bool> g_abort{false};

// ─── Config globals ──────────────────────────────────────────────────────────
static int   g_n_ctx         = 4096;
static int   g_max_new_tokens= 2048;
static float g_temperature   = 0.7f;
static float g_top_p         = 0.9f;
static float g_min_p         = 0.05f;
static int   g_n_gpu_layers  = 0;
static int   g_n_threads     = 4;
static int   g_seed          = -1;
static float g_repeat_pen    = 1.1f;
static float g_freq_pen      = 0.0f;
static float g_pres_pen      = 0.0f;

// ─── Conversation history ────────────────────────────────────────────────────
struct ChatTurn { std::string role; std::string content; };
static std::string         g_system_prompt = "You are a helpful, concise assistant running on-device.";
static std::vector<ChatTurn> g_history;

// ─── Helpers ─────────────────────────────────────────────────────────────────
static void rebuild_sampler() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(
      -1, g_repeat_pen, g_freq_pen, g_pres_pen));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(g_min_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(g_top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(g_temperature));
    // FIX: must add a final token-selector or sample() crashes / returns garbage
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(g_seed));
}

// Build the full prompt string from system prompt + history + current user turn
static std::string build_prompt(const std::string& user_msg) {
    // Try llama.cpp's built-in chat template first
    std::vector<llama_chat_message> msgs;
    std::vector<std::string> contents;  // keep strings alive

    // system
    contents.push_back(g_system_prompt);
    msgs.push_back({"system", contents.back().c_str()});

    // history
    for (auto& turn : g_history) {
        contents.push_back(turn.content);
        msgs.push_back({turn.role.c_str(), contents.back().c_str()});
    }

    // current user
    contents.push_back(user_msg);
    msgs.push_back({"user", contents.back().c_str()});

    // Apply template (nullptr = use model's embedded template)
    std::vector<char> buf(8192 * 4);
    int n = llama_chat_apply_template(g_model,
                                      msgs.data(), msgs.size(),
                                      true, buf.data(), (int)buf.size());
    if (n > 0 && n < (int)buf.size()) {
        return std::string(buf.data(), n);
    }

    // Fallback: ChatML format (works for Qwen, Mistral, most instruct models)
    std::string out;
    out += "<|im_start|>system\n" + g_system_prompt + "<|im_end|>\n";
    for (auto& turn : g_history) {
        out += "<|im_start|>" + turn.role + "\n" + turn.content + "<|im_end|>\n";
    }
    out += "<|im_start|>user\n" + user_msg + "<|im_end|>\n";
    out += "<|im_start|>assistant\n";
    return out;
}

extern "C" {

// ─── Config ──────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setEngineConfigNative(
    JNIEnv*, jobject, jint nCtx, jint maxNewTokens, jfloat temp,
    jfloat topP, jfloat minP, jint gpuLayers, jint nThreads, jint seed)
{
    g_n_ctx          = nCtx;
    g_max_new_tokens = maxNewTokens;
    g_temperature    = temp;
    g_top_p          = topP;
    g_min_p          = minP;
    g_n_gpu_layers   = gpuLayers;
    g_n_threads      = nThreads;
    g_seed           = seed;
    LOGI("Config: gpu=%d threads=%d ctx=%d temp=%.2f", gpuLayers, nThreads, nCtx, temp);
}

JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setRepeatPenaltyNative(
    JNIEnv*, jobject, jfloat r, jfloat f, jfloat p)
{
    g_repeat_pen = r; g_freq_pen = f; g_pres_pen = p;
}

JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_setSystemPromptNative(
    JNIEnv* env, jobject, jstring prompt)
{
    const char* s = env->GetStringUTFChars(prompt, nullptr);
    g_system_prompt = s;
    env->ReleaseStringUTFChars(prompt, s);
}

// ─── Init ────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_initializeSharedMemoryNative(
    JNIEnv*, jobject)
{
    // FIX: llama_backend_init() must be called once before any llama.cpp API
    llama_backend_init();

    int fd = ASharedMemory_create("gguf_pro_shm", sizeof(SharedBuffer));
    if (fd < 0) { LOGE("ASharedMemory_create failed"); return -1; }
    g_buf = (SharedBuffer*)mmap(NULL, sizeof(SharedBuffer),
                                PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (g_buf == MAP_FAILED) { LOGE("mmap failed"); g_buf = nullptr; return -1; }
    memset(g_buf, 0, sizeof(SharedBuffer));
    LOGI("Shared ring buffer created, fd=%d", fd);
    return fd;
}

// ─── Model load ──────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_loadGgufModelNative(
    JNIEnv* env, jobject, jstring path)
{
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading model: %s  gpu_layers=%d", filePath, g_n_gpu_layers);

    // Free existing resources
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_history.clear();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = g_n_gpu_layers;

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);
    if (!g_model) { LOGE("llama_model_load_from_file failed"); return JNI_FALSE; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx             = g_n_ctx;
    cparams.n_threads         = g_n_threads;
    cparams.n_threads_batch   = g_n_threads;
    // 8-bit KV quantization — saves ~50% VRAM on long contexts
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) { LOGE("llama_init_from_model failed"); return JNI_FALSE; }

    // FIX: build sampler chain WITH a final dist sampler
    rebuild_sampler();

    LOGI("Model loaded OK. ctx=%d, sampler ready.", g_n_ctx);
    return JNI_TRUE;
}

// ─── Inference ───────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_executeZeroCopyInference(
    JNIEnv* env, jobject, jstring promptJ)
{
    if (!g_ctx || !g_buf || !g_sampler) {
        LOGE("executeZeroCopyInference called before model loaded");
        if (g_buf) g_buf->flags = 1;   // mark done so Kotlin doesn't hang
        return;
    }

    const char* userInput = env->GetStringUTFChars(promptJ, nullptr);
    std::string userMsg(userInput);
    env->ReleaseStringUTFChars(promptJ, userInput);

    // Reset output buffer
    g_buf->write_pos  = 0;
    g_buf->flags      = 0;
    g_buf->tokens_gen = 0;
    g_abort = false;

    // Rebuild sampler in case penalties/temperature changed since last turn
    rebuild_sampler();

    // FIX: Clear KV cache before each turn — avoids stale context / OOM crash
    llama_kv_self_clear(g_ctx);

    // Build full conversation prompt
    std::string fullPrompt = build_prompt(userMsg);
    LOGI("Prompt built, len=%zu", fullPrompt.size());

    auto vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(g_n_ctx);
    int n_toks = llama_tokenize(vocab, fullPrompt.c_str(), (int)fullPrompt.size(),
                                tokens.data(), g_n_ctx, true, false);
    if (n_toks < 0) {
        LOGE("Tokenize failed, n_toks=%d", n_toks);
        g_buf->flags = 1;
        return;
    }

    // Prefill
    llama_batch batch = llama_batch_get_one(tokens.data(), n_toks);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode (prefill) failed");
        g_buf->flags = 1;
        return;
    }

    auto start = std::chrono::high_resolution_clock::now();
    std::string response;

    // Generate
    for (int i = 0; i < g_max_new_tokens; i++) {
        if (g_abort) break;

        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256] = {};
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0 && g_buf->write_pos + n < STREAM_SIZE) {
            memcpy(g_buf->data + g_buf->write_pos, piece, n);
            g_buf->write_pos += n;
            g_buf->tokens_gen  = i + 1;
            response.append(piece, n);

            auto now     = std::chrono::high_resolution_clock::now();
            double secs  = std::chrono::duration<double>(now - start).count();
            if (secs > 0) g_buf->tps_scaled = (uint32_t)((i + 1) / secs * 100);
        }

        llama_batch b2 = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, b2) != 0) {
            LOGE("llama_decode (gen step %d) failed", i);
            break;
        }
    }

    // Store turn in history for multi-turn context
    g_history.push_back({"user",      userMsg});
    g_history.push_back({"assistant", response});

    g_buf->flags = 1;  // signal done
    LOGI("Inference done: %d tokens", (int)g_history.back().content.size());
}

// ─── Abort / Reset ───────────────────────────────────────────────────────────
JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_abortInferenceNative(
    JNIEnv*, jobject) { g_abort = true; }

JNIEXPORT void JNICALL Java_com_gguf_ipc_EngineCore_resetContextNative(
    JNIEnv*, jobject)
{
    g_history.clear();
    if (g_ctx) llama_kv_cache_clear(g_ctx);
    if (g_buf) { g_buf->write_pos = 0; g_buf->flags = 1; g_buf->tokens_gen = 0; }
    LOGI("Context reset.");
}

// ─── KV / position ───────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_getKvCacheUsageNative(
    JNIEnv*, jobject)
{
    if (!g_ctx) return 0;
    llama_memory_t mem = llama_get_memory(g_ctx);
    int used = (int)llama_memory_seq_pos_max(mem, 0) + 1;
    return (g_n_ctx > 0) ? (used * 100) / g_n_ctx : 0;
}

JNIEXPORT jint JNICALL Java_com_gguf_ipc_EngineCore_getWritePosNative(
    JNIEnv*, jobject) { return g_buf ? (int)g_buf->write_pos : 0; }

JNIEXPORT jboolean JNICALL Java_com_gguf_ipc_EngineCore_isInferenceDoneNative(
    JNIEnv*, jobject) { return g_buf ? (g_buf->flags == 1) : JNI_TRUE; }

// ─── Model info ──────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_getModelInfoNative(
    JNIEnv* env, jobject)
{
    if (!g_model) return env->NewStringUTF("{}");
    std::ostringstream json;
    json << "{";

    // Architecture description
    const char* desc = llama_model_desc(g_model);
    if (desc) {
        json << "\"description\":\"" << desc << "\",";
    }
    json << "\"parameters\":" << llama_model_n_params(g_model) << ",";
    json << "\"size_bytes\":"  << llama_model_size(g_model)    << ",";
    json << "\"context_train\":" << llama_model_n_ctx_train(g_model) << ",";

    // Model metadata key-value pairs
    int count = llama_model_meta_count(g_model);
    char key[512], val[512];
    bool first = true;
    for (int i = 0; i < count && i < 40; i++) {
        if (llama_model_meta_key_by_index(g_model, i, key, sizeof(key)) >= 0 &&
            llama_model_meta_val_str_by_index(g_model, i, val, sizeof(val)) >= 0)
        {
            if (!first) json << ",";
            // escape quotes in value
            std::string v(val);
            size_t pos = 0;
            while ((pos = v.find('"', pos)) != std::string::npos) {
                v.replace(pos, 1, "\\\""); pos += 2;
            }
            json << "\"" << key << "\":\"" << v << "\"";
            first = false;
        }
    }
    json << "}";
    return env->NewStringUTF(json.str().c_str());
}

// ─── Benchmark ───────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_benchmarkNative(
    JNIEnv* env, jobject, jint ppTokens, jint tgTokens)
{
    if (!g_ctx || !g_model) return env->NewStringUTF("{\"pp_tps\":0,\"tg_tps\":0}");

    llama_kv_cache_clear(g_ctx);
    rebuild_sampler();

    auto vocab = llama_model_get_vocab(g_model);

    // PP benchmark: tokenize a test sentence and decode it
    const std::string test_prompt(ppTokens, 'A');   // simple fill
    std::vector<llama_token> toks(ppTokens + 4);
    int n = llama_tokenize(vocab, test_prompt.c_str(), (int)test_prompt.size(),
                           toks.data(), (int)toks.size(), true, false);
    if (n <= 0) return env->NewStringUTF("{\"pp_tps\":0,\"tg_tps\":0}");

    auto t0 = std::chrono::high_resolution_clock::now();
    llama_batch b = llama_batch_get_one(toks.data(), n);
    llama_decode(g_ctx, b);
    auto t1 = std::chrono::high_resolution_clock::now();
    double pp_sec = std::chrono::duration<double>(t1 - t0).count();

    // TG benchmark
    auto t2 = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < tgTokens; i++) {
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        llama_batch b2 = llama_batch_get_one(&tok, 1);
        llama_decode(g_ctx, b2);
    }
    auto t3 = std::chrono::high_resolution_clock::now();
    double tg_sec = std::chrono::duration<double>(t3 - t2).count();

    llama_kv_cache_clear(g_ctx);

    char out[128];
    snprintf(out, sizeof(out), "{\"pp_tps\":%.1f,\"tg_tps\":%.1f}",
             pp_sec > 0 ? n / pp_sec : 0.0,
             tg_sec > 0 ? tgTokens / tg_sec : 0.0);
    return env->NewStringUTF(out);
}

// ─── Chat history export ─────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL Java_com_gguf_ipc_EngineCore_exportChatHistoryNative(
    JNIEnv* env, jobject)
{
    std::string out;
    for (auto& turn : g_history) {
        out += (turn.role == "user" ? "User: " : "Assistant: ");
        out += turn.content + "\n\n";
    }
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
