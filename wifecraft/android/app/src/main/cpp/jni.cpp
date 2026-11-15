// The Kotlin side's door into the renderer (com.logie.wifecraft.engine.Native). Every scene call
// runs on the GL thread; decodeBlp may run anywhere.
#include <jni.h>
#include <sys/stat.h>

#include <algorithm>
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

JNIEXPORT void JNICALL FN(showCity)(JNIEnv*, jclass, jlong h, jboolean shown) { scene(h)->showCity(shown); }

JNIEXPORT jint JNICALL FN(addActor)(JNIEnv* env, jclass, jlong h, jint model, jintArray texKeys, jintArray texValues,
                                     jintArray geoKeys, jintArray geoValues, jfloat scale) {
    std::map<int, int> geo;
    auto k = ints(env, geoKeys), v = ints(env, geoValues);
    for (size_t i = 0; i < k.size() && i < v.size(); ++i) geo[k[i]] = v[i];
    return scene(h)->addActor(uint32_t(model), textureMap(env, texKeys, texValues), geo, scale);
}

JNIEXPORT jstring JNICALL FN(modelError)(JNIEnv* env, jclass, jlong h, jint model) {
    return env->NewStringUTF(scene(h)->modelError(uint32_t(model)).c_str());
}

JNIEXPORT void JNICALL FN(attach)(JNIEnv* env, jclass, jlong h, jint id, jint attachment, jint model, jintArray texKeys, jintArray texValues) {
    scene(h)->attach(id, uint32_t(attachment), uint32_t(model), textureMap(env, texKeys, texValues));
}

// The whole look of a standing person, changed in place (Native.dress documents the arrays).
JNIEXPORT void JNICALL FN(dress)(JNIEnv* env, jclass, jlong h, jint id, jintArray geosetKeys, jintArray geosetValues,
                                 jintArray layerFiles, jintArray layerMeta, jintArray tintColours, jintArray extraModels) {
    constexpr size_t kMeta = 9, kTints = 2, kExtra = 4;
    std::map<int, int> geo;
    auto gk = ints(env, geosetKeys), gv = ints(env, geosetValues);
    for (size_t i = 0; i < gk.size() && i < gv.size(); ++i) geo[gk[i]] = gv[i];
    auto files = ints(env, layerFiles), meta = ints(env, layerMeta), tints = ints(env, tintColours);
    std::vector<SkinLayer> layers;
    for (size_t i = 0; i < files.size() && (i + 1) * kMeta <= meta.size(); ++i) {
        const int* m = &meta[i * kMeta];
        SkinLayer l;
        l.fileId = uint32_t(files[i]);
        l.textureType = m[0]; l.layer = m[1]; l.blend = m[2];
        l.x = m[3]; l.y = m[4]; l.w = m[5]; l.h = m[6];
        l.materialW = m[7]; l.materialH = m[8];
        if ((i + 1) * kTints <= tints.size()) { l.tint1 = uint32_t(tints[i * kTints]); l.tint2 = uint32_t(tints[i * kTints + 1]); }
        layers.push_back(l);
    }
    auto ex = ints(env, extraModels);
    std::vector<DressModel> models;
    for (size_t i = 0; i + kExtra <= ex.size(); i += kExtra) models.push_back({ex[i], uint32_t(ex[i + 1]), uint32_t(ex[i + 2]), ex[i + 3]});
    scene(h)->dress(id, geo, layers, models);
}

// Wrath++'s body options on a standing person (Native.body documents the arrays).
JNIEXPORT void JNICALL FN(body)(JNIEnv* env, jclass, jlong h, jint id, jfloatArray morph, jfloat k, jfloat fold) {
    scene(h)->setBody(id, floats(env, morph), k, fold);
}

JNIEXPORT void JNICALL FN(removeActor)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->removeActor(id); }

JNIEXPORT void JNICALL FN(place)(JNIEnv*, jclass, jlong h, jint id, jfloat x, jfloat y, jfloat z, jfloat facing) {
    scene(h)->placeActor(id, V3(x, y, z), facing);
}

JNIEXPORT void JNICALL FN(anim)(JNIEnv*, jclass, jlong h, jint id, jint anim, jboolean loop, jint now) {
    scene(h)->playAnim(id, uint16_t(anim), loop, uint32_t(now));
}

// An imported animation on a person (Native.boneAnim documents the arrays). Returns the tracks this model has a bone for.
JNIEXPORT jint JNICALL FN(boneAnim)(JNIEnv* env, jclass, jlong h, jint id, jobjectArray bones, jintArray channels,
                                    jintArray keyCounts, jfloatArray times, jfloatArray values, jfloat duration,
                                    jboolean loop, jint now) {
    auto ch = ints(env, channels), counts = ints(env, keyCounts);
    auto ts = floats(env, times), vs = floats(env, values);
    jsize n = bones ? env->GetArrayLength(bones) : 0;
    BoneAnim anim;
    anim.duration = duration;
    anim.loop = loop;
    size_t ti = 0, vi = 0;
    for (jsize i = 0; i < n && size_t(i) < ch.size() && size_t(i) < counts.size(); ++i) {
        auto js = (jstring)env->GetObjectArrayElement(bones, i);
        BoneAnimTrack t;
        if (js) { t.bone = str(env, js); env->DeleteLocalRef(js); }
        t.rotation = ch[size_t(i)] == 0;
        size_t keys = size_t(std::max(0, counts[size_t(i)])), per = t.rotation ? 4 : 3;
        if (ti + keys > ts.size() || vi + keys * per > vs.size()) break;
        t.times.assign(ts.begin() + long(ti), ts.begin() + long(ti + keys));
        t.values.assign(vs.begin() + long(vi), vs.begin() + long(vi + keys * per));
        ti += keys; vi += keys * per;
        anim.tracks.push_back(std::move(t));
    }
    return scene(h)->setBoneAnim(id, anim, uint32_t(now));
}

JNIEXPORT void JNICALL FN(clearBoneAnim)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->clearBoneAnim(id); }

// Options' Soft body: 0 off, 1 subtle, 2 natural, 3 strong (engine/softbody.h).
JNIEXPORT void JNICALL FN(softBody)(JNIEnv*, jclass, jlong h, jint level) { scene(h)->setSoftBody(level); }

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
