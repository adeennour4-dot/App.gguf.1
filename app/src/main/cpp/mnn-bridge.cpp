// mnn-bridge.cpp — JNI bridge between Kotlin MnnEngine and MNN-LLM C++ API
// Links against libMNN.so and libMNN_Express.so built from alibaba/MNN

#include <jni.h>
#include <string>
#include <sstream>
#include <atomic>
#include <mutex>
#include <chrono>
#include <android/log.h>

#define LOG_TAG "MnnBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// MNN-LLM C++ API
#include "llm/llm.hpp"

using namespace MNN::Transformer;

static Llm* g_llm = nullptr;
static std::atomic<bool> g_model_loaded{false};
static std::atomic<bool> g_stop_requested{false};
static std::atomic<bool> g_inference_done{true};
static std::atomic<int> g_tokens_generated{0};
static std::mutex g_mutex;
static std::string g_stream_buffer;
static std::string g_full_response;
static std::vector<std::pair<std::string, std::string>> g_history;
static std::string g_system_prompt = "You are a helpful assistant.";

// Helper: build simple JSON string from key-value pairs
static std::string buildJson(std::initializer_list<std::pair<const char*, std::string>> items) {
    std::string json = "{";
    bool first = true;
    for (auto& [k, v] : items) {
        if (!first) json += ",";
        first = false;
        json += "\"" + std::string(k) + "\":" + v;
    }
    json += "}";
    return json;
}

static std::string quote(const std::string& s) {
    return "\"" + s + "\"";
}

static std::string num(float f) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%g", (double)f);
    return buf;
}

static std::string num(int i) {
    return std::to_string(i);
}

static std::string bol(bool b) {
    return b ? "true" : "false";
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_MnnEngine_mnnLoadModel(JNIEnv* env, jobject thiz, jstring path) {
    const char* model_path = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading MNN model: %s", model_path);

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_llm) {
        delete g_llm;
        g_llm = nullptr;
    }

    g_llm = Llm::createLLM(model_path);
    env->ReleaseStringUTFChars(path, model_path);

    if (g_llm == nullptr) {
        LOGE("createLLM failed");
        return JNI_FALSE;
    }

    // Apply default config as JSON string
    g_llm->set_config("{\"use_mmap\":true,\"precision\":\"low\",\"backend_type\":\"cpu\"}");

    if (!g_llm->load()) {
        LOGE("Model load() failed");
        delete g_llm;
        g_llm = nullptr;
        return JNI_FALSE;
    }

    g_model_loaded = true;
    g_history.clear();
    g_history.emplace_back("system", g_system_prompt);
    LOGI("MNN model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_MnnEngine_mnnExecuteInference(JNIEnv* env, jobject thiz, jstring prompt) {
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Executing MNN inference");

    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop_requested = false;
    g_inference_done = false;
    g_tokens_generated = 0;
    g_stream_buffer.clear();
    g_full_response.clear();

    g_history.emplace_back("user", std::string(prompt_str));
    env->ReleaseStringUTFChars(prompt, prompt_str);

    int max_tokens = 4096;
    int tokens_generated = 0;
    std::string token_buffer;

    for (int i = 0; i < max_tokens && !g_stop_requested; ++i) {
        if (g_stop_requested) break;
        
        // Generate one token at a time for streaming
        g_llm->generate(1);
        tokens_generated = i + 1;
        g_tokens_generated = tokens_generated;  // Update for polling
        
        auto* ctx = g_llm->getContext();
        if (ctx != nullptr) {
            // Check if we have output from generate
            if (ctx->output_str && strlen(ctx->output_str) > 0) {
                token_buffer = std::string(ctx->output_str);
                g_full_response += token_buffer;
                g_stream_buffer = token_buffer;
            }
            if (ctx->status == LlmStatus::NORMAL_FINISHED ||
                ctx->status == LlmStatus::MAX_TOKENS_FINISHED) {
                break;
            }
        }
    }

    g_history.emplace_back("assistant", g_full_response);
    g_inference_done = true;
    LOGI("MNN inference done, tokens=%d", tokens_generated);
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_MnnEngine_mnnAbortInference(JNIEnv* env, jobject thiz) {
    LOGI("MNN abort requested");
    g_stop_requested = true;
}

JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_MnnEngine_mnnReadPartialStream(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_stream_buffer.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_MnnEngine_mnnReadTokenStream(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_full_response.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_MnnEngine_mnnIsInferenceDone(JNIEnv* env, jobject thiz) {
    return g_inference_done ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_gguf_ipc_MnnEngine_mnnGetTokensGenerated(JNIEnv* env, jobject thiz) {
    return g_tokens_generated.load();
}

JNIEXPORT jint JNICALL
Java_com_gguf_ipc_MnnEngine_mnnGetKvCacheUsage(JNIEnv* env, jobject thiz) {
    if (!g_llm || !g_model_loaded) return 0;
    auto* ctx = g_llm->getContext();
    if (ctx == nullptr) return 0;
    // Use prompt_len + gen_seq_len as token count estimate
    int used_tokens = ctx->prompt_len + ctx->gen_seq_len;
    int ctx_size = 8192;  // Default context size
    return (used_tokens > 0 && ctx_size > 0) ? (int)((float)used_tokens / ctx_size * 100.0f) : 0;
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_MnnEngine_mnnResetContext(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_history.clear();
    g_history.emplace_back("system", g_system_prompt);
    g_stream_buffer.clear();
    g_full_response.clear();
    g_tokens_generated = 0;
    g_inference_done = true;
    if (g_llm) g_llm->reset();
    LOGI("MNN context reset");
}

JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_MnnEngine_mnnGetModelInfo(JNIEnv* env, jobject thiz) {
    if (!g_llm || !g_model_loaded) {
        return env->NewStringUTF("{}");
    }
    auto* ctx = g_llm->getContext();
    std::string info = buildJson({
        {"engine", quote("MNN")},
        {"model_loaded", bol(true)},
        {"prompt_len", ctx ? num(ctx->prompt_len) : num(0)},
        {"gen_seq_len", ctx ? num(ctx->gen_seq_len) : num(0)}
    });
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_MnnEngine_mnnBenchmark(JNIEnv* env, jobject thiz, jint ppTokens, jint tgTokens) {
    if (!g_llm || !g_model_loaded) {
        return env->NewStringUTF("{\"error\":\"Model not loaded\"}");
    }

    const int tok = 16;

    std::vector<int> tokens(ppTokens, tok);
    auto start = std::chrono::high_resolution_clock::now();
    g_llm->response(tokens, nullptr, nullptr, 1);
    auto end = std::chrono::high_resolution_clock::now();
    auto* ctx = g_llm->getContext();
    float prefill_ms = ctx ? (float)ctx->prefill_us / 1000.0f : 0;
    float prefill_tps = (prefill_ms > 0) ? (float)ppTokens / (prefill_ms / 1000.0f) : 0;

    std::vector<int> gen_tokens(1, tok);
    start = std::chrono::high_resolution_clock::now();
    g_llm->response(gen_tokens, nullptr, nullptr, tgTokens);
    end = std::chrono::high_resolution_clock::now();
    ctx = g_llm->getContext();
    float decode_ms = ctx ? (float)ctx->decode_us / 1000.0f : 0;
    float decode_tps = (decode_ms > 0) ? (float)tgTokens / (decode_ms / 1000.0f) : 0;

    std::string result = buildJson({
        {"prefill_tokens", num((int)ppTokens)},
        {"prefill_ms", num(prefill_ms)},
        {"prefill_tps", num(prefill_tps)},
        {"decode_tokens", num((int)tgTokens)},
        {"decode_ms", num(decode_ms)},
        {"decode_tps", num(decode_tps)}
    });

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_MnnEngine_mnnExportChatHistory(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string export_str;
    for (auto& item : g_history) {
        export_str += "[" + item.first + "]: " + item.second + "\n";
    }
    return env->NewStringUTF(export_str.c_str());
}

} // extern "C"
