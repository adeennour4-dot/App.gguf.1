cmake_minimum_required(VERSION 3.22.1)
project("gguf_v5_pro")

set(CMAKE_CXX_STANDARD 17)

# 1. CRITICAL: Turn Vulkan processing flags ON before fetching the repository
set(GGML_VULKAN ON CACHE INTERNAL "Compile llama.cpp with Vulkan support")

# Pass safe baseline optimization flags for modern Android ARMv8/ARMv9 architectures
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -march=armv8-a+dotprod")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8-a+dotprod")

# 2. Fetch Content definitions for the specified llama.cpp repository checkpoint
include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9542
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

# 3. Establish the main JNI communication bridge library target
add_library(ipc-bridge SHARED ipc-bridge.cpp)

# 4. FIXED: Updated target include directories to match modern llama.cpp file layout
target_include_directories(ipc-bridge PRIVATE 
    ${llama_SOURCE_DIR}
    ${llama_SOURCE_DIR}/src
    ${llama_SOURCE_DIR}/ggml/include  # Kept as fallback for standard macro components
)

# 5. Link native NDK platform engines, logging, and Vulkan drivers cleanly
target_link_libraries(ipc-bridge
    PRIVATE
    llama
    ggml
    android
    log
    vulkan
)
