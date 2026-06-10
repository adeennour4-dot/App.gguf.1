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

# ---------------------------------------------------------------------------
# Vulkan linking (Android system library)
# On Android, Vulkan is loaded dynamically via the Vulkan loader
# ---------------------------------------------------------------------------
if(GGML_VULKAN)
    # On Android, Vulkan is a system library - just link against it
    # The Vulkan loader is at /system/lib64/libvulkan.so
    find_library(Vulkan_LIB vulkan)
    if(Vulkan_LIB)
        target_link_libraries(ipc-bridge PRIVATE ${Vulkan_LIB})
        target_compile_definitions(ipc-bridge PRIVATE GGML_VULKAN_ENABLED)
        message(STATUS "Vulkan enabled: ${Vulkan_LIB}")
    else()
        message(WARNING "Vulkan not found, disabling Vulkan backend")
        set(GGML_VULKAN OFF)
    endif()
endif()

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
