# The renderer: Dalaran, the people and the camera, drawn with OpenGL ES 3 (engine/), and the
# Kotlin side's door into it (jni.cpp).
cmake_minimum_required(VERSION 3.22.1)
project(wifecraft CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(wcengine SHARED
    jni.cpp
    engine/blp.cpp
    engine/bytes.cpp
    engine/m2.cpp
    engine/scene.cpp
    engine/wmo.cpp)

target_compile_options(wcengine PRIVATE -O2 -Wall -Wno-unused-parameter)
target_link_libraries(wcengine GLESv3 log)
