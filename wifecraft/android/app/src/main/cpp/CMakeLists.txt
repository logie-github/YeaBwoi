# The renderer: Dalaran, the locations (a WMO, its doodads and the map's ground) and the editor's props, the people and the camera, drawn with OpenGL ES 3 (engine/), and the
# Kotlin side's door into it (jni.cpp).
cmake_minimum_required(VERSION 3.22.1)
project(wifecraft CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(wcengine SHARED
    jni.cpp
    engine/blp.cpp
    engine/bodypart.cpp
    engine/bodypick.cpp
    engine/bodyrig.cpp
    engine/bodyshape.cpp
    engine/buildview.cpp
    engine/bytes.cpp
    engine/cloth.cpp
    engine/daylight.cpp
    engine/grade.cpp
    engine/hdbody.cpp
    engine/hdscene.cpp
    engine/editview.cpp
    engine/evebody.cpp
    engine/evescene.cpp
    engine/json.cpp
    engine/location.cpp
    engine/m2.cpp
    engine/modelpart.cpp
    engine/motionlayer.cpp
    engine/particles.cpp
    engine/postfx.cpp
    engine/scene.cpp
    engine/scene_gfx.cpp
    engine/sculpt.cpp
    engine/skin.cpp
    engine/softbody.cpp
    engine/terrain.cpp
    engine/wmo.cpp)

target_compile_options(wcengine PRIVATE -O2 -Wall -Wno-unused-parameter)
target_link_libraries(wcengine GLESv3 EGL log z)   # z: the Eve body's PNG files (engine/evebody.cpp)
