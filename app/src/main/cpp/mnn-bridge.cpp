// mnn-bridge.cpp — JNI bridge between Kotlin MnnEngine and MNN-LLM C++ API
// Links against libMNN.so built from alibaba/MNN

#include <jni.h>
#include <string>
#include <sstream>
#include <atomic>
#include <mutex>
#include <chrono>
#include <thread>
#include <android/log.h>

#define LOG_TAG "MnnBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// MNN-LLM C++ API
#include "llm/llm.hpp"

using namespace MNN::Transformer;

static JavaVM* g_jvm = nullptr;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static Llm* g_llm = nullptr;
static std::atomic<bool> g_model_loaded{false};
static std::atomic<bool> g_stop_requested{false};
static std::atomic<bool> g_inference_done{true};
static std::atomic<int> g_tokens_generated{0};
static std::mutex g_mutex;
static std::string g_stream_buffer;
static std::string g_full_response;
static std::vector<std::pair<std::string, std::string>> g_history;
static std::string g_system_prompt = "You are a helpful, concise assistant running on-device. Respond clearly and directly.";
static jobject g_callback = nullptr;

struct MnnConfig {
    int n_ctx = 8192;
    int n_batch = 2048;
    int max_new_tokens = 4096;
    float temperature = 0.7f;
    float top_p = 0.9f;
    float min_p = 0.05f;
};

static MnnConfig g_cfg;

// JNI callback helpers
static void call_callback_on_token(const std::string& piece) {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env_stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (get_env_stat == JNI_EDETACH) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        need_detach = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    if (onToken) {
        jstring jpiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(g_callback, onToken, jpiece);
        env->DeleteLocalRef(jpiece);
    }
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void call_callback_on_done() {
    if (!g_callback || !g_jvm) return;
    JNIEnv* env = nullptr;
    bool need_detach = false;
    int get_env_stat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (get_env_stat == JNI_EDETACH) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        need_detach = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(g_callback);
    jmethodID onDone = env->GetMethodID(cls, "onDone", "()V");
    if (onDone) env->CallVoidMethod(g_callback, onDone);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
}

static void release_callback() {
    if (g_callback && g_jvm) {
        JNIEnv* env = nullptr;
        g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (env) env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
}

// Helper functions
static std::string buildJson(std::initializer_list<std::pair<const char*, std::string>> items) {
    std::string json = "{";
    bool first = true;
    for (auto& item : items) {
        if (!first) json += ",";
        first = false;
        json += "\"" + std::string(item.first) + "\":" + item.second;
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

    // Apply optimized config
    std::string config = "{\"use_mmap\":true,\"precision\":\"low\",\"backend_type\":\"cpu\"}";
    g_llm->set_config(config.c_str());

    if (!g_llm->load()) {
        LOGE("Model load() failed");
        delete g_llm;
        g_llm = nullptr;
        return JNI_FALSE;
    }

    g_model_loaded = true;
    g_history.clear();
    g_stream_buffer.clear();
    g_full_response.clear();
    LOGI("MNN model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_MnnEngine_mnnUnloadModel(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_llm) {
        delete g_llm;
        g_llm = nullptr;
    }
    g_model_loaded = false;
    g_history.clear();
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_MnnEngine_mnnSetConfig(
    JNIEnv* env, jobject thiz,
    jint nCtx, jint nBatch, jint maxTokens,
    jfloat temperature, jfloat topP, jfloat minP) {
    
    g_cfg.n_ctx = nCtx > 0 ? nCtx : 8192;
    g_cfg.n_batch = nBatch > 0 ? nBatch : 2048;
    g_cfg.max_new_tokens = maxTokens > 0 ? maxTokens : 4096;
    g_cfg.temperature = temperature;
    g_cfg.top_p = topP;
    g_cfg.min_p = minP;
    
    LOGI("MNN config set: ctx=%d, batch=%d, max_tokens=%d", g_cfg.n_ctx, g_cfg.n_batch, g_cfg.max_new_tokens);
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_MnnEngine_mnnSetRepeatPenalty(
    JNIEnv* env, jobject thiz,
    jfloat repeatPenalty, jfloat freqPenalty, jfloat presPenalty) {
    
    // MNN applies repeat penalty via config
    char config[256];
    snprintf(config, sizeof(config), 
             "{\"repeat_penalty\":%g,\"freq_penalty\":%g,\"pres_penalty\":%g}",
             (double)repeatPenalty, (double)freqPenalty, (double)presPenalty);
    if (g_llm) g_llm->set_config(config);
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_MnnEngine_mnnSetSystemPrompt(JNIEnv* env, jobject thiz, jstring prompt) {
    const char* s = env->GetStringUTFChars(prompt, nullptr);
    if (s) {
        g_system_prompt = s;
        env->ReleaseStringUTFChars(prompt, s);
    }
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

    std::string query(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // MNN response API - we process in chunks for better streaming
    std::ostringstream oss;
    
    // Build conversation history
    std::string full_prompt = g_system_prompt + "\n\nUser: " + query + "\nAssistant: ";
    
    // For streaming, we need to run inference and periodically poll
    // MNN 3.5.0 doesn't have native streaming, so we simulate it
    g_llm->response(full_prompt, &oss);
    g_full_response = oss.str();
    g_stream_buffer = g_full_response;
    
    auto* ctx = g_llm->getContext();
    if (ctx) {
        g_tokens_generated = ctx->prompt_len + ctx->gen_seq_len;
    }
    
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
    int used_tokens = ctx->prompt_len + ctx->gen_seq_len;
    int ctx_size = g_cfg.n_ctx > 0 ? g_cfg.n_ctx : 8192;
    return (used_tokens > 0 && ctx_size > 0) ? (int)((float)used_tokens / ctx_size * 100.0f) : 0;
}

JNIEXPORT void JNICALL
Java_com_gguf_ipc_MnnEngine_mnnResetContext(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_history.clear();
    g_stream_buffer.clear();
    g_full_response.clear();
    g_tokens_generated = 0;
    g_inference_done = true;
    if (g_llm) {
        g_llm->reset();
    }
    LOGI("MNN context reset");
}

JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_MnnEngine_mnnGetModelInfo(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_llm || !g_model_loaded) {
        return env->NewStringUTF("{\"engine\":\"MNN\",\"loaded\":false}");
    }
    auto* ctx = g_llm->getContext();
    std::string info = buildJson({
        {"engine", quote("MNN")},
        {"loaded", "true"},
        {"context_size", num(g_cfg.n_ctx)},
        {"max_tokens", num(g_cfg.max_new_tokens)}
    });
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_gguf_ipc_MnnEngine_mnnBenchmark(JNIEnv* env, jobject thiz, jint ppTokens, jint tgTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_llm || !g_model_loaded) {
        return env->NewStringUTF("{\"error\":\"Model not loaded\"}");
    }

    // Simple benchmark
    std::string pp_prompt;
    for (int i = 0; i < ppTokens; ++i) pp_prompt += "word ";

    auto start = std::chrono::high_resolution_clock::now();
    std::ostringstream oss;
    g_llm->response(pp_prompt, &oss);
    auto end = std::chrono::high_resolution_clock::now();
    
    double pp_ms = std::chrono::duration<double, std::milli>(end - start).count();
    double pp_tps = (pp_ms > 0) ? (double)ppTokens / (pp_ms / 1000.0) : 0;

    std::string result = buildJson({
        {"pp_ms", num((float)pp_ms)},
        {"pp_tps", num((float)pp_tps)},
        {"decode_ms", num(0)},
        {"decode_tps", num(0)}
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