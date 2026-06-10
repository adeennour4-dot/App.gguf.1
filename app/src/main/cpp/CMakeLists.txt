cmake_minimum_required(VERSION 3.22.1)
project("gguf_zerocopy_engine_v6" CXX C)

# Force C++17 for all targets (MNN-LLM needs it for json/nlohmann)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# ---------------------------------------------------------------------------
# GPU Backends for Android
# ---------------------------------------------------------------------------

# Vulkan backend (ON for Mali/Xclipse GPUs)
option(GGML_VULKAN "Enable Vulkan GPU backend" ON)

# OpenCL backend (OFF by default, enable for Adreno builds)
option(GGML_OPENCL "Enable OpenCL GPU backend" OFF)

# llama.cpp build options
set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Flash attention for faster inference on mobile
set(LLAMA_FLASH_ATTN ON CACHE BOOL "" FORCE)

# OpenCL Adreno optimizations (if OpenCL enabled)
if(GGML_OPENCL)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
endif()

# ---------------------------------------------------------------------------
# Vulkan dependency: SPIRV-Headers (required by llama.cpp's ggml-vulkan)
# ---------------------------------------------------------------------------
if(GGML_VULKAN)
    find_package(SPIRV-Headers QUIET)
    if(NOT SPIRV-Headers_FOUND)
        message(WARNING "SPIRV-Headers not found, disabling Vulkan backend")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

# ---------------------------------------------------------------------------
# Fetch llama.cpp
# ---------------------------------------------------------------------------
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

message(STATUS "Build config — Vulkan: ${GGML_VULKAN} | OpenCL: ${GGML_OPENCL}")

# ---------------------------------------------------------------------------
# Fetch MNN (Alibaba MNN-LLM)
# ---------------------------------------------------------------------------
set(MNN_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TRAIN OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_QUANTOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DIFFUSION OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_AUDIO OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_ARM82 ON CACHE BOOL "" FORCE)
set(MNN_OPENCL OFF CACHE BOOL "" FORCE)
set(MNN_VULKAN OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT ON CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_USE_SSE OFF CACHE BOOL "" FORCE)
set(MNN_USE_THREAD_POOL ON CACHE BOOL "" FORCE)
set(MNN_FORBID_MULTI_THREAD OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    mnn
    GIT_REPOSITORY https://github.com/alibaba/MNN.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(mnn)

# ---------------------------------------------------------------------------
# JNI shared libraries
# ---------------------------------------------------------------------------

# --- ipc-bridge (llama.cpp JNI) ---
add_library(ipc-bridge SHARED ipc-bridge.cpp)

find_library(log-lib     log)
find_library(android-lib android)

target_include_directories(ipc-bridge PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)

target_link_libraries(ipc-bridge
    PRIVATE
        llama
        ggml
        ${log-lib}
        ${android-lib}
)

# --- mnn-bridge (MNN-LLM JNI) ---
add_library(mnn-bridge SHARED mnn-bridge.cpp)

target_include_directories(mnn-bridge PRIVATE
    ${mnn_SOURCE_DIR}/include/
    ${mnn_SOURCE_DIR}/source/
    ${mnn_SOURCE_DIR}/express/
    ${mnn_SOURCE_DIR}/transformers/llm/engine/include/
    ${mnn_SOURCE_DIR}/3rd_party/
    ${mnn_SOURCE_DIR}/3rd_party/half/
    ${mnn_SOURCE_DIR}/3rd_party/flatbuffers/include/
)

target_link_libraries(mnn-bridge
    PRIVATE
        MNN
        ${log-lib}
        ${android-lib}
)

# ---------------------------------------------------------------------------
# OpenCL linking (Android vendor library)
# ---------------------------------------------------------------------------
if(GGML_OPENCL)
    target_compile_definitions(ipc-bridge PRIVATE GGML_OPENCL_ENABLED)
    message(STATUS "OpenCL enabled (runtime loading)")
endif()

target_compile_features(ipc-bridge PRIVATE cxx_std_17)
target_compile_features(mnn-bridge PRIVATE cxx_std_17)
