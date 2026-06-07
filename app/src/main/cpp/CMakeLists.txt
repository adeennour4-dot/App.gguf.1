cmake_minimum_required(VERSION 3.22.1)
project("gguf_ultra_v5")

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9542
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(ipc-bridge SHARED ipc-bridge.cpp)
target_link_libraries(ipc-bridge llama ggml android log)
