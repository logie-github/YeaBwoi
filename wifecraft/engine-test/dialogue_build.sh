#!/usr/bin/env bash
# Builds the dialogue core and its desktop test against a llama.cpp checkout of the app's tag (WC_LLAMA_TAG in
# android/app/src/main/cpp/dialogue/CMakeLists.txt):
#   git clone --depth 1 --branch v1.0.0-b12359-b1b770d https://github.com/ggml-org/llama.cpp /some/dir/llama.cpp
#   engine-test/dialogue_build.sh /some/dir/llama.cpp [BUILD_DIR]
# then: BUILD_DIR/dialogue_test MODEL.gguf   (a small model such as Qwen2.5-0.5B-Instruct Q4_K_M is enough)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
llama="${1:?usage: dialogue_build.sh LLAMA_CPP_DIR [BUILD_DIR]}"
out="${2:-$here/build/dialogue}"
cmake -S "$here/dialogue" -B "$out" -DWC_LLAMA_SOURCE_DIR="$(cd "$llama" && pwd)" -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON > /dev/null
cmake --build "$out" --target dialogue_test -j"$(nproc)"
echo "$out/dialogue_test"
