// V3 Gold: experimental fixes for Persona 4 Golden on Vita3K.
// SPDX-License-Identifier: GPL-2.0-or-later
//
// Switched on and off from the app's in-game menu (com.logie.v3gold.GoldLab through
// android/jni/gold_lab.cpp). Everything here is off unless the player turns it on.

#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace gold {

/** What the renderer did with a full-screen draw that reads back an earlier frame. */
enum class BlurAction : uint8_t {
    // Left alone (didn't match, or the fix is off).
    Kept,
    // Skipped: it blended the previous frame over the new one.
    Skipped,
    // Drawn with the new frame bound where the old one was (a shader mixing the two).
    Rebound,
};

/**
 * A full-screen draw (at most 6 vertices) that samples a render target. Persona 4 Golden makes
 * its motion blur by blending what it drew in the previous frame over the new frame; this is how
 * the renderer reports such draws to the in-game menu, so the right one can be found on a phone.
 */
struct ReadbackDraw {
    uint64_t fragment_hash = 0;
    uint16_t texture_count = 0;
    uint16_t width = 0;
    uint16_t height = 0;
    // Frames since the oldest sampled render target was drawn (0: this frame).
    uint16_t frames_old = 0;
    uint16_t source_width = 0;
    uint16_t source_height = 0;
    bool blending = false;
    uint8_t blend_src = 0;
    uint8_t blend_dst = 0;
    BlurAction action = BlurAction::Kept;
};

struct Lab {
    std::atomic<bool> blur_fix{ false };

    std::mutex mutex;
    std::vector<ReadbackDraw> current_frame;
    std::vector<ReadbackDraw> last_frame;

    static constexpr size_t MAX_REPORTED = 24;

    void record(const ReadbackDraw &draw) {
        std::lock_guard<std::mutex> guard(mutex);
        if (current_frame.size() < MAX_REPORTED)
            current_frame.push_back(draw);
    }

    /** Called when the game shows a new frame. Frames without such draws keep the last report. */
    void end_frame() {
        std::lock_guard<std::mutex> guard(mutex);
        if (!current_frame.empty()) {
            last_frame.swap(current_frame);
            current_frame.clear();
        }
    }

    std::vector<ReadbackDraw> report() {
        std::lock_guard<std::mutex> guard(mutex);
        return last_frame;
    }
};

inline Lab &lab() {
    static Lab instance;
    return instance;
}

} // namespace gold
