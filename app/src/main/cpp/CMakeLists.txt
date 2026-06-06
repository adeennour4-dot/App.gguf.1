cmake_minimum_required(VERSION 3.22.1)
project("gguf_zerocopy_engine_v4" CXX C)

include(FetchContent)

# ---------------------------------------------------------------------------
# Vulkan setup
# ---------------------------------------------------------------------------
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)

if(ANDROID)
    set(Vulkan_INCLUDE_DIR "${ANDROID_SYSROOT}/usr/include"
        CACHE PATH "Vulkan headers from NDK sysroot")
    find_library(VULKAN_LIB vulkan)

    if(VULKAN_LIB)
        message(STATUS "Found Android Vulkan loader: ${VULKAN_LIB}")

        # Vulkan-Headers: provides vulkan.hpp needed by llama.cpp
        FetchContent_Declare(
            vulkan_headers
            GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
            GIT_TAG        v1.3.275
            GIT_SHALLOW    TRUE
        )
        FetchContent_MakeAvailable(vulkan_headers)
        include_directories(${vulkan_headers_SOURCE_DIR}/include)

        # SPIRV-Headers: required by llama.cpp's ggml-vulkan CMakeLists
        # Must be fetched BEFORE FetchContent_MakeAvailable(llama) so that
        # find_package(SPIRV-Headers) inside llama.cpp succeeds.
        FetchContent_Declare(
            SPIRV-Headers
            GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
            GIT_TAG        sdk-1.3.275.0
            GIT_SHALLOW    TRUE
        )
        FetchContent_MakeAvailable(SPIRV-Headers)

        # Point CMake's find_package to the fetched SPIRV-Headers install tree
        list(APPEND CMAKE_PREFIX_PATH "${spirv-headers_SOURCE_DIR}")

    else()
        message(WARNING "Vulkan loader not found — disabling Vulkan backend")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

# ---------------------------------------------------------------------------
# Fetch llama.cpp b9487
# ---------------------------------------------------------------------------
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9487
    GIT_SHALLOW    TRUE
)

set(GGML_OPENMP          OFF CACHE BOOL "Disable OpenMP"                  FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "Disable llamafile"               FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "Disable dynamic backend loading" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "Skip tests"                      FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Skip examples"                   FORCE)

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

if(ANDROID AND VULKAN_LIB)
    target_link_libraries(ipc-bridge PRIVATE ${VULKAN_LIB})
endif()

target_compile_features(ipc-bridge PRIVATE cxx_std_17)
