cmake_minimum_required(VERSION 3.22.1)
project("gguf_v5_pro")

set(CMAKE_CXX_STANDARD 17)

# NOTE: GGML_VULKAN is intentionally OFF here and in Gradle.
# Vulkan compile-time shader compilation requires SPIRV-Headers on the host.
# GitHub Actions runners don't have it. Runtime Vulkan (via Android loader) works fine.
# Remove the misleading "ON" that was overridden by Gradle anyway.

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -march=armv8-a+dotprod")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8-a+dotprod")

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9542
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(ipc-bridge SHARED ipc-bridge.cpp)

target_include_directories(ipc-bridge PRIVATE
    ${llama_SOURCE_DIR}
    ${llama_SOURCE_DIR}/src
    ${llama_SOURCE_DIR}/ggml/include
)

target_link_libraries(ipc-bridge
    PRIVATE llama ggml android log
    # vulkan is linked as optional; Vulkan compile-time support is OFF but runtime loader works
)
