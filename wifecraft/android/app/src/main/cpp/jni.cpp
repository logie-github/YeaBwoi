// The Kotlin side's door into the renderer (com.logie.wifecraft.engine.Native). Every scene call
// runs on the GL thread; decodeBlp may run anywhere.
#include <jni.h>
#include <sys/stat.h>

#include <algorithm>
#include <cstdio>
#include <limits>
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

JNIEXPORT void JNICALL FN(place)(JNIEnv*, jclass, jlong h, jint id, jfloat x, jfloat y, jfloat z, jfloat facing,
                                  jint furniture, jfloat sx, jfloat sy, jfloat sz) {
    scene(h)->placeActor(id, V3(x, y, z), facing, furniture, V3(sx, sy, sz));
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

// The tracks of an imported animation's arrays (Native.boneAnim's), for a layer or a reach.
static BoneAnim boneTracks(JNIEnv* env, jobjectArray bones, jintArray channels, jintArray keyCounts, jfloatArray times,
                           jfloatArray values, jfloat duration, jboolean loop) {
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
    return anim;
}

// A bone layer on an actor (Native.boneLayer): rotation keys turned onto its own animation. Returns the tracks that found a bone.
JNIEXPORT jint JNICALL FN(boneLayer)(JNIEnv* env, jclass, jlong h, jint id, jobjectArray bones, jintArray channels,
                                     jintArray keyCounts, jfloatArray times, jfloatArray values, jfloat duration,
                                     jboolean loop, jint now) {
    return scene(h)->setBoneLayer(id, boneTracks(env, bones, channels, keyCounts, times, values, duration, loop), uint32_t(now));
}
JNIEXPORT void JNICALL FN(clearBoneLayer)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->clearBoneLayer(id); }

// A reach (Native.reach): the actor's arm on a point of another actor's body, its weight and offsets keyed in the arrays.
// Where an actor's head, chest and pelvis bones are as last drawn (Native.bonePoints; world x, y, z each), or null.
JNIEXPORT jfloatArray JNICALL FN(bonePoints)(JNIEnv* env, jclass, jlong h, jint id) {
    float p[9];
    if (!scene(h)->actorBonePoints(id, p)) return nullptr;
    jfloatArray out = env->NewFloatArray(9);
    if (out) env->SetFloatArrayRegion(out, 0, 9, p);
    return out;
}

JNIEXPORT jint JNICALL FN(reach)(JNIEnv* env, jclass, jlong h, jint id, jint target, jint targetBone, jboolean left,
                                 jobjectArray bones, jintArray channels, jintArray keyCounts, jfloatArray times,
                                 jfloatArray values, jfloat duration, jboolean loop, jint now) {
    return scene(h)->setReach(id, target, targetBone, left == JNI_TRUE,
                              boneTracks(env, bones, channels, keyCounts, times, values, duration, loop), uint32_t(now));
}
JNIEXPORT void JNICALL FN(clearReach)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->clearReach(id); }

// Options' Soft body: 0 off, 1 subtle, 2 natural, 3 strong (engine/softbody.h).
JNIEXPORT void JNICALL FN(softBody)(JNIEnv*, jclass, jlong h, jint level) { scene(h)->setSoftBody(level); }
// Whether an actor is a character (a playable race's model): only characters have a soft body.
JNIEXPORT void JNICALL FN(character)(JNIEnv*, jclass, jlong h, jint id, jboolean on) { scene(h)->setCharacter(id, on); }

JNIEXPORT void JNICALL FN(idle)(JNIEnv*, jclass, jlong h, jint id, jint anim) { scene(h)->setIdleAnim(id, uint16_t(anim)); }

JNIEXPORT void JNICALL FN(show)(JNIEnv*, jclass, jlong h, jint id, jboolean visible) { scene(h)->showActor(id, visible); }

JNIEXPORT void JNICALL FN(setActorScale)(JNIEnv*, jclass, jlong h, jint id, jfloat scale) { scene(h)->setActorScale(id, scale); }

JNIEXPORT jfloat JNICALL FN(height)(JNIEnv*, jclass, jlong h, jint id) { return scene(h)->actorHeight(id); }

// Human scale (anim/HumanScale.kt): the actor's standing height in model units (0: no humanoid skeleton), and the root
// motion of its imported animations in the pair's shared yards (0: off).
JNIEXPORT jfloat JNICALL FN(standHeight)(JNIEnv*, jclass, jlong h, jint id) { return scene(h)->actorStandHeight(id); }
JNIEXPORT void JNICALL FN(humanRootMotion)(JNIEnv*, jclass, jlong h, jint id, jfloat xy) { scene(h)->setHumanRootMotion(id, xy); }

// Body parts (engine/bodypart.h): a part file loaded (its handle, 0: partError says why), put on an actor with its tinted
// texture (RGBA, w x h, top row first), which slot is drawn, the race's fit, taken off, and where to sample the skin.
JNIEXPORT jint JNICALL FN(loadPart)(JNIEnv* env, jclass, jlong h, jstring path) { return scene(h)->loadPart(str(env, path)); }
JNIEXPORT jstring JNICALL FN(partError)(JNIEnv* env, jclass, jlong h) { return env->NewStringUTF(scene(h)->partError().c_str()); }
JNIEXPORT jboolean JNICALL FN(attachPart)(JNIEnv* env, jclass, jlong h, jint id, jint part, jbyteArray rgba, jint w, jint ht) {
    if (!rgba || w <= 0 || ht <= 0) return JNI_FALSE;
    jsize n = env->GetArrayLength(rgba);
    if (int64_t(n) != int64_t(w) * int64_t(ht) * 4) return JNI_FALSE;
    std::vector<uint8_t> px(static_cast<size_t>(n));
    env->GetByteArrayRegion(rgba, 0, n, reinterpret_cast<jbyte*>(px.data()));
    return scene(h)->attachPart(id, part, px.data(), w, ht) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL FN(partState)(JNIEnv*, jclass, jlong h, jint id, jint state) { scene(h)->setPartState(id, state); }
JNIEXPORT void JNICALL FN(partFit)(JNIEnv*, jclass, jlong h, jint id, jfloat dx, jfloat dz, jfloat scale) {
    PartFit f;
    f.dx = dx; f.dz = dz; f.scale = scale;
    scene(h)->setPartFit(id, f);
}
JNIEXPORT void JNICALL FN(partDrive)(JNIEnv*, jclass, jlong h, jint id, jfloat level, jint partner, jint entry, jfloat rest) {
    scene(h)->setPartDrive(id, level, partner, entry, rest);
}
JNIEXPORT void JNICALL FN(partSkin)(JNIEnv*, jclass, jlong h, jint id, jint rgb) { scene(h)->setPartSkin(id, rgb); }
JNIEXPORT void JNICALL FN(detachParts)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->detachParts(id); }
JNIEXPORT jfloatArray JNICALL FN(partSkinUvs)(JNIEnv* env, jclass, jlong h, jint id) {
    std::vector<float> uv = scene(h)->partSkinUvs(id);
    jfloatArray out = env->NewFloatArray(jsize(uv.size()));
    if (out && !uv.empty()) env->SetFloatArrayRegion(out, 0, jsize(uv.size()), uv.data());
    return out;
}

// HD Models (engine/hdbody.h, hdscene.cpp): a set loaded under its race and sex ("" or why not), all of them dropped, one
// actor drawn as its HD figure with its options (bodyparts/HdModels: hair, outfit, eyes, face mark, body marks, skin tint,
// hair tint, jewellery), taken off, and what became of it ("on|off|scale|ms|triangles|why").
JNIEXPORT jstring JNICALL FN(loadHdSet)(JNIEnv* env, jclass, jlong h, jstring path) {
    std::string err;
    bool ok = scene(h)->loadHdSet(str(env, path), err);
    return env->NewStringUTF(ok ? "" : err.c_str());
}

JNIEXPORT void JNICALL FN(clearHdSets)(JNIEnv*, jclass, jlong h) { scene(h)->clearHdSets(); }

JNIEXPORT void JNICALL FN(setHdBody)(JNIEnv* env, jclass, jlong h, jint id, jint race, jint sex, jboolean on, jint hair, jint outfit, jint eyes,
                                     jint faceMark, jint bodyMarks, jint tint, jint hairTint, jintArray jewelry, jint body, jfloatArray shapes) {
    wc::HdOptions o;
    o.on = on == JNI_TRUE;
    o.hair = hair; o.outfit = outfit; o.eyes = eyes; o.faceMark = faceMark; o.bodyMarks = bodyMarks;
    o.tint = tint; o.hairTint = hairTint; o.body = body;
    for (int j : ints(env, jewelry)) o.jewelry.push_back(j);
    o.shapes = floats(env, shapes);
    scene(h)->setHdBody(id, race, sex, o);
}

// The HD body's own genitals hidden (`on`: the add-on parts drawn in their place, bodypart.h) or shown again.
JNIEXPORT void JNICALL FN(useHdGenitalMask)(JNIEnv*, jclass, jlong h, jint id, jboolean on) { scene(h)->useHdGenitalMask(id, on == JNI_TRUE); }

JNIEXPORT void JNICALL FN(clearHdBody)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->clearHdBody(id); }

JNIEXPORT jstring JNICALL FN(hdStatus)(JNIEnv* env, jclass, jlong h, jint id) {
    wc::Scene::HdStatus st;
    if (!scene(h)->hdStatus(id, st)) return env->NewStringUTF("no such actor");
    char buf[160];
    std::snprintf(buf, sizeof buf, "%s|%.3f|%.1f|%zu", st.active ? "on" : "off", st.stats.scale, st.stats.ms, st.stats.triangles);
    return env->NewStringUTF((std::string(buf) + "|" + st.status).c_str());
}

// Model parts (engine/modelpart.h): a version 2 part on an actor's named bone with its fit (its handle on the actor, 0: not
// one), shown or hidden and refitted by handle (0: all), taken off; the stand-in's cut (bone names), its skin tint and where
// to sample its face's skin; and a manifest's set cut from the downloaded files into files/bodyparts/<set>/ (any thread).
JNIEXPORT jint JNICALL FN(attachBonePart)(JNIEnv*, jclass, jlong h, jint id, jint part, jfloat dx, jfloat dy, jfloat dz, jfloat scale) {
    BonePartFit f;
    f.offset = V3(dx, dy, dz);
    f.scale = scale;
    return scene(h)->attachBonePart(id, part, f);
}
JNIEXPORT void JNICALL FN(bonePartVisible)(JNIEnv*, jclass, jlong h, jint id, jint handle, jboolean visible) {
    scene(h)->setBonePartVisible(id, handle, visible == JNI_TRUE);
}
JNIEXPORT void JNICALL FN(bonePartFit)(JNIEnv*, jclass, jlong h, jint id, jint handle, jfloat dx, jfloat dy, jfloat dz, jfloat scale) {
    BonePartFit f;
    f.offset = V3(dx, dy, dz);
    f.scale = scale;
    scene(h)->setBonePartFit(id, handle, f);
}
JNIEXPORT void JNICALL FN(detachBoneParts)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->detachBoneParts(id); }
JNIEXPORT void JNICALL FN(bodyCut)(JNIEnv* env, jclass, jlong h, jint id, jobjectArray bones) {
    std::vector<std::string> names;
    jsize n = bones ? env->GetArrayLength(bones) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(bones, i));
        if (s) { names.push_back(str(env, s)); env->DeleteLocalRef(s); }
    }
    scene(h)->setBodyCut(id, names);
}
JNIEXPORT void JNICALL FN(bodyTint)(JNIEnv*, jclass, jlong h, jint id, jfloat r, jfloat g, jfloat b) { scene(h)->setBodyTint(id, r, g, b); }
JNIEXPORT jfloatArray JNICALL FN(faceSkinUvs)(JNIEnv* env, jclass, jlong h, jint id) {
    std::vector<float> uv = scene(h)->faceSkinUvs(id);
    jfloatArray out = env->NewFloatArray(jsize(uv.size()));
    if (out && !uv.empty()) env->SetFloatArrayRegion(out, 0, jsize(uv.size()), uv.data());
    return out;
}
JNIEXPORT jstring JNICALL FN(buildModelParts)(JNIEnv* env, jclass, jstring manifest, jstring filesDir, jstring outDir, jstring set) {
    std::string base = str(env, filesDir), only = str(env, set), report;
    FileLookup files = [base](uint32_t fid) {
        std::string p = base + "/" + std::to_string(fid);
        struct stat st;
        return stat(p.c_str(), &st) == 0 && st.st_size > 0 ? p : std::string();
    };
    ModelPartManifest man;
    std::string err;
    if (!parseModelPartManifest(str(env, manifest), man, err)) return env->NewStringUTF(("failed\nmanifest: " + err).c_str());
    bool any = false, ok = true;
    for (const ModelPartSet& s : man.sets) {
        if (!only.empty() && s.set != only) continue;
        any = true;
        std::string r;
        if (!buildModelPartSet(s, man, files, str(env, outDir), r)) ok = false;
        report += "== " + s.set + "\n" + r;
    }
    if (!any) { ok = false; report = "no set " + only + "\n"; }
    return env->NewStringUTF(((ok ? "ok\n" : "failed\n") + report).c_str());
}

JNIEXPORT void JNICALL FN(camera)(JNIEnv*, jclass, jlong h, jfloat ex, jfloat ey, jfloat ez, jfloat tx, jfloat ty, jfloat tz, jfloat fov) {
    scene(h)->setCamera(V3(ex, ey, ez), V3(tx, ty, tz), fov);
}

// Depth of field's sharp band on the subject (Scene::setFocus; far <= 0: the scene's own band).
JNIEXPORT void JNICALL FN(focus)(JNIEnv*, jclass, jlong h, jfloat nearD, jfloat farD, jfloat strength) {
    scene(h)->setFocus(nearD, farD, strength);
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

// The world clock's hour on the location's light (Scene::setTimeOfDay); negative: off.
JNIEXPORT void JNICALL FN(timeOfDay)(JNIEnv*, jclass, jlong h, jfloat hours) {
    scene(h)->setTimeOfDay(hours);
}

// Cinematic mode's look (Scene::setCinematic): the mood's strength, the vignette, the grain, the letterbox bars.
JNIEXPORT void JNICALL FN(cinematic)(JNIEnv*, jclass, jlong h, jfloat mood, jfloat vignette, jfloat grain, jfloat bars) {
    scene(h)->setCinematic(mood, vignette, grain, bars);
}

// Cinematic mode's zone mood (Scene::setZoneMood): its strength 0..1 and the boost.
JNIEXPORT void JNICALL FN(zoneMood)(JNIEnv*, jclass, jlong h, jfloat strength, jboolean boost) {
    scene(h)->setZoneMood(strength, boost == JNI_TRUE);
}

// The body's additive motion (engine/motionlayer.h): Options' shares for everyone, and one actor's intensity and look target.
JNIEXPORT void JNICALL FN(motionParams)(JNIEnv*, jclass, jlong h, jfloat breathing, jfloat idle, jboolean lookAt) {
    wc::MotionParams p;
    p.breathing = breathing; p.idle = idle; p.lookAt = lookAt;
    scene(h)->setMotionParams(p);
}
JNIEXPORT void JNICALL FN(motion)(JNIEnv*, jclass, jlong h, jint id, jfloat intensity, jint target) {
    scene(h)->setMotion(id, intensity, target);
}
JNIEXPORT void JNICALL FN(clearMotion)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->clearMotion(id); }
// The atmosphere's glowing motes (engine/particles.h): how many (0: none) and how bright.
JNIEXPORT void JNICALL FN(particles)(JNIEnv*, jclass, jlong h, jint count, jfloat strength) {
    scene(h)->setParticles(count, strength);
}

// Interactable sparkles (engine/sparkle.h): spots of 8 floats (x, y, z, halfX, halfY, yaw, strength, seed), the density 0..1.
JNIEXPORT void JNICALL FN(sparkles)(JNIEnv* env, jclass, jlong h, jfloatArray data, jfloat density) {
    std::vector<float> v;
    if (data) {
        jsize n = env->GetArrayLength(data);
        v.resize(size_t(n));
        if (n > 0) env->GetFloatArrayRegion(data, 0, n, v.data());
    }
    scene(h)->setSparkles(v.data(), int(v.size()) / SparkleField::kStride, density);
}

// Props (engine/propmesh.h): a ".wcprop" file kept under its object id ("" or why not), the frame's copies (ids, then 16 floats each),
// and one forgotten.
JNIEXPORT jstring JNICALL FN(loadProp)(JNIEnv* env, jclass, jlong h, jlong id, jbyteArray data) {
    std::string err;
    if (!data) return env->NewStringUTF("no data");
    jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> v(size_t(n > 0 ? n : 0));
    if (n > 0) env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(v.data()));
    bool ok = scene(h)->loadProp(uint64_t(id), v.data(), v.size(), err);
    return env->NewStringUTF(ok ? "" : err.c_str());
}
JNIEXPORT void JNICALL FN(unloadProp)(JNIEnv*, jclass, jlong h, jlong id) { scene(h)->unloadProp(uint64_t(id)); }
JNIEXPORT void JNICALL FN(props)(JNIEnv* env, jclass, jlong h, jlongArray ids, jfloatArray matrices) {
    std::vector<uint64_t> id;
    std::vector<float> m;
    if (ids && matrices) {
        jsize n = env->GetArrayLength(ids);
        jsize k = env->GetArrayLength(matrices);
        if (n > 0 && k >= n * 16) {
            id.resize(size_t(n));
            m.resize(size_t(n) * 16);
            env->GetLongArrayRegion(ids, 0, n, reinterpret_cast<jlong*>(id.data()));
            env->GetFloatArrayRegion(matrices, 0, n * 16, m.data());
        }
    }
    scene(h)->setProps(id.data(), m.data(), int(id.size()));
}

JNIEXPORT void JNICALL FN(graphics)(JNIEnv* env, jclass, jlong h, jfloatArray values) {
    auto v = floats(env, values);
    scene(h)->setGraphics(v.data(), int(v.size()));
}

JNIEXPORT void JNICALL FN(graphicsStats)(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    if (!out) return;
    jsize n = std::min<jsize>(env->GetArrayLength(out), 32);
    float v[32];
    scene(h)->graphicsStats(v, int(n));
    env->SetFloatArrayRegion(out, 0, n, v);
}

// One actor's skin sheen 0..1 (Scene::setSheen).
JNIEXPORT void JNICALL FN(sheen)(JNIEnv*, jclass, jlong h, jint id, jfloat value) { scene(h)->setSheen(id, value); }

// ---- locations and the editor's props (Native.kt documents them)

JNIEXPORT jboolean JNICALL FN(loadLocation)(JNIEnv* env, jclass, jlong h, jstring json, jstring id, jstring view) {
    return scene(h)->loadLocation(str(env, json), id ? str(env, id) : "", view ? str(env, view) : "");
}

JNIEXPORT jboolean JNICALL FN(locationView)(JNIEnv* env, jclass, jlong h, jstring view) {
    return scene(h)->locationView(view ? str(env, view) : "");
}

JNIEXPORT void JNICALL FN(clearLocation)(JNIEnv*, jclass, jlong h) { scene(h)->clearLocation(); }

JNIEXPORT jstring JNICALL FN(locationError)(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(scene(h)->locationError().c_str());
}

JNIEXPORT jboolean JNICALL FN(creatorBackdrop)(JNIEnv*, jclass, jlong h, jint model) {
    return scene(h)->creatorBackdrop(uint32_t(model));
}

JNIEXPORT jint JNICALL FN(addProp)(JNIEnv*, jclass, jlong h, jint model, jfloat x, jfloat y, jfloat z, jfloat yaw, jfloat scale) {
    return scene(h)->addProp(uint32_t(model), V3(x, y, z), yaw, scale);
}

JNIEXPORT jboolean JNICALL FN(moveProp)(JNIEnv*, jclass, jlong h, jint id, jfloat x, jfloat y, jfloat z, jfloat yaw, jfloat scale) {
    return scene(h)->moveProp(id, V3(x, y, z), yaw, scale);
}

JNIEXPORT void JNICALL FN(removeProp)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->removeProp(id); }

JNIEXPORT void JNICALL FN(clearProps)(JNIEnv*, jclass, jlong h) { scene(h)->clearProps(); }

JNIEXPORT jfloatArray JNICALL FN(propBounds)(JNIEnv* env, jclass, jlong h, jint id) {
    V3 mn, mx;
    if (!scene(h)->propBounds(id, mn, mx)) return nullptr;
    float v[6] = {mn.x, mn.y, mn.z, mx.x, mx.y, mx.z};
    jfloatArray arr = env->NewFloatArray(6);
    env->SetFloatArrayRegion(arr, 0, 6, v);
    return arr;
}

JNIEXPORT jfloatArray JNICALL FN(propContact)(JNIEnv* env, jclass, jlong h, jint id, jint furniture) {
    V3 point;
    float facing, halfDepth;
    if (!scene(h)->propContact(id, furniture, point, facing, halfDepth)) return nullptr;
    const float v[] = {point.x, point.y, point.z, facing, halfDepth};
    jfloatArray arr = env->NewFloatArray(5);
    env->SetFloatArrayRegion(arr, 0, 5, v);
    return arr;
}

JNIEXPORT void JNICALL FN(select)(JNIEnv*, jclass, jlong h, jint kind, jint id) { scene(h)->select(kind, id); }

// [kind, id, x, y, z, distance] (kind 0: nothing)
JNIEXPORT jint JNICALL FN(locationObject)(JNIEnv*, jclass, jlong h, jint model, jfloat x, jfloat y, jfloat z) {
    return scene(h)->locationObject(uint32_t(model), V3(x, y, z));
}

JNIEXPORT jboolean JNICALL FN(walkSegment)(JNIEnv*, jclass, jlong h,
                                           jfloat x, jfloat y, jfloat z, jfloat tx, jfloat ty, jfloat tz) {
    return scene(h)->walkSegment(V3(x, y, z), V3(tx, ty, tz));
}

JNIEXPORT jfloatArray JNICALL FN(pick)(JNIEnv* env, jclass, jlong h, jfloat sx, jfloat sy, jint w, jint ht) {
    Scene::PickHit hit = scene(h)->pick(sx, sy, w, ht);
    float v[6] = {float(hit.kind), float(hit.id), hit.point.x, hit.point.y, hit.point.z, hit.distance};
    jfloatArray arr = env->NewFloatArray(6);
    env->SetFloatArrayRegion(arr, 0, 6, v);
    return arr;
}

// the creator's tap-to-edit and sculpt (engine/bodypick.h, engine/sculpt.h; Native.kt documents them)
JNIEXPORT jint JNICALL FN(pickBodyRegion)(JNIEnv*, jclass, jlong h, jint actor, jfloat sx, jfloat sy, jint w, jint ht) {
    return scene(h)->pickBodyRegion(actor, sx, sy, w, ht);
}

// Flesh contact (engine/contact.h): a finger at the screen point presses actor's skin with pressure 0..1 for holdMs after this push (the
// ContactRegion it hit, -1 a miss); releaseTouch lets go.
JNIEXPORT jint JNICALL FN(pushTouch)(JNIEnv*, jclass, jlong h, jint actor, jfloat sx, jfloat sy, jint w, jint ht, jfloat pressure, jint holdMs) {
    return scene(h)->pushTouch(actor, sx, sy, w, ht, pressure, holdMs);
}
JNIEXPORT void JNICALL FN(releaseTouch)(JNIEnv*, jclass, jlong h, jint actor) { scene(h)->releaseTouch(actor); }

// Interact mode's touch: pickBodyRegion's code and detail (8 floats; see Scene::pickBodyDetail), null when the point misses her
JNIEXPORT jfloatArray JNICALL FN(pickBodyDetail)(JNIEnv* env, jclass, jlong h, jint actor, jfloat sx, jfloat sy, jint w, jint ht) {
    float v[8];
    int code = scene(h)->pickBodyDetail(actor, sx, sy, w, ht, v);
    if (code == 0) return nullptr;
    v[0] = float(code);
    jfloatArray arr = env->NewFloatArray(8);
    env->SetFloatArrayRegion(arr, 0, 8, v);
    return arr;
}

// Stockings (engine/stockings.h): `v` = style, denier, r, g, b, seam, lace (null or empty: off). Returns the shell's triangles so far (0 until built).
JNIEXPORT jint JNICALL FN(setStockings)(JNIEnv* env, jclass, jlong h, jint actor, jfloatArray v) {
    if (!v || env->GetArrayLength(v) < 7) return scene(h)->setStockings(actor, nullptr);
    float f[7];
    env->GetFloatArrayRegion(v, 0, 7, f);
    StockSpec sp;
    sp.style = int(f[0]); sp.denier = int(f[1]); sp.r = f[2]; sp.g = f[3]; sp.b = f[4]; sp.seam = f[5] > 0.5f; sp.lace = f[6] > 0.5f;
    return scene(h)->setStockings(actor, &sp);
}

// The stockings' fabric (engine/stockfabric.h): a finger on the stocking, its drag, its release, the state, undo and repair
JNIEXPORT jboolean JNICALL FN(stockGrab)(JNIEnv*, jclass, jlong h, jint actor, jfloat sx, jfloat sy, jint w, jint ht) { return scene(h)->stockGrab(actor, sx, sy, w, ht); }
JNIEXPORT jboolean JNICALL FN(stockProbe)(JNIEnv*, jclass, jlong h, jint actor, jfloat sx, jfloat sy, jint w, jint ht) { return scene(h)->stockGrab(actor, sx, sy, w, ht, true); }
JNIEXPORT void JNICALL FN(stockLook)(JNIEnv*, jclass, jlong h, jint actor, jfloat elasticity, jfloat tearStrain, jfloat sheen, jfloat side) { scene(h)->stockLook(actor, elasticity, tearStrain, sheen, side); }
JNIEXPORT void JNICALL FN(stockDrag)(JNIEnv*, jclass, jlong h, jint actor, jfloat sx, jfloat sy, jint w, jint ht) { scene(h)->stockDrag(actor, sx, sy, w, ht); }
JNIEXPORT void JNICALL FN(stockRelease)(JNIEnv*, jclass, jlong h, jint actor) { scene(h)->stockRelease(actor); }
JNIEXPORT jboolean JNICALL FN(stockUndo)(JNIEnv*, jclass, jlong h, jint actor) { return scene(h)->stockUndo(actor); }
JNIEXPORT void JNICALL FN(stockRepair)(JNIEnv*, jclass, jlong h, jint actor) { scene(h)->stockRepair(actor); }
JNIEXPORT jfloatArray JNICALL FN(stockState)(JNIEnv* env, jclass, jlong h, jint actor) {
    float v[4];
    if (!scene(h)->stockState(actor, v)) return nullptr;
    jfloatArray arr = env->NewFloatArray(4);
    env->SetFloatArrayRegion(arr, 0, 4, v);
    return arr;
}
JNIEXPORT void JNICALL FN(stockIterations)(JNIEnv*, jclass, jlong h, jint n) { scene(h)->stockIterations(n); }

JNIEXPORT void JNICALL FN(setSculpt)(JNIEnv* env, jclass, jlong h, jint actor, jfloatArray values) {
    std::vector<float> v;
    if (values) {
        jsize n = env->GetArrayLength(values);
        v.resize(size_t(n));
        if (n > 0) env->GetFloatArrayRegion(values, 0, n, v.data());
    }
    scene(h)->setSculpt(actor, v.data(), v.size());
}

JNIEXPORT jfloat JNICALL FN(groundAt)(JNIEnv*, jclass, jlong h, jfloat x, jfloat y, jfloat z) {
    float out = 0;
    return scene(h)->groundAt(x, y, z, out) ? out : std::numeric_limits<float>::quiet_NaN();
}

// ---- the placement editor's view (engine/editview.cpp; Native.kt documents them)

JNIEXPORT void JNICALL FN(gizmo)(JNIEnv*, jclass, jlong h, jint mode, jint kind, jint id, jint axis) {
    scene(h)->setGizmo(mode, kind, id, axis);
}

JNIEXPORT jint JNICALL FN(gizmoHit)(JNIEnv*, jclass, jlong h, jfloat sx, jfloat sy, jint w, jint ht, jfloat slop) {
    return scene(h)->gizmoHit(sx, sy, w, ht, slop);
}

// ---- the live scene's build mode overlay (engine/buildview.cpp; Native.kt documents them)

JNIEXPORT void JNICALL FN(buildGizmo)(JNIEnv*, jclass, jlong h, jint flags, jfloat x, jfloat y, jfloat z, jfloat yaw, jint axis) {
    scene(h)->setBuildGizmo(flags, V3(x, y, z), yaw, axis);
}

JNIEXPORT void JNICALL FN(buildMarkers)(JNIEnv* env, jclass, jlong h, jfloatArray data) {
    std::vector<float> v;
    if (data) {
        jsize n = env->GetArrayLength(data);
        v.resize(size_t(n));
        if (n > 0) env->GetFloatArrayRegion(data, 0, n, v.data());
    }
    scene(h)->setBuildMarkers(v.data(), int(v.size()) / Scene::kBuildMarkerFloats);
}

JNIEXPORT void JNICALL FN(buildBox)(JNIEnv* env, jclass, jlong h, jfloatArray data) {
    float v[7] = {0, 0, 0, 0, 0, 0, 0};
    jsize n = data ? env->GetArrayLength(data) : 0;
    if (n >= 7) env->GetFloatArrayRegion(data, 0, 7, v);
    scene(h)->setBuildBox(v, n >= 7 ? 7 : 0);
}

JNIEXPORT jfloatArray JNICALL FN(locationObjectInfo)(JNIEnv* env, jclass, jlong h, jint index) {
    float v[12];
    if (!scene(h)->locationObjectInfo(index, v)) return nullptr;
    jfloatArray arr = env->NewFloatArray(12);
    env->SetFloatArrayRegion(arr, 0, 12, v);
    return arr;
}

// eye 3, target 3, up 3, fovY, aspect, view 16, proj 16 (column-major)
JNIEXPORT jfloatArray JNICALL FN(cameraInfo)(JNIEnv* env, jclass, jlong h) {
    Scene::CameraInfo c = scene(h)->cameraInfo();
    float v[43] = {c.eye.x, c.eye.y, c.eye.z, c.target.x, c.target.y, c.target.z, c.up.x, c.up.y, c.up.z, c.fovY, c.aspect};
    std::copy(c.view.m, c.view.m + 16, v + 11);
    std::copy(c.proj.m, c.proj.m + 16, v + 27);
    jfloatArray arr = env->NewFloatArray(43);
    env->SetFloatArrayRegion(arr, 0, 43, v);
    return arr;
}

// eye 3, target 3, fov; null when the loaded location's view has no camera
JNIEXPORT jfloatArray JNICALL FN(viewCamera)(JNIEnv* env, jclass, jlong h, jstring view) {
    V3 eye, target;
    float fov = 50;
    if (!scene(h)->viewCamera(view ? str(env, view) : "", eye, target, fov)) return nullptr;
    float v[7] = {eye.x, eye.y, eye.z, target.x, target.y, target.z, fov};
    jfloatArray arr = env->NewFloatArray(7);
    env->SetFloatArrayRegion(arr, 0, 7, v);
    return arr;
}

namespace {
jfloatArray boxArray(JNIEnv* env, const V3& mn, const V3& mx) {
    float v[6] = {mn.x, mn.y, mn.z, mx.x, mx.y, mx.z};
    jfloatArray arr = env->NewFloatArray(6);
    env->SetFloatArrayRegion(arr, 0, 6, v);
    return arr;
}
std::string gThumbError;
// The HD figure for the next look picture (thumbHd): taken once.
HdOptions gThumbHd;
int gThumbHdRace = 0, gThumbHdSex = 0;
} // namespace

// An actor's box in the world (its standing box, turned and placed as it stands); null: no such actor.
JNIEXPORT jfloatArray JNICALL FN(actorBounds)(JNIEnv* env, jclass, jlong h, jint id, jfloat x, jfloat y, jfloat z, jfloat facing) {
    V3 a, b;
    if (!scene(h)->actorBounds(id, a, b)) return nullptr;
    M4 m = M4::translation(V3(x, y, z)) * M4::rotationZ(facing);
    V3 mn(1e30f, 1e30f, 1e30f), mx(-1e30f, -1e30f, -1e30f);
    for (int k = 0; k < 8; ++k) {
        V3 p = m.point(V3((k & 1) ? b.x : a.x, (k & 2) ? b.y : a.y, (k & 4) ? b.z : a.z));
        mn = V3(std::min(mn.x, p.x), std::min(mn.y, p.y), std::min(mn.z, p.z));
        mx = V3(std::max(mx.x, p.x), std::max(mx.y, p.y), std::max(mx.z, p.z));
    }
    return boxArray(env, mn, mx);
}

// An actor's flesh around its main bones as dressed (engine/flesh.h, FleshMeasure::pack's floats; anim/FleshProfile.of reads
// them); null while it can't be measured yet. Measured once per look, kept by the engine.
JNIEXPORT jfloatArray JNICALL FN(actorFlesh)(JNIEnv* env, jclass, jlong h, jint id) {
    std::vector<float> v;
    if (!scene(h)->actorFlesh(id, v) || v.empty()) return nullptr;
    jfloatArray arr = env->NewFloatArray(jsize(v.size()));
    env->SetFloatArrayRegion(arr, 0, jsize(v.size()), v.data());
    return arr;
}

JNIEXPORT jboolean JNICALL FN(ghostProp)(JNIEnv*, jclass, jlong h, jint model, jfloat x, jfloat y, jfloat z, jfloat yaw, jfloat scale) {
    return scene(h)->ghostProp(uint32_t(model), V3(x, y, z), yaw, scale);
}

JNIEXPORT void JNICALL FN(ghostActor)(JNIEnv*, jclass, jlong h, jint id) { scene(h)->ghostActor(id); }

JNIEXPORT void JNICALL FN(ghostMove)(JNIEnv*, jclass, jlong h, jfloat x, jfloat y, jfloat z, jfloat yaw) {
    scene(h)->ghostMove(V3(x, y, z), yaw);
}

JNIEXPORT void JNICALL FN(ghostClear)(JNIEnv*, jclass, jlong h) { scene(h)->ghostClear(); }

JNIEXPORT jfloatArray JNICALL FN(ghostBounds)(JNIEnv* env, jclass, jlong h) {
    V3 mn, mx;
    if (!scene(h)->ghostBounds(mn, mx)) return nullptr;
    return boxArray(env, mn, mx);
}

namespace {
jintArray thumbPixels(JNIEnv* env, const std::vector<uint8_t>& rgba, int size, int height = -1) {
    const int w = size, hgt = height < 0 ? size : height;
    std::vector<jint> out(size_t(w) * hgt + 2);
    out[0] = w; out[1] = hgt;
    for (size_t i = 0; i < size_t(w) * hgt; ++i) {
        const uint8_t* p = &rgba[i * 4];
        out[i + 2] = jint(uint32_t(p[3]) << 24 | uint32_t(p[0]) << 16 | uint32_t(p[1]) << 8 | p[2]);
    }
    jintArray arr = env->NewIntArray(jsize(out.size()));
    env->SetIntArrayRegion(arr, 0, jsize(out.size()), out.data());
    return arr;
}
} // namespace

// [size, size, ARGB pixels...] of a prop's thumbnail, or null (thumbError says why).
JNIEXPORT jintArray JNICALL FN(thumbProp)(JNIEnv* env, jclass, jlong h, jint model, jint size) {
    std::vector<uint8_t> rgba;
    gThumbError.clear();
    int s = std::clamp(int(size), 16, 512);
    if (!scene(h)->thumbnail(uint32_t(model), nullptr, s, rgba, gThumbError)) return nullptr;
    return thumbPixels(env, rgba, s);
}

namespace {
// The arrays as Native.dress and Native.body take them, made a Scene::ThumbLook.
Scene::ThumbLook thumbLookOf(JNIEnv* env, jint model, jfloat scale, jintArray geosetKeys, jintArray geosetValues, jintArray layerFiles,
                             jintArray layerMeta, jintArray tintColours, jintArray extraModels, jfloatArray morph, jfloat k, jfloat fold) {
    constexpr size_t kMeta = 9, kTints = 2, kExtra = 4;
    Scene::ThumbLook look;
    look.model = uint32_t(model);
    look.scale = scale;
    auto gk = ints(env, geosetKeys), gv = ints(env, geosetValues);
    for (size_t i = 0; i < gk.size() && i < gv.size(); ++i) look.geo[gk[i]] = gv[i];
    auto files = ints(env, layerFiles), meta = ints(env, layerMeta), tints = ints(env, tintColours);
    for (size_t i = 0; i < files.size() && (i + 1) * kMeta <= meta.size(); ++i) {
        const int* m = &meta[i * kMeta];
        SkinLayer l;
        l.fileId = uint32_t(files[i]);
        l.textureType = m[0]; l.layer = m[1]; l.blend = m[2];
        l.x = m[3]; l.y = m[4]; l.w = m[5]; l.h = m[6];
        l.materialW = m[7]; l.materialH = m[8];
        if ((i + 1) * kTints <= tints.size()) { l.tint1 = uint32_t(tints[i * kTints]); l.tint2 = uint32_t(tints[i * kTints + 1]); }
        look.layers.push_back(l);
    }
    auto ex = ints(env, extraModels);
    for (size_t i = 0; i + kExtra <= ex.size(); i += kExtra) look.extras.push_back({ex[i], uint32_t(ex[i + 1]), uint32_t(ex[i + 2]), ex[i + 3]});
    look.morph = floats(env, morph);
    look.bodyK = k; look.bodyFold = fold;
    look.hd = gThumbHd; look.hdRace = gThumbHdRace; look.hdSex = gThumbHdSex;
    gThumbHd = HdOptions();
    return look;
}
} // namespace

// A look's thumbnail (the arrays as Native.dress and Native.body take them), or null (thumbError says why).
JNIEXPORT jintArray JNICALL FN(thumbLook)(JNIEnv* env, jclass, jlong h, jint model, jfloat scale, jintArray geosetKeys,
                                         jintArray geosetValues, jintArray layerFiles, jintArray layerMeta, jintArray tintColours,
                                         jintArray extraModels, jfloatArray morph, jfloat k, jfloat fold, jint size) {
    Scene::ThumbLook look = thumbLookOf(env, model, scale, geosetKeys, geosetValues, layerFiles, layerMeta, tintColours, extraModels, morph, k, fold);
    std::vector<uint8_t> rgba;
    gThumbError.clear();
    int s = std::clamp(int(size), 16, 512);
    if (!scene(h)->thumbnail(0, &look, s, rgba, gThumbError)) return nullptr;
    return thumbPixels(env, rgba, s);
}

// A look's picture framed as a Transmog card (Scene::ThumbFrame: the band lo..hi of the body, from az, el), width x height,
// clear (transparent) or on the slate: [width, height, ARGB pixels...] or null (thumbError says why).
JNIEXPORT jintArray JNICALL FN(thumbCard)(JNIEnv* env, jclass, jlong h, jint model, jfloat scale, jintArray geosetKeys,
                                         jintArray geosetValues, jintArray layerFiles, jintArray layerMeta, jintArray tintColours,
                                         jintArray extraModels, jfloatArray morph, jfloat k, jfloat fold, jint width, jint height,
                                         jfloat lo, jfloat hi, jfloat az, jfloat el, jboolean fitWidth, jboolean clear) {
    Scene::ThumbLook look = thumbLookOf(env, model, scale, geosetKeys, geosetValues, layerFiles, layerMeta, tintColours, extraModels, morph, k, fold);
    Scene::ThumbFrame frame;
    frame.lo = lo; frame.hi = hi; frame.az = az; frame.el = el;
    frame.fitWidth = fitWidth == JNI_TRUE;
    frame.clear = clear == JNI_TRUE;
    std::vector<uint8_t> rgba;
    gThumbError.clear();
    int w = std::clamp(int(width), 16, 512), ht = std::clamp(int(height), 16, 512);
    if (!scene(h)->thumbnail(0, &look, &frame, w, ht, rgba, gThumbError)) return nullptr;
    return thumbPixels(env, rgba, w, ht);
}

JNIEXPORT jstring JNICALL FN(thumbError)(JNIEnv* env, jclass, jlong) { return env->NewStringUTF(gThumbError.c_str()); }

// The HD figure (bodyparts/HdModels) on the next look picture only (thumbLook or thumbCard right after, on this thread).
JNIEXPORT void JNICALL FN(thumbHd)(JNIEnv* env, jclass, jlong, jint race, jint sex, jboolean on, jint hair, jint outfit, jint eyes,
                                   jint faceMark, jint bodyMarks, jint tint, jint hairTint, jintArray jewelry, jint body, jfloatArray shapes) {
    HdOptions o;
    o.on = on == JNI_TRUE;
    o.hair = hair; o.outfit = outfit; o.eyes = eyes; o.faceMark = faceMark; o.bodyMarks = bodyMarks;
    o.tint = tint; o.hairTint = hairTint; o.body = body;
    for (int j : ints(env, jewelry)) o.jewelry.push_back(j);
    o.shapes = floats(env, shapes);
    gThumbHd = o; gThumbHdRace = race; gThumbHdSex = sex;
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
