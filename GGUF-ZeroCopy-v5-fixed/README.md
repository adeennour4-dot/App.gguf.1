GGUF ZeroCopy v5 PRO
On-Device Inference, Re-engineered.

GGUF ZeroCopy is a high-performance, production-ready inference engine for Android. Using a zero-copy shared memory architecture, it delivers real-time LLM responses with minimal latency and maximum hardware utilization.
🚀 What’s New in v5 PRO

    ⚡ Extreme Performance: Upgraded to llama.cpp b9542 with LLVM ThinLTO and DotProd acceleration.

    🧠 KV-Cache Quantization: New Q8_0 Cache Quantization allows you to store 2x more context (16k+) in the same RAM footprint.

    🌌 Glassmorphic Cyber-UI: A futuristic, high-contrast interface with real-time TPS (Tokens Per Second) telemetry and smooth glass-effect surfaces.

    🚀 Vulkan Unified Memory: Direct GPU-memory mapping for ultra-low latency response streaming.

    🛠️ Robust Foundation: Upgraded to Gradle 9.5.1, NDK r29, and Compose BOM 2026.05.00.

🏗️ Architecture Overview

The "Zero-Copy" advantage comes from a shared-memory circular buffer between the native engine and the JVM.
code Mermaid

graph LR
    subgraph "Native C++ (llama.cpp b9542)"
        Engine[Engine Core]
        KV[Q8_0 KV-Cache]
    end
    
    Engine -- "Mapped Shared Memory (512KB)" --> UI
    
    subgraph "Kotlin/Compose (Android UI)"
        UI[Glassmorphic UI]
        Telemetry[Real-time TPS Meter]
    end

🛠️ Build Specs
Component	Version
Gradle	9.5.1 (with retry-logic)
AGP	9.1.1
NDK	29.0.14206865
llama.cpp	b9542 (June 2026)
Compose BOM	2026.05.00
🚀 Quick Setup
1. Build Requirements

Ensure you have the latest Android Studio (Ladybug or newer) and the NDK r29 installed via the SDK Manager.
2. CI/CD Deployment

This project includes a production-grade GitHub Action. To trigger a build:

    Push your changes.

    The setup-gradle@v4 action will automatically handle caching and dependency synchronization, bypassing standard network timeouts.

⚙️ Configuration Reference
Setting	Optimal Value	Description
n_ctx	8192	Context window size
n_gpu_layers	99	Forces full Vulkan offload
cparams.type_k	Q8_0	KV-Cache quantization (Pro Feature)
optimization	ThinLTO	Enables cross-module function inlining
📱 The PRO UI Features

    Real-time Telemetry: See your actual T/S (Tokens Per Second) calculated in real-time within the native bridge.

    KV-Fill Monitoring: A sleek, glowing progress bar tracks your context window usage.

    Glassmorphic Output: A high-end, semi-transparent text area designed for high-density reading.

    Pro-Exec Button: One-tap inference start with hardware-accelerated feedback.

📜 License

Copyright © 2026 GGUF ZeroCopy Engine. Built for power users.
Pro-Tips for Optimization:

    Vulkan Stability: If your device crashes during boot, ensure your driver supports Vulkan 1.3.

    Memory Management: Always load your model via the ACTION_OPEN_DOCUMENT picker to allow the engine to map the file descriptor directly into memory (the "Zero-Copy" path).

    Build Faster: If running locally, use ./gradlew assembleDebug --parallel --offline to utilize your local Gradle cache
