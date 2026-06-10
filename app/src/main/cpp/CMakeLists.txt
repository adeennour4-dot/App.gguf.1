cmake_minimum_required(VERSION 3.22.1)
project("gguf_zerocopy_engine_v6" CXX C)

# Force C++17 for all targets
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# ---------------------------------------------------------------------------
# Android-optimized ARM CPU flags
# ---------------------------------------------------------------------------
# Target armv8.6-a with dotprod, i8mm, fp16 — covers most modern Android SoCs
# (Snapdragon 8 Gen 2+, Dimensity 9200+, Exynos 2200+)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARM_ARCH_FLAGS "-march=armv8.6-a+dotprod+i8mm+fp16")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   ${ARM_ARCH_FLAGS}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ARM_ARCH_FLAGS}")
endif()

# ---------------------------------------------------------------------------
# llama.cpp build options
# ---------------------------------------------------------------------------

# GPU backends
option(GGML_VULKAN "Enable Vulkan GPU backend" ON)
option(GGML_OPENCL "Enable OpenCL GPU backend" OFF)  # requires OpenCL headers not in NDK

# CPU optimizations
set(GGML_CPU_KLEIDIAI   OFF CACHE BOOL "" FORCE)  # KleidiAI ARM micro-kernels (disabled — b9474 kernel mismatch)

set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE     OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)   # static linking, no dynamic backend loading
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_FLASH_ATTN ON CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# Vulkan dependency: SPIRV-Headers
# ---------------------------------------------------------------------------
if(GGML_VULKAN)
    find_package(SPIRV-Headers QUIET)
    if(NOT SPIRV-Headers_FOUND)
        message(WARNING "SPIRV-Headers not found, disabling Vulkan backend")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

# ---------------------------------------------------------------------------
# Fetch llama.cpp — pinned to stable release b9474 (2026-06-02)
# ---------------------------------------------------------------------------
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9474
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

message(STATUS "Build config — Vulkan: ${GGML_VULKAN} | OpenCL: ${GGML_OPENCL} | KleidiAI: ${GGML_CPU_KLEIDIAI} | tag: b9474")

# ---------------------------------------------------------------------------
# Fetch MNN (Alibaba MNN-LLM)
#
# MNN uses option() for most flags. We force-set them via CACHE before
# FetchContent processes MNN's CMakeLists.txt, so option() sees them
# already defined and skips its defaults.
#
# GIT_SUBMODULES "" prevents fetching submodules (kleidiai etc.) that
# aren't needed — we disable them via MNN_KLEIDIAI=OFF.
# ---------------------------------------------------------------------------
set(MNN_BUILD_SHARED_LIBS ON  CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM         ON  CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO        OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST        OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TRAIN       OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_QUANTOOLS   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DIFFUSION   OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV      OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_AUDIO       OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY        ON  CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_ARM82             OFF CACHE BOOL "" FORCE)
set(MNN_KLEIDIAI          OFF CACHE BOOL "" FORCE)
set(MNN_SME2              OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL            OFF CACHE BOOL "" FORCE)
set(MNN_VULKAN            OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD         OFF CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT        ON  CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_USE_SSE           OFF CACHE BOOL "" FORCE)
set(MNN_USE_THREAD_POOL   ON  CACHE BOOL "" FORCE)
set(MNN_FORBID_MULTI_THREAD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_PROTOBUFFER OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    mnn
    GIT_REPOSITORY https://github.com/alibaba/MNN.git
    GIT_TAG        3.5.0
    GIT_SHALLOW    TRUE
    GIT_SUBMODULES ""             # don't fetch any submodules
    GIT_SUBMODULES_RECURSE FALSE  # skip recursive submodule init
)

# Download MNN source first (before processing its CMakeLists.txt)
FetchContent_GetProperties(mnn)
if(NOT mnn_POPULATED)
    FetchContent_Populate(mnn)
endif()

# Fix: MNN's cmake modules use CMAKE_SOURCE_DIR which resolves to our project
# root when built via FetchContent. Override it to MNN's actual source dir.
set(CMAKE_SOURCE_DIR "${mnn_SOURCE_DIR}")

# Now process MNN's CMakeLists.txt with the corrected CMAKE_SOURCE_DIR
add_subdirectory("${mnn_SOURCE_DIR}" "${mnn_BINARY_DIR}")

# Restore
set(CMAKE_SOURCE_DIR "${PROJECT_SOURCE_DIR}")

# ---------------------------------------------------------------------------
# JNI shared libraries
# ---------------------------------------------------------------------------

find_library(log-lib     log)
find_library(android-lib android)

# --- ipc-bridge (llama.cpp JNI) ---
add_library(ipc-bridge SHARED ipc-bridge.cpp)

target_include_directories(ipc-bridge PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)

target_link_libraries(ipc-bridge
    PRIVATE llama ggml ${log-lib} ${android-lib}
)

# --- mnn-bridge (MNN-LLM JNI) ---
add_library(mnn-bridge SHARED mnn-bridge.cpp)

target_include_directories(mnn-bridge PRIVATE
    ${mnn_SOURCE_DIR}/include/
    ${mnn_SOURCE_DIR}/source/
    ${mnn_SOURCE_DIR}/express/
    ${mnn_SOURCE_DIR}/transformers/llm/engine/include/
    ${mnn_SOURCE_DIR}/3rd_party/
    ${mnn_SOURCE_DIR}/3rd_party/half/
    ${mnn_SOURCE_DIR}/3rd_party/flatbuffers/include/
)

target_link_libraries(mnn-bridge
    PRIVATE MNN ${log-lib} ${android-lib}
)

# ---------------------------------------------------------------------------
# OpenCL linking
# ---------------------------------------------------------------------------
if(GGML_OPENCL)
    target_compile_definitions(ipc-bridge PRIVATE GGML_OPENCL_ENABLED)
endif()

target_compile_features(ipc-bridge PRIVATE cxx_std_17)
target_compile_features(mnn-bridge PRIVATE cxx_std_17)
