
cmake_minimum_required(VERSION 3.22.1)

project("llama-android")

# 1. Enable FetchContent for dependency management
include(FetchContent)

# 2. Configure llama.cpp dependency
# We use a known stable tag (b4080) or a full commit hash
FetchContent_Declare(
  llama
  GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
  GIT_TAG        b4080  # Changed from b4080a0 which was causing the error
  GIT_SHALLOW    TRUE
)

# Set llama.cpp build options before making it available
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

# 3. Create your JNI library
# This is the library your Kotlin/Java code will load via System.loadLibrary("llama-android")
add_library(llama-android SHARED
    native-lib.cpp # Ensure this file exists in your cpp folder
)

# 4. Include optimization flags for ARMv8.4-A (Dot Product) as seen in your logs
target_compile_options(llama-android PRIVATE
    -O3
    -flto
    -march=armv8.4a+dotprod
)

# 5. Link against the llama library and Android logging library
find_library(log-lib log)

target_link_libraries(llama-android
    llama
    ${log-lib}
)
