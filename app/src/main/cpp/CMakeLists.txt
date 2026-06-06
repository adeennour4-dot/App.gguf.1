# app/src/main/cpp/CMakeLists.txt
# For use with latest llama.cpp and Android NDK

cmake_minimum_required(VERSION 3.22)
project("appgguf")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable verbose output for debugging (optional)
set(CMAKE_VERBOSE_MAKEFILE ON)

# ----------------------------------------------------------------------------
# Fetch llama.cpp from GitHub (latest master)
# ----------------------------------------------------------------------------
include(FetchContent)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp
    GIT_TAG        master          # or a specific commit hash if needed
    GIT_SHALLOW    TRUE
)

FetchContent_MakeAvailable(llama)

# ----------------------------------------------------------------------------
# Define our shared library
# ----------------------------------------------------------------------------
add_library(ipc-bridge SHARED
    ipc-bridge.cpp
)

# ----------------------------------------------------------------------------
# Include directories
# ----------------------------------------------------------------------------
target_include_directories(ipc-bridge PRIVATE
    ${llama_SOURCE_DIR}
    ${llama_SOURCE_DIR}/include
)

# ----------------------------------------------------------------------------
# Link libraries
# ----------------------------------------------------------------------------
target_link_libraries(ipc-bridge
    # llama.cpp static library (will be built by FetchContent)
    llama
    # Android logging
    log
    # POSIX shared memory (shm_open, shm_unlink)
    rt
    # Threads (required by llama.cpp)
    ${CMAKE_THREAD_LIBS_INIT}
)

# ----------------------------------------------------------------------------
# Compiler flags for performance and Android compatibility
# ----------------------------------------------------------------------------
target_compile_options(ipc-bridge PRIVATE
    -O3
    -flto
    -fno-limit-debug-info
    -funwind-tables
    -fstack-protector-strong
)

# If using Vulkan or specific CPU features, you can add:
# target_compile_definitions(ipc-bridge PRIVATE GGML_USE_VULKAN)
# target_link_libraries(ipc-bridge Vulkan::Vulkan)

# ----------------------------------------------------------------------------
# Android specific: set the correct ABI and min SDK (NDK will handle)
# ----------------------------------------------------------------------------
# The NDK already defines ANDROID_ABI, so we just ensure the flags are set.
if(ANDROID)
    target_compile_options(ipc-bridge PRIVATE
        -DANDROID
        -fdata-sections
        -ffunction-sections
    )
    target_link_options(ipc-bridge PRIVATE
        -Wl,--gc-sections
    )
endif()

# ----------------------------------------------------------------------------
# Optional: define a macro to enable KV cache functions if needed
# The latest llama.cpp already defines them, but we keep for safety
# ----------------------------------------------------------------------------
target_compile_definitions(ipc-bridge PRIVATE
    LLAMA_USE_KV_CACHE=1
)
