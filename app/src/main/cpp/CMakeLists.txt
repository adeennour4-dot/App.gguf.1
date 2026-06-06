cmake_minimum_required(VERSION 3.22)
project("appgguf")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Pin to a commit that still has the old API (llama_chat_apply_template with 7 args, llama_kv_cache_*, etc.)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp
    GIT_TAG        b4080a0   # Dec 2024 – stable API for your original code
    GIT_SHALLOW    TRUE
)

FetchContent_MakeAvailable(llama)

add_library(ipc-bridge SHARED
    ipc-bridge.cpp
)

target_include_directories(ipc-bridge PRIVATE
    ${llama_SOURCE_DIR}
    ${llama_SOURCE_DIR}/include
)

target_link_libraries(ipc-bridge
    llama
    log
    rt                # for shm_open / shm_unlink
    ${CMAKE_THREAD_LIBS_INIT}
)

target_compile_definitions(ipc-bridge PRIVATE
    LLAMA_USE_KV_CACHE=1
)

if(ANDROID)
    target_compile_options(ipc-bridge PRIVATE
        -DANDROID
        -fdata-sections
        -ffunction-sections
    )
    target_link_options(ipc-bridge PRIVATE
        -Wl,--gc-sections
    )
endif()
