cmake_minimum_required(VERSION 3.22.1)
project("gguf_zerocopy_engine_v4" CXX C)

include(FetchContent)

# ---------------------------------------------------------------------------
# Disable Vulkan — llama.cpp's Vulkan backend requires glslang + SPIRV-Tools
# + SPIRV-Headers all chained via FetchContent which breaks in the Android
# NDK cross-compile environment. llama.cpp still auto-detects Vulkan at
# runtime via the Android Vulkan loader; this flag only controls the
# compile-time shader compilation pipeline.
# ---------------------------------------------------------------------------
set(GGML_VULKAN    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# Fetch llama.cpp b9487
# ---------------------------------------------------------------------------
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9487
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

target_compile_features(ipc-bridge PRIVATE cxx_std_17)
