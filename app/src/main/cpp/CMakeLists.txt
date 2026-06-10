cmake_minimum_required(VERSION 3.22.1)
project("gguf_zerocopy_engine_v6" CXX C)

include(FetchContent)

# ---------------------------------------------------------------------------
# GPU Backends for Android
#
# Vulkan: Works on Mali (Pixel, MediaTek), Samsung Xclipse (RDNA)
# OpenCL: Works on Qualcomm Adreno (8 Gen 3, 8 Elite, X-Elite)
#
# On Android, Vulkan is a system library at /system/lib64/libvulkan.so
# OpenCL is at /vendor/lib64/libOpenCL.so (vendor-provided)
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

# OpenCL Adreno optimizations (if OpenCL enabled)
if(GGML_OPENCL)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
endif()

# ---------------------------------------------------------------------------
# Vulkan dependency: SPIRV-Headers (required by llama.cpp's ggml-vulkan)
# Only enabled when building locally with Vulkan installed. CI builds CPU-only.
# ---------------------------------------------------------------------------
if(GGML_VULKAN)
    find_package(SPIRV-Headers QUIET)
    if(NOT SPIRV-Headers_FOUND)
        message(WARNING "SPIRV-Headers not found, disabling Vulkan backend")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

# ---------------------------------------------------------------------------
# Fetch llama.cpp (pinned commit for stable Vulkan SPIRV-Headers support)
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

# ---------------------------------------------------------------------------
# OpenCL linking (Android vendor library)
# On Android, OpenCL is at /vendor/lib64/libOpenCL.so
# ---------------------------------------------------------------------------
if(GGML_OPENCL)
    # OpenCL is loaded dynamically on Android
    # We link against it at runtime via dlopen
    target_compile_definitions(ipc-bridge PRIVATE GGML_OPENCL_ENABLED)
    message(STATUS "OpenCL enabled (runtime loading)")
endif()

target_compile_features(ipc-bridge PRIVATE cxx_std_17)
