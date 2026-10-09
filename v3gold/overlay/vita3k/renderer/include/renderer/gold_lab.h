// V3 Gold: experimental fixes for Persona 4 Golden on Vita3K.
// SPDX-License-Identifier: GPL-2.0-or-later
//
// Switched on and off from the app's in-game menu (com.logie.v3gold.GoldLab through
// android/jni/gold_lab.cpp). Everything here is off unless the player turns it on.
//
// Also the screen shape, which the renderers' presentation code asks for where to put the
// game's picture.

#pragma once

#include <algorithm>
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

/** How the game's picture fills the screen (com.logie.v3gold.GoldConfig.SCREEN_SHAPE_*). */
enum class ScreenShape : int {
    // Vita3K's own placement: the game's 16:9, as big as fits (or its "stretch" setting).
    Original = 0,
    // 4:3, as big as fits, showing the middle of the picture: its sides are cut off.
    Crop43 = 1,
    // 4:3, as big as fits, with the whole picture squeezed in.
    Squeeze43 = 2,
    // The whole screen, with the whole picture stretched over it.
    Fill = 3,
};

struct Lab {
    std::atomic<bool> blur_fix{ false };
    std::atomic<int> screen_shape{ static_cast<int>(ScreenShape::Original) };

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

inline ScreenShape screen_shape() {
    return static_cast<ScreenShape>(lab().screen_shape.load(std::memory_order_relaxed));
}

struct ScreenRect {
    float x = 0.0f;
    float y = 0.0f;
    float width = 0.0f;
    float height = 0.0f;
};

/**
 * Where the game's picture goes on a screen_w x screen_h screen for [shape]. False for
 * ScreenShape::Original: Vita3K places it as usual.
 */
inline bool screen_rect(const ScreenShape shape, const float screen_w, const float screen_h, ScreenRect &rect) {
    if (shape == ScreenShape::Original || screen_w <= 0.0f || screen_h <= 0.0f)
        return false;
    if (shape == ScreenShape::Fill) {
        rect = { 0.0f, 0.0f, screen_w, screen_h };
        return true;
    }
    constexpr float aspect = 4.0f / 3.0f;
    if (screen_w / screen_h > aspect) {
        rect.height = screen_h;
        rect.width = screen_h * aspect;
    } else {
        rect.width = screen_w;
        rect.height = screen_w / aspect;
    }
    rect.x = (screen_w - rect.width) / 2.0f;
    rect.y = (screen_h - rect.height) / 2.0f;
    return true;
}

/**
 * How much of a picture_w x picture_h picture to leave out on each side, as a fraction of its
 * width: for ScreenShape::Crop43, all but its middle 4:3. 0 otherwise.
 */
inline float crop_fraction(const float picture_w, const float picture_h) {
    if (screen_shape() != ScreenShape::Crop43 || picture_w <= 0.0f)
        return 0.0f;
    const float keep = std::min(1.0f, picture_h * 4.0f / 3.0f / picture_w);
    return (1.0f - keep) / 2.0f;
}

} // namespace gold
