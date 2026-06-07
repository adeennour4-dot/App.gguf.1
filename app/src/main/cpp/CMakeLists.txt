cmake_minimum_required(VERSION 3.22.1)
project("gguf_ultra_v5")

set(CMAKE_CXX_STANDARD 17)

# Disable Vulkan/OpenMP for maximum stability on Android
set(GGML_VULKAN    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9542
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(ipc-bridge SHARED ipc-bridge.cpp)

target_link_libraries(ipc-bridge
    PRIVATE
    llama
    ggml
    android
    log
)

target_include_directories(ipc-bridge PRIVATE 
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)
