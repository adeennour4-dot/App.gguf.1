cmake_minimum_required(VERSION 3.22.1)
project("gguf_zerocopy_engine" CXX C)

# ---------------------------------------------------------------------------
# Vulkan: On Android the Vulkan loader is part of the OS (libvulkan.so).
# We only need the headers for compilation; the .so is found at link time
# via find_library below.  The NDK ships Vulkan headers in its sysroot.
#
# For GGML_VULKAN=ON, llama.cpp also needs glslc (the SPIR-V shader compiler)
# to be on the HOST PATH at configure time so it can pre-compile .glsl → .spv.
# The GitHub Actions workflow installs glslc before invoking Gradle.
# ---------------------------------------------------------------------------
if(ANDROID)
    set(Vulkan_INCLUDE_DIR "${ANDROID_SYSROOT}/usr/include" CACHE PATH "Vulkan headers from NDK sysroot")
    find_library(VULKAN_LIB vulkan)
    if(VULKAN_LIB)
        message(STATUS "Found Android Vulkan loader: ${VULKAN_LIB}")
    else()
        message(WARNING "Vulkan loader not found — Vulkan GPU offload will be disabled at runtime")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

# ---------------------------------------------------------------------------
# Fetch llama.cpp — updated to b5576 which supports:
#   - Qwen3 / Qwen3.5 (including MoE variants)
#   - Gemma 4 / Gemma 4 E4B (MoE)
#   - Zyphra ZAYA-1-8B
#   - Full llama_chat_apply_template() for all of the above
#   - llama_kv_cache_seq_rm() for context management
#   - Flash attention (GGML_FLASH_ATTN)
# To update: change GIT_TAG to a newer bNNNN tag and audit ipc-bridge.cpp
# for any API changes per the notes in README.md.
# ---------------------------------------------------------------------------
include(FetchContent)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b5576        # ← updated from b5046; supports Qwen3.5/Gemma4/ZAYA
    GIT_SHALLOW    TRUE
)

set(GGML_VULKAN       ${GGML_VULKAN} CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_OPENMP       OFF  CACHE BOOL "Disable OpenMP (broken in Android NDK)" FORCE)
set(GGML_LLAMAFILE    OFF  CACHE BOOL "Disable llamafile (unsupported on Android)" FORCE)
set(GGML_BACKEND_DL   OFF  CACHE BOOL "Disable dynamic backend loading" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "Skip tests"    FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Skip examples" FORCE)

FetchContent_MakeAvailable(llama)

# ---------------------------------------------------------------------------
# Our JNI bridge shared library
# ---------------------------------------------------------------------------
add_library(ipc-bridge SHARED
    ipc-bridge.cpp
)

find_library(log-lib    log)
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

if(ANDROID AND VULKAN_LIB)
    target_link_libraries(ipc-bridge PRIVATE ${VULKAN_LIB})
endif()

target_compile_features(ipc-bridge PRIVATE cxx_std_17)
