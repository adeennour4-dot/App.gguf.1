cmake_minimum_required(VERSION 3.22.1)
project("llama-android")

include(FetchContent)

# FIX: Use the official stable tag 'b4080'
FetchContent_Declare(
  llama
  GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
  GIT_TAG        b4080
  GIT_SHALLOW    TRUE
)

# Build settings for mobile
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

# Define our JNI library
add_library(llama-android SHARED native-lib.cpp)

# ARM v8.4 optimizations (Dot Product support)
target_compile_options(llama-android PRIVATE -O3 -march=armv8.4a+dotprod)

find_library(log-lib log)

# Link llama and the Android log library
target_link_libraries(llama-android llama ${log-lib})
