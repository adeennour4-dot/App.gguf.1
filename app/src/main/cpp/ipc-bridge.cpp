
extern "C" JNIEXPORT jboolean JNICALL
Java_com_gguf_ipc_EngineCore_loadGgufModelNative(JNIEnv* env, jobject, jstring path) {
    const char* filePath = env->GetStringUTFChars(path, nullptr);
    
    // 1. TOTAL TEARDOWN: Free everything in reverse order
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model = nullptr; }

    LOGI("RAM Cleared. Loading new model with updated settings...");

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true; // Essential for Samsung stability
    mparams.n_gpu_layers = g_cfg.n_gpu_layers; // Use the value from settings

    g_model = llama_model_load_from_file(filePath, mparams);
    env->ReleaseStringUTFChars(path, filePath);

    if (!g_model) return JNI_FALSE;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = g_cfg.n_ctx; // Use updated context size
    cparams.n_threads = g_cfg.n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Rebuild sampler with new temperature/top_p
    rebuild_sampler(); 

    return JNI_TRUE;
}       
