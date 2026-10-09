// V3 Gold: the in-game menu's switches for Persona 4 Golden (screen shape, motion blur, 60 FPS).
// SPDX-License-Identifier: GPL-2.0-or-later

#include "android_state.h"

#include <display/state.h>
#include <io/state.h>
#include <kernel/state.h>
#include <mem/functions.h>
#include <renderer/gold_lab.h>

#include <jni.h>

#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

namespace {

// Vulkan's VkBlendFactor values, as short names for the report.
const char *blend_factor_name(const uint8_t factor) {
    switch (factor) {
    case 0: return "0";
    case 1: return "1";
    case 2: return "src";
    case 3: return "1-src";
    case 4: return "dst";
    case 5: return "1-dst";
    case 6: return "srcA";
    case 7: return "1-srcA";
    case 8: return "dstA";
    case 9: return "1-dstA";
    default: return "?";
    }
}

const char *action_name(const gold::BlurAction action) {
    switch (action) {
    case gold::BlurAction::Skipped: return "SKIPPED";
    case gold::BlurAction::Rebound: return "REBOUND";
    default: return "kept";
    }
}

// The game's own executable (eboot.bin), the first module loaded otherwise.
const KernelModule *main_module(EmuEnvState &emuenv) {
    const KernelModule *first = nullptr;
    for (const auto &[uid, module] : emuenv.kernel.loaded_modules) {
        if (!module)
            continue;
        if (std::string(module->info.path).find("eboot.bin") != std::string::npos)
            return module.get();
        if (first == nullptr)
            first = module.get();
    }
    return first;
}

EmuEnvState *running_emuenv() {
    auto *emuenv = get_emuenv();
    auto *controller = get_app_session_controller();
    if (!emuenv || !controller || !controller->is_running())
        return nullptr;
    return emuenv;
}

jobjectArray to_java(JNIEnv *env, const std::vector<std::string> &lines) {
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(lines.size()), string_class, nullptr);
    for (size_t i = 0; i < lines.size(); i++) {
        jstring line = env->NewStringUTF(lines[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), line);
        env->DeleteLocalRef(line);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_logie_v3gold_GoldNative_setBlurFix(JNIEnv *, jclass, jboolean enabled) {
    gold::lab().blur_fix.store(enabled != JNI_FALSE);
}

// One line per full-screen draw that read back a render target in the last frame shown.
JNIEXPORT jobjectArray JNICALL
Java_com_logie_v3gold_GoldNative_blurReport(JNIEnv *env, jclass) {
    std::vector<std::string> lines;
    for (const gold::ReadbackDraw &draw : gold::lab().report()) {
        char line[192];
        std::snprintf(line, sizeof(line), "%s · %ux%u from %ux%u · %u tex · %u frame(s) old · %s · #%08x",
            action_name(draw.action), draw.width, draw.height, draw.source_width, draw.source_height,
            draw.texture_count, draw.frames_old,
            draw.blending ? (std::string("blend ") + blend_factor_name(draw.blend_src) + ", " + blend_factor_name(draw.blend_dst)).c_str() : "no blend",
            static_cast<uint32_t>(draw.fragment_hash >> 32));
        lines.emplace_back(line);
    }
    return to_java(env, lines);
}

// How the game's picture fills the screen (gold::ScreenShape).
JNIEXPORT void JNICALL
Java_com_logie_v3gold_GoldNative_setScreenShape(JNIEnv *, jclass, jint shape) {
    const int valid = shape >= static_cast<int>(gold::ScreenShape::Original) && shape <= static_cast<int>(gold::ScreenShape::Fill)
        ? shape
        : static_cast<int>(gold::ScreenShape::Original);
    gold::lab().screen_shape.store(valid);
}

// Makes the game's "wait for two screen refreshes" calls wait for one: 30 FPS games can run at 60.
JNIEXPORT void JNICALL
Java_com_logie_v3gold_GoldNative_setFpsHack(JNIEnv *, jclass, jboolean enabled) {
    if (auto *emuenv = get_emuenv())
        emuenv->display.fps_hack = enabled != JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_logie_v3gold_GoldNative_fps(JNIEnv *, jclass) {
    auto *emuenv = running_emuenv();
    return emuenv ? static_cast<jint>(emuenv->fps) : 0;
}

// Every aligned 32-bit word equal to value in the game's executable, as
// [segment, offset, address, permissions] quadruples.
JNIEXPORT jintArray JNICALL
Java_com_logie_v3gold_GoldNative_findWords(JNIEnv *env, jclass, jint value) {
    std::vector<jint> found;
    if (auto *emuenv = running_emuenv()) {
        std::lock_guard<std::mutex> guard(emuenv->kernel.mutex);
        if (const KernelModule *module = main_module(*emuenv)) {
            for (int segment = 0; segment < static_cast<int>(MODULE_INFO_NUM_SEGMENTS); segment++) {
                const SceKernelSegmentInfo &info = module->info.segments[segment];
                const Address start = info.vaddr.address();
                const uint32_t size = info.memsz & ~3u;
                if (start == 0 || size == 0 || !is_valid_addr_range(emuenv->mem, start, start + size))
                    continue;
                const uint32_t *words = Ptr<const uint32_t>(start).get(emuenv->mem);
                for (uint32_t i = 0; i < size / 4 && found.size() < 4 * 512; i++) {
                    if (words[i] == static_cast<uint32_t>(value)) {
                        found.push_back(segment);
                        found.push_back(static_cast<jint>(i * 4));
                        found.push_back(static_cast<jint>(start + i * 4));
                        found.push_back(static_cast<jint>(info.perms));
                    }
                }
            }
        }
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(found.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(found.size()), found.data());
    return result;
}

JNIEXPORT jint JNICALL
Java_com_logie_v3gold_GoldNative_read32(JNIEnv *, jclass, jint address) {
    auto *emuenv = running_emuenv();
    const Address addr = static_cast<Address>(address);
    if (!emuenv || (addr & 3) != 0 || !is_valid_addr_range(emuenv->mem, addr, addr + 4))
        return 0;
    return static_cast<jint>(*Ptr<const uint32_t>(addr).get(emuenv->mem));
}

JNIEXPORT jboolean JNICALL
Java_com_logie_v3gold_GoldNative_write32(JNIEnv *, jclass, jint address, jint value) {
    auto *emuenv = running_emuenv();
    const Address addr = static_cast<Address>(address);
    if (!emuenv || (addr & 3) != 0 || !is_valid_addr_range(emuenv->mem, addr, addr + 4))
        return JNI_FALSE;
    *Ptr<uint32_t>(addr).get(emuenv->mem) = static_cast<uint32_t>(value);
    return JNI_TRUE;
}

// Where Vita3K looks for patch files (patch/<title id>*.txt), applied when the game starts.
JNIEXPORT jstring JNICALL
Java_com_logie_v3gold_GoldNative_patchPath(JNIEnv *env, jclass) {
    auto *emuenv = get_emuenv();
    return env->NewStringUTF(emuenv ? emuenv->patch_path.string().c_str() : "");
}

JNIEXPORT jstring JNICALL
Java_com_logie_v3gold_GoldNative_titleId(JNIEnv *env, jclass) {
    auto *emuenv = running_emuenv();
    return env->NewStringUTF(emuenv ? emuenv->io.title_id.c_str() : "");
}

} // extern "C"
