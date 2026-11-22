// Kotlin's door into the dialogue core (com.logie.wifecraft.dialogue.NativeDialogue). One process-wide Core: one model shared
// by every NPC. Every string that carries player-facing text crosses as UTF-8 bytes (JNI's own string functions use modified
// UTF-8, which mangles emoji and NUL). Compiled by CI only for the phone; the core itself runs on the desktop (engine-test).
#include <jni.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "dialogue_core.h"

namespace {

std::mutex gMutex;                       // guards gCore's life (load/unload), not generate
std::shared_ptr<wcd::Core> gCore;

std::shared_ptr<wcd::Core> core() {
    std::lock_guard<std::mutex> g(gMutex);
    return gCore;
}

std::string bytes(JNIEnv *env, jbyteArray a) {
    if (!a) return std::string();
    jsize n = env->GetArrayLength(a);
    std::string s((size_t)n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jstring ascii(JNIEnv *env, const std::string &in) {
    std::string s = in;
    for (auto &c : s) if ((unsigned char)c >= 0x80 || c == 0) c = '?';
    return env->NewStringUTF(s.c_str());
}

void installLogger() {
    wcd::Core::setLogger([](int level, const std::string &text) {
        if (level < 2) return;
        int prio = level >= 4 ? ANDROID_LOG_ERROR : level == 3 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
        __android_log_print(prio, "wcdialogue", "%s", text.c_str());
    });
}

const char *finishName(wcd::Finish f) {
    switch (f) {
        case wcd::Finish::Eos: return "eos";
        case wcd::Finish::Length: return "length";
        case wcd::Finish::Stop: return "stop";
        case wcd::Finish::Cancelled: return "cancelled";
        default: return "error";
    }
}

}  // namespace

extern "C" {

// Returns null when the model is loaded, else why not.
JNIEXPORT jstring JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeLoad(
        JNIEnv *env, jobject, jbyteArray path, jint threads, jint threadsBatch, jint ctx, jint slots, jint ubatch,
        jint kvBits, jint nice, jbyteArray stateDir) {
    installLogger();
    auto c = std::make_shared<wcd::Core>();
    wcd::LoadParams p;
    p.path = bytes(env, path);
    p.threads = threads;
    p.threadsBatch = threadsBatch;
    p.ctx = ctx;
    p.slots = slots;
    p.ubatch = ubatch;
    p.kvBits = kvBits;
    p.nice = nice;
    p.stateDir = bytes(env, stateDir);
    std::string err;
    if (!c->load(p, err)) return ascii(env, err.empty() ? "the model did not load" : err);
    std::lock_guard<std::mutex> g(gMutex);
    gCore = c;
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeUnload(JNIEnv *, jobject) {
    std::shared_ptr<wcd::Core> c;
    {
        std::lock_guard<std::mutex> g(gMutex);
        c = gCore;
        gCore.reset();
    }
    if (c) c->unload();
}

// "finish \t promptTokens \t reused \t shared \t restored \t generated \t prefillMs \t decodeMs \t error"
JNIEXPORT jstring JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeGenerate(
        JNIEnv *env, jobject, jlong ticket, jbyteArray key, jbyteArray prompt, jint maxTokens, jfloat temperature, jfloat topP,
        jfloat minP, jint topK, jfloat repeatPenalty, jint seed, jint minShare, jint chunkDelayMs, jobjectArray stops, jobject sink) {
    auto c = core();
    if (!c) return ascii(env, "error\t0\t0\t0\t0\t0\t0\t0\tno model is loaded");
    wcd::GenParams gp;
    gp.maxTokens = maxTokens;
    gp.temperature = temperature;
    gp.topP = topP;
    gp.minP = minP;
    gp.topK = topK;
    gp.repeatPenalty = repeatPenalty;
    gp.seed = (uint32_t)seed;
    gp.minShare = minShare;
    gp.chunkDelayMs = chunkDelayMs;
    if (stops) {
        jsize n = env->GetArrayLength(stops);
        for (jsize i = 0; i < n; i++) {
            jbyteArray b = (jbyteArray)env->GetObjectArrayElement(stops, i);
            std::string s = bytes(env, b);
            env->DeleteLocalRef(b);
            if (!s.empty()) gp.stop.push_back(s);
        }
    }
    jclass sc = env->GetObjectClass(sink);
    jmethodID onPiece = env->GetMethodID(sc, "onPiece", "([B)Z");
    wcd::TokenSink fn = [&](const std::string &piece) -> bool {
        jbyteArray arr = env->NewByteArray((jsize)piece.size());
        if (!arr) return false;
        env->SetByteArrayRegion(arr, 0, (jsize)piece.size(), reinterpret_cast<const jbyte *>(piece.data()));
        jboolean go = env->CallBooleanMethod(sink, onPiece, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return go == JNI_TRUE;
    };
    wcd::GenResult r = c->generate((uint64_t)ticket, bytes(env, key), bytes(env, prompt), gp, fn);
    std::string err = r.error;
    for (auto &ch : err) if (ch == '\t' || ch == '\n') ch = ' ';
    char head[256];
    snprintf(head, sizeof head, "%s\t%d\t%d\t%d\t%d\t%d\t%.1f\t%.1f\t", finishName(r.finish), r.promptTokens, r.reusedTokens,
             r.sharedTokens, r.restoredTokens, r.generated, r.prefillMs, r.decodeMs);
    return ascii(env, std::string(head) + err);
}

JNIEXPORT void JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeCancel(JNIEnv *, jobject, jlong ticket) {
    if (auto c = core()) c->cancel((uint64_t)ticket);
}

JNIEXPORT void JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeReset(JNIEnv *env, jobject, jbyteArray key) {
    if (auto c = core()) c->reset(bytes(env, key));
}

JNIEXPORT void JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeResetAll(JNIEnv *, jobject) {
    if (auto c = core()) c->resetAll();
}

JNIEXPORT jint JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeFlush(JNIEnv *, jobject) {
    auto c = core();
    return c ? c->flush() : 0;
}

// "description \t sizeBytes \t params \t trainCtx \t ctx \t slots \t liveSlots \t liveTokens \t rss"
JNIEXPORT jstring JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeInfo(JNIEnv *env, jobject) {
    auto c = core();
    if (!c) return nullptr;
    wcd::ModelInfo i = c->info();
    char buf[512];
    snprintf(buf, sizeof buf, "%s\t%llu\t%llu\t%d\t%d\t%d\t%d\t%d\t%llu", i.desc.c_str(), (unsigned long long)i.sizeBytes,
             (unsigned long long)i.params, i.trainCtx, i.ctx, i.slots, c->liveSlots(), c->liveTokens(),
             (unsigned long long)wcd::Core::rssBytes());
    return ascii(env, buf);
}

JNIEXPORT jint JNICALL Java_com_logie_wifecraft_dialogue_NativeDialogue_nativeCountTokens(JNIEnv *env, jobject, jbyteArray text) {
    auto c = core();
    return c ? (jint)c->tokenize(bytes(env, text)).size() : -1;
}

}  // extern "C"
