// The toy interaction's door from Kotlin (engine/ToyNative.kt): Scene::setToy, toyInfo and the reactions' pose (engine/orificedraw.h, toyrig.h).
#include <jni.h>

#include "engine/scene.h"

using wc::Scene;

#define TOY_FN(name) Java_com_logie_wifecraft_engine_ToyNative_##name

extern "C" {

// mode 0 puts the toy away; the floats: finger x, y, view width, height, depth (Sims metres), vibration 0..1, roll (radians).
JNIEXPORT void JNICALL TOY_FN(setToy)(JNIEnv*, jclass, jlong h, jint actor, jlong guid, jboolean doubleEnded, jint mode, jfloat fx, jfloat fy, jint w,
                                      jint ht, jfloat depth, jfloat vibration, jfloat roll, jboolean hidden) {
    wc::ToyInput in;
    in.mode = mode; in.fx = fx; in.fy = fy; in.width = w; in.height = ht; in.depth = depth; in.vibration = vibration; in.roll = roll; in.hidden = hidden;
    reinterpret_cast<Scene*>(h)->setToy(actor, uint64_t(guid), doubleEnded, in);
}

// out (at least 12 floats): ready (0/1), hd (0/1), entry x, y, way-in x, y, pixels per metre, insertable length (m), radius (m), wet, depth (m), tip x, tip y.
JNIEXPORT jboolean JNICALL TOY_FN(toyInfo)(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    wc::ToyInfo i;
    const bool ok = reinterpret_cast<Scene*>(h)->toyInfo(i);
    if (out && env->GetArrayLength(out) >= 14) {
        const float v[14] = {ok ? 1.0f : 0.0f, i.hd ? 1.0f : 0.0f, i.entryX, i.entryY, i.axisX, i.axisY, i.pxPerMetre, i.insertLength, i.radius, i.wet, i.depth, i.tipX, i.tipY, 0.0f};
        env->SetFloatArrayRegion(out, 0, 14, v);
    }
    return ok;
}

// The reactions' pose (toyrig.h's Pose order, 12 floats); a null array takes the layer off.
JNIEXPORT void JNICALL TOY_FN(setPose)(JNIEnv* env, jclass, jlong h, jint actor, jfloatArray values) {
    Scene* s = reinterpret_cast<Scene*>(h);
    if (!values || env->GetArrayLength(values) < 12) { s->clearToyPose(actor); return; }
    float v[12];
    env->GetFloatArrayRegion(values, 0, 12, v);
    s->setToyPose(actor, v, 12);
}

}
