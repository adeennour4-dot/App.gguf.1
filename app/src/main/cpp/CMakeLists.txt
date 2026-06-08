cmake_minimum_required(VERSION 3.22.1)
project("gguf_v5_pro")

set(CMAKE_CXX_STANDARD 17)

# 1. CRITICAL: Enable Vulkan flags BEFORE making llama.cpp available
set(GGML_VULKAN ON CACHE INTERNAL "Compile llama.cpp with Vulkan support")

# Add safety flags for ARMv8/ARMv9 architectures (essential for Exynos stability)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -march=armv8-a+dotprod")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8-a+dotprod")

# 2. Fetch the specific tag of llama.cpp
include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9542
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

# 3. Define your shared library (This bridges Kotlin/Java with C++)
add_library(ipc-bridge SHARED ipc-bridge.cpp)

# 4. Target Include Directories so your ipc-bridge.cpp can see llama headers
target_include_directories(ipc-bridge PRIVATE 
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)

# 5. Link libraries correctly, including the NDK Vulkan headers/stubs
target_link_libraries(ipc-bridge
    PRIVATE
    llama
    ggml
    android
    log
    vulkan
)
