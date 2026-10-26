// The Kotlin side's door into the renderer (com.logie.wifecraft.engine.Native). Every scene call
// runs on the GL thread; decodeBlp may run anywhere.
#include <jni.h>
#include <sys/stat.h>

#include <map>
#include <string>
#include <vector>

#include "engine/blp.h"
#include "engine/scene.h"

using namespace wc;

namespace {

Scene* scene(jlong h) { return reinterpret_cast<Scene*>(h); }

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<int> ints(JNIEnv* env, jintArray a) {
    std::vector<int> out;
    if (!a) return out;
    jsize n = env->GetArrayLength(a);
    out.resize(size_t(n));
    if (n) env->GetIntArrayRegion(a, 0, n, out.data());
    return out;
}

std::vector<float> floats(JNIEnv* env, jfloatArray a) {
    std::vector<float> out;
    if (!a) return out;
    jsize n = env->GetArrayLength(a);
    out.resize(size_t(n));
    if (n) env->GetFloatArrayRegion(a, 0, n, out.data());
    return out;
}

std::map<uint32_t, uint32_t> textureMap(JNIEnv* env, jintArray keys, jintArray values) {
    std::map<uint32_t, uint32_t> m;
    auto k = ints(env, keys), v = ints(env, values);
    for (size_t i = 0; i < k.size() && i < v.size(); ++i) m[uint32_t(k[i])] = uint32_t(v[i]);
    return m;
}

} // namespace

#define FN(name) Java_com_logie_wifecraft_engine_Native_##name

extern "C" {

JNIEXPORT jlong JNICALL FN(create)(JNIEnv* env, jclass, jstring dir) {
    std::string base = str(env, dir);
    auto* s = new Scene([base](uint32_t id) {
        std::string p = base + "/" + std::to_string(id);
        struct stat st;
        return stat(p.c_str(), &st) == 0 && st.st_size > 0 ? p : std::string();
    });
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv*, jclass, jlong h) { delete scene(h); }

JNIEXPORT void JNICALL FN(initGl)(JNIEnv*, jclass, jlong h) { scene(h)->releaseGl(); scene(h)->initGl(); }

JNIEXPORT void JNICALL FN(render)(JNIEnv*, jclass, jlong h, jint w, jint ht, jint now) {
    scene(h)->render(w, ht, uint32_t(now));
}

JNIEXPORT jboolean JNICALL FN(loadCity)(JNIEnv* env, jclass, jlong h, jint root, jintArray groups, jfloatArray centres, jfloat radius) {
    auto g = ints(env, groups);
    auto c = floats(env, centres);
    std::vector<V3> pts;
    for (size_t i = 0; i + 2 < c.size(); i += 3) pts.emplace_back(c[i], c[i + 1], c[i + 2]);
    return scene(h)->loadCity(uint32_t(root), g, pts, radius);
}

JNIEXPORT jint JNICALL FN(addActor)(JNIEnv* env, jclass, jlong h, jint model, jintArray texKeys, jintArray texValues,
                                     jintArray geoKeys, jintArray geoValues, jfloat scale) {
    std::map<int, int> geo;
    auto k = ints(env, geoKeys), v = ints(env, geoValues);
    for (size_t i = 0; i < k.size() && i < v.size(); ++i) geo[k[i]] = v[i];
    return scene(h)->addActor(uint32_t(model), textureMap(env, texKeys, texValues), geo, scale);
}

JNIEXPORT void JNICALL FN(attach)(JNIEnv* env, jclass, jlong h, jint id, jint attachment, jint model, jintArray texKeys, jintArray texValues) {
    scene(h)->attach(id, uint32_t(attachment), uint32_t(model), textureMap(env, texKeys, texValues));
}

JNIEXPORT void JNICALL FN(removeActor)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->removeActor(id); }

JNIEXPORT void JNICALL FN(place)(JNIEnv*, jclass, jlong h, jint id, jfloat x, jfloat y, jfloat z, jfloat facing) {
    scene(h)->placeActor(id, V3(x, y, z), facing);
}

JNIEXPORT void JNICALL FN(anim)(JNIEnv*, jclass, jlong h, jint id, jint anim, jboolean loop, jint now) {
    scene(h)->playAnim(id, uint16_t(anim), loop, uint32_t(now));
}

JNIEXPORT void JNICALL FN(idle)(JNIEnv*, jclass, jlong h, jint id, jint anim) { scene(h)->setIdleAnim(id, uint16_t(anim)); }

JNIEXPORT void JNICALL FN(show)(JNIEnv*, jclass, jlong h, jint id, jboolean visible) { scene(h)->showActor(id, visible); }

JNIEXPORT jfloat JNICALL FN(height)(JNIEnv*, jclass, jlong h, jint id) { return scene(h)->actorHeight(id); }

JNIEXPORT void JNICALL FN(camera)(JNIEnv*, jclass, jlong h, jfloat ex, jfloat ey, jfloat ez, jfloat tx, jfloat ty, jfloat tz, jfloat fov) {
    scene(h)->setCamera(V3(ex, ey, ez), V3(tx, ty, tz), fov);
}

// 3 sky top, 3 horizon, 3 below, 3 sun direction, 3 sun colour, 3 ambient, 3 fog colour, fog start, fog end
JNIEXPORT void JNICALL FN(light)(JNIEnv* env, jclass, jlong h, jfloatArray values) {
    auto v = floats(env, values);
    if (v.size() < 23) return;
    Light l;
    float* dst[] = {l.skyTop, l.skyHorizon, l.skyBelow, l.sunDir, l.sunColor, l.ambient, l.fogColor};
    for (int i = 0; i < 7; ++i) for (int k = 0; k < 3; ++k) dst[i][k] = v[size_t(i * 3 + k)];
    l.fogStart = v[21]; l.fogEnd = v[22];
    scene(h)->setLight(l);
}

// [width, height, ARGB pixels...] or null: for the interface's textures, decoded off the GL thread.
JNIEXPORT jintArray JNICALL FN(decodeBlp)(JNIEnv* env, jclass, jstring path, jint maxSize) {
    Bytes b;
    Image img;
    if (!readFile(str(env, path), b) || !decodeBlp(b, maxSize, img)) return nullptr;
    size_t n = size_t(img.width) * img.height;
    std::vector<jint> out(n + 2);
    out[0] = img.width; out[1] = img.height;
    for (size_t i = 0; i < n; ++i) {
        const uint8_t* p = &img.rgba[i * 4];
        out[i + 2] = jint(uint32_t(p[3]) << 24 | uint32_t(p[0]) << 16 | uint32_t(p[1]) << 8 | p[2]);
    }
    jintArray arr = env->NewIntArray(jsize(out.size()));
    env->SetIntArrayRegion(arr, 0, jsize(out.size()), out.data());
    return arr;
}

}
