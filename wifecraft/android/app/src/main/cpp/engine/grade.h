// The cinematic look's last pass (Scene::setCinematic): the frame is drawn into an offscreen picture of the view's size,
// then drawn onto the framebuffer that was bound with a colour grade (the area's cinematic profile, daylight.h: lift, gamma,
// gain, an S-curve and saturation, cineGradeColour's maths), a vignette, film grain and letterbox bars, each by its own
// amount. With every
// amount at 0 nothing is drawn offscreen at all (the frame goes straight to the screen, as before). OpenGL ES 3.
#pragma once

namespace wc {

struct GradeParams {
    float mood = 0;       // 0..1: the grade's strength (over 0: the grade is drawn; its numbers below are at that strength)
    float lift[3] = {0, 0, 0};    // where black goes (daylight.h CineProfile, scaled by cineScaled)
    float gamma = 1;
    float mid[3] = {1, 1, 1};     // the mid-tones by channel (the zone mood's tint: each channel's power is gamma / mid)
    float gain[3] = {1, 1, 1};    // where white goes
    float contrast = 0;           // the S-curve's share
    float saturation = 1;
    float vignette = 0;   // 0..1: how much the corners darken (1: by about 60%)
    float grain = 0;      // 0..1: film grain's strength (1: about +-4% on the mid-tones)
    float bars = 0;       // 0..0.5: each letterbox bar's height over the view's
    float seconds = 0;    // the grain's clock (it changes 24 times a second)
    bool active() const { return mood > 0.001f || vignette > 0.001f || grain > 0.001f || bars > 0.0005f; }
};

// The bars that frame a view `width` x `height` as `aspect` (2.39: scope) would: each bar's share of the height, 0 when the
// view is that wide already.
float letterboxBars(int width, int height, float aspect = 2.39f);

class Grade {
public:
    // Binds the offscreen picture (made, or remade at a new size) for the frame, remembering the framebuffer bound before.
    // False: it can't be had (the frame is then drawn straight to the screen, ungraded).
    bool begin(int width, int height);
    // The offscreen picture drawn onto the remembered framebuffer through the grade. Leaves that framebuffer bound.
    void end(int width, int height, const GradeParams& p);
    // The GL objects deleted (the context is still there), or forgotten (it is gone: releaseGl, a new context).
    void release();
    void forget();

private:
    unsigned fbo_ = 0, colour_ = 0, depth_ = 0, prog_ = 0, vao_ = 0;
    int w_ = 0, h_ = 0;
    int prevFbo_ = 0;
    bool failed_ = false;
};

} // namespace wc
