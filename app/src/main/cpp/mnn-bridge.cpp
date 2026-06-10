// mnn-bridge.cpp — JNI bridge between Kotlin MnnEngine and MNN-LLM C++ API
// Links against libMNN.so and libMNN_Express.so built from alibaba/MNN

#include <jni.h>
#include <string>
#include <sstream>
#include <atomic>
#include <mutex>
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

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_MnnEngine_mnnLoadModel(JNIEnv* env, jobject thiz, jstring path) {
    const char* model_path = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading MNN model: %s", model_path);

    std::lock_guard<std::mutex> lock(g_mutex);

    // Clean up previous model
    if (g_llm) {
        delete g_llm;
        g_llm = nullptr;
    }

    g_llm = Llm::createLLM(model_path);
    env->ReleaseStringUTFChars(path, model_path);

    if (g_llm == nullptr) {
        LOGE("createLLM failed for path: %s", model_path);
        return JNI_FALSE;
    }

    // Apply default config
    nlohmann::json config;
    config["use_mmap"] = true;
    config["precision"] = "low";
    config["backend_type"] = "cpu";
    std::string config_str = config.dump();
    g_llm->set_config(config_str);

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
    LOGI("Executing MNN inference: %s", prompt_str);

    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop_requested = false;
    g_inference_done = false;
    g_tokens_generated = 0;
    g_stream_buffer.clear();
    g_full_response.clear();

    // Add user message to history
    g_history.emplace_back("user", std::string(prompt_str));
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // Create a stringstream to capture output
    std::ostringstream output_stream;

    // Run inference — response() does prefill, then we decode token by token
    g_llm->response(g_history, &output_stream, "<eop>", 0);

    // Now decode tokens one at a time for streaming
    int max_tokens = 4096;
    for (int i = 0; i < max_tokens && !g_stop_requested; ++i) {
        g_llm->generate(1);
        g_tokens_generated++;

        // Check if we hit end-of-pattern
        auto* ctx = g_llm->getContext();
        if (ctx != nullptr) {
            if (ctx->status == LlmStatus::NORMAL_FINISHED ||
                ctx->status == LlmStatus::MAX_TOKENS_FINISHED) {
                break;
            }
        }
    }

    // Get the full response from the output stream
    g_full_response = output_stream.str();
    g_stream_buffer = g_full_response;

    // Add assistant response to history
    g_history.emplace_back("assistant", g_full_response);

    g_inference_done = true;
    LOGI("MNN inference done, tokens=%d", g_tokens_generated.load());
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
    // Rough estimate based on history size vs context window
    int history_tokens = 0;
    for (auto& item : g_history) {
        history_tokens += (int)item.second.size() / 4; // rough token estimate
    }
    int ctx_size = 8192; // default
    return (int)((float)history_tokens / ctx_size * 100.0f);
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
    nlohmann::json info;
    info["engine"] = "MNN";
    info["model_loaded"] = true;
    if (ctx) {
        info["prompt_len"] = ctx->prompt_len;
        info["gen_seq_len"] = ctx->gen_seq_len;
    }
    return env->NewStringUTF(info.dump().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_MnnEngine_mnnBenchmark(JNIEnv* env, jobject thiz, jint ppTokens, jint tgTokens) {
    if (!g_llm || !g_model_loaded) {
        return env->NewStringUTF("{\"error\": \"Model not loaded\"}");
    }

    nlohmann::json result;
    const int tok = 16;

    // Prefill benchmark
    std::vector<int> tokens(ppTokens, tok);
    auto start = std::chrono::high_resolution_clock::now();
    g_llm->response(tokens, nullptr, nullptr, 1);
    auto end = std::chrono::high_resolution_clock::now();
    auto* ctx = g_llm->getContext();
    float prefill_ms = ctx ? ctx->prefill_us / 1000.0f : 0;
    float prefill_tps = (prefill_ms > 0) ? ppTokens / (prefill_ms / 1000.0f) : 0;

    // Decode benchmark
    std::vector<int> gen_tokens(1, tok);
    start = std::chrono::high_resolution_clock::now();
    g_llm->response(gen_tokens, nullptr, nullptr, tgTokens);
    end = std::chrono::high_resolution_clock::now();
    ctx = g_llm->getContext();
    float decode_ms = ctx ? ctx->decode_us / 1000.0f : 0;
    float decode_tps = (decode_ms > 0) ? tgTokens / (decode_ms / 1000.0f) : 0;

    result["prefill_tokens"] = ppTokens;
    result["prefill_ms"] = prefill_ms;
    result["prefill_tps"] = prefill_tps;
    result["decode_tokens"] = tgTokens;
    result["decode_ms"] = decode_ms;
    result["decode_tps"] = decode_tps;

    return env->NewStringUTF(result.dump().c_str());
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
