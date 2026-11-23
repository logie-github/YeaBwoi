# The dialogue model: llama.cpp (fetched at build time from its own repository at a pinned release tag, never committed) and
# our core around it (dialogue_core: one model, one KV sequence per NPC) as the static library wcdialogue_core; the shared
# library wcdialogue (dialogue_jni.cpp) is the Kotlin side's door. Desktop test: wifecraft/engine-test/dialogue_test.cpp,
# built with -DWC_LLAMA_SOURCE_DIR=<a checkout of the same tag> (engine-test/dialogue_build.sh).

set(WC_LLAMA_TAG "v1.0.0-b12359-b1b770d" CACHE STRING "llama.cpp release tag the app is built against")

# llama.cpp's own options: the library only (no tools, tests, server, curl), CPU only (no GPU backend: see the README's note on
# Adreno), no OpenMP (ggml has its own pool)
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
    # arm64-v8a: the CPU kernels are chosen on the phone, not here. ggml builds the CPU backend once per feature set
    # (GGML_CPU_ALL_VARIANTS: android_armv8.0_1 plain NEON, armv8.2 with dotprod and fp16, armv8.6 with i8mm, armv9 with SVE2, ...)
    # as separate libraries (libggml-cpu-<variant>.so, loaded from the app's native-library folder: the manifest has
    # extractNativeLibs) and, at load, keeps the best one this CPU can run (GGML_BACKEND_DL, Core::load's
    # ggml_backend_load_all_from_path). i8mm roughly doubles the speed of reading a prompt and every Snapdragon 8 Gen 1 and later
    # has it; a phone without it falls back to dotprod or to plain NEON by the same scoring, so no phone meets an illegal instruction.
    # This needs shared libraries (libllama.so, libggml.so, libggml-base.so beside libwcdialogue.so).
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
    set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
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
    # 16 KB page alignment (Android 15+ devices)
    target_link_options(wcdialogue PRIVATE -Wl,-z,max-page-size=16384)
endif()

if (ANDROID)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)   # nothing after this file asks for it, but the cache is shared with the rest of the build
endif()
