# The dialogue model: llama.cpp (fetched at build time from its own repository at a pinned release tag, never committed) and
# our core around it (dialogue_core: one model, one KV sequence per NPC) as the static library wcdialogue_core; the shared
# library wcdialogue (dialogue_jni.cpp) is the Kotlin side's door. Desktop test: wifecraft/engine-test/dialogue_test.cpp,
# built with -DWC_LLAMA_SOURCE_DIR=<a checkout of the same tag> (engine-test/dialogue_build.sh).

set(WC_LLAMA_TAG "v1.0.0-b12359-b1b770d" CACHE STRING "llama.cpp release tag the app is built against")

# llama.cpp's own options: the library only (no tools, tests, server, curl), CPU only, no OpenMP (ggml has its own pool)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
if (ANDROID)
    # arm64-v8a without GGML_NATIVE: say which instructions the kernels may use. dotprod (the int8 dot product of the
    # quantised matmuls) and fp16 are in every phone with 8 GB or more of RAM; i8mm (about twice the prompt speed) is not
    # in all of them, so it is left out rather than risk an illegal instruction.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

if (WC_LLAMA_SOURCE_DIR)
    add_subdirectory(${WC_LLAMA_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama-build EXCLUDE_FROM_ALL)
else()
    include(FetchContent)
    FetchContent_Declare(llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${WC_LLAMA_TAG}
        GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(wcdialogue_core STATIC dialogue_core.cpp)
target_compile_features(wcdialogue_core PUBLIC cxx_std_17)
target_include_directories(wcdialogue_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(wcdialogue_core PUBLIC llama)
target_compile_options(wcdialogue_core PRIVATE -O2 -Wall -Wno-unused-parameter)
set_target_properties(wcdialogue_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if (ANDROID)
    add_library(wcdialogue SHARED dialogue_jni.cpp)
    target_link_libraries(wcdialogue PRIVATE wcdialogue_core log)
    target_compile_options(wcdialogue PRIVATE -O2 -Wall -Wno-unused-parameter)
    # 16 KB page alignment (Android 15+ devices): llama.cpp's static code is linked in
    target_link_options(wcdialogue PRIVATE -Wl,-z,max-page-size=16384)
endif()
