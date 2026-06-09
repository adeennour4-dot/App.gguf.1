cmake_minimum_required(VERSION 3.22.1)
project("gguf_v5_pro")

set(CMAKE_CXX_STANDARD 17)

# Pre-set Vulkan paths for NDK 29
include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9542
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(ipc-bridge SHARED ipc-bridge.cpp)

# Link for high-end math acceleration
target_link_libraries(ipc-bridge
    PRIVATE
    llama
    ggml
    android
    log
    vulkan
)
