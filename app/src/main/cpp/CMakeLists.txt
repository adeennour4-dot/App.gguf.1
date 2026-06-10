cmake_minimum_required(VERSION 3.22.1)
project("gguf_zerocopy_engine_v6" CXX C)

include(FetchContent)

# ---------------------------------------------------------------------------
# GPU Backends for Android
#
# Vulkan: Works on Mali (Pixel, MediaTek), Samsung Xclipse (RDNA)
# OpenCL: Works on Qualcomm Adreno (8 Gen 3, 8 Elite, X-Elite)
#
# Both are enabled by default. At runtime, only one is active based on GPU.
# ---------------------------------------------------------------------------

# Vulkan backend (ON for Mali/Xclipse GPUs)
option(GGML_VULKAN "Enable Vulkan GPU backend" ON)

# OpenCL backend (ON for Adreno GPUs)
option(GGML_OPENCL "Enable OpenCL GPU backend" OFF)

# llama.cpp build options
set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# OpenCL Adreno optimizations (if OpenCL enabled)
if(GGML_OPENCL)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
endif()

# ---------------------------------------------------------------------------
# Fetch llama.cpp latest (June 2026)
# Updated to latest for OpenCL Adreno support + multimodal (libmtmd)
# ---------------------------------------------------------------------------
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

# ---------------------------------------------------------------------------
# JNI shared library
# ---------------------------------------------------------------------------
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

# Link Vulkan if enabled
if(GGML_VULKAN)
    find_package(Vulkan REQUIRED)
    target_link_libraries(ipc-bridge PRIVATE Vulkan::Vulkan)
    target_compile_definitions(ipc-bridge PRIVATE GGML_VULKAN_ENABLED)
endif()

# Link OpenCL if enabled
if(GGML_OPENCL)
    find_library(OpenCL_LIB OpenCL)
    if(OpenCL_LIB)
        target_link_libraries(ipc-bridge PRIVATE ${OpenCL_LIB})
        target_compile_definitions(ipc-bridge PRIVATE GGML_OPENCL_ENABLED)
    else()
        message(WARNING "OpenCL not found, disabling OpenCL backend")
        set(GGML_OPENCL OFF)
    endif()
endif()

target_compile_features(ipc-bridge PRIVATE cxx_std_17)
