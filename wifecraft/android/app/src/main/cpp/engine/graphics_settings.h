// Options' Graphics page as the renderer takes it (graphics/GraphicsSettings.kt, Native.graphics): one float array in a fixed
// order (GraphicsSettings::Index; the Kotlin side writes the same indices), clamped here. Every effect at its default is off:
// the frame is then drawn exactly as before (no offscreen chain, no extra pass). The governor's lowered steps are already
// applied by the Kotlin side when the array arrives (graphics/Governor.kt): the engine only draws what it is given.
#pragma once

#include <algorithm>
#include <cmath>

namespace wc {

struct GraphicsSettings {
    // The array's order (Kotlin: GraphicsSettings.toNative). Never reorder: append.
    enum Index {
        kRenderScale = 0,   // 0.6..1.25 (1: the view's own size; above 1: supersampled)
        kMsaa,              // 0, 2 or 4 samples on the scene's target
        kAnisotropy,        // 1, 2, 4, 8 or 16 (texture filtering at grazing angles)
        kTextureDetail,     // 1, 2 or 4: the biggest texture kept for each kind of thing, times this (a larger mip of the file)
        kTonemap,           // 0 off (clamped as before), 1 filmic highlight shoulder, 2 ACES fitted (mid-grey kept)
        kExposure,          // -2..2 EV
        kBloom,             // 0..1
        kDof,               // 0..1 depth of field's strength
        kAperture,          // 0..1 how shallow the focus is
        kDofInEditor,       // 0/1: depth of field also in the creator
        kAo,                // 0..1 ambient occlusion's strength
        kAoQuality,         // 0 low (6 samples), 1 medium (10), 2 high (16)
        kContact,           // 0..1 contact shadows (screen-space, toward the key light)
        kShadows,           // 0..1 the key light's shadow map's strength
        kShadowQuality,     // 0: 1024 px, hard-ish PCF; 1: 2048 px, soft (PCSS-lite)
        kSkin,              // 0..1 skin shading: wrap light, warm scatter at the terminator, sheen with a Fresnel rim
        kHair,              // 0..1 hair's anisotropic highlight (Kajiya-Kay)
        kShafts,            // 0..1 light shafts (radial blur of the bright sky and windows)
        kHaze,              // 0..1 a little depth haze tinted by the area's fog
        kVignette,          // 0..1 (skipped while cinematic mode's grade draws its own)
        kGrain,             // 0..1 (likewise)
        kChroma,            // 0..1 chromatic aberration at the edges
        kSharpen,           // 0..1 contrast-adaptive sharpening (after the render scale's resample)
        kTimers,            // 0/1: GPU timer queries per pass (the overlay, the governor)
        kEyes,              // 0..1 eye highlights: a glint and a wet sheen on the eyes' own mesh (texture type 19)
        kThinSkin,          // 0..1 thin-skin tint: ears, nose, lips, fingers lit through, warm (skinShade)
        kCount
    };

    float renderScale = 1;
    int msaa = 0;
    int anisotropy = 1;
    float textureDetail = 1;
    int tonemap = 0;
    float exposure = 0;
    float bloom = 0, dof = 0, aperture = 0.5f;
    bool dofInEditor = false;
    float ao = 0;
    int aoQuality = 1;
    float contact = 0, shadows = 0;
    int shadowQuality = 1;
    float skin = 0, hair = 0, shafts = 0, haze = 0;
    float vignette = 0, grain = 0, chroma = 0, sharpen = 0;
    bool timers = false;
    float eyes = 0, thinSkin = 0;

    static float unit(float v) { return std::isfinite(v) ? std::clamp(v, 0.0f, 1.0f) : 0.0f; }

    // From the array (shorter: the rest keep their defaults; a NaN or out-of-range value is clamped).
    void read(const float* v, int n) {
        *this = GraphicsSettings();
        auto at = [&](int i, float def) { return i < n && std::isfinite(v[i]) ? v[i] : def; };
        renderScale = std::clamp(at(kRenderScale, 1), 0.5f, 1.5f);
        int s = int(std::lround(at(kMsaa, 0)));
        msaa = s >= 4 ? 4 : s >= 2 ? 2 : 0;
        int a = int(std::lround(at(kAnisotropy, 1)));
        anisotropy = a >= 16 ? 16 : a >= 8 ? 8 : a >= 4 ? 4 : a >= 2 ? 2 : 1;
        const float td = at(kTextureDetail, 1);
        textureDetail = td >= 3.0f ? 4.0f : td >= 1.5f ? 2.0f : 1.0f;
        tonemap = std::clamp(int(std::lround(at(kTonemap, 0))), 0, 2);
        exposure = std::clamp(at(kExposure, 0), -2.0f, 2.0f);
        bloom = unit(at(kBloom, 0));
        dof = unit(at(kDof, 0));
        aperture = unit(at(kAperture, 0.5f));
        dofInEditor = at(kDofInEditor, 0) > 0.5f;
        ao = unit(at(kAo, 0));
        aoQuality = std::clamp(int(std::lround(at(kAoQuality, 1))), 0, 2);
        contact = unit(at(kContact, 0));
        shadows = unit(at(kShadows, 0));
        shadowQuality = std::clamp(int(std::lround(at(kShadowQuality, 1))), 0, 1);
        skin = unit(at(kSkin, 0));
        hair = unit(at(kHair, 0));
        shafts = unit(at(kShafts, 0));
        haze = unit(at(kHaze, 0));
        vignette = unit(at(kVignette, 0));
        grain = unit(at(kGrain, 0));
        chroma = unit(at(kChroma, 0));
        sharpen = unit(at(kSharpen, 0));
        timers = at(kTimers, 0) > 0.5f;
        eyes = unit(at(kEyes, 0));
        thinSkin = unit(at(kThinSkin, 0));
    }

    // Whether the frame goes through the offscreen chain (postfx.h) at all. False: drawn straight to the screen as before.
    bool post() const {
        return std::fabs(renderScale - 1.0f) > 0.01f || msaa > 0 || tonemap > 0 || std::fabs(exposure) > 0.01f || bloom > 0 ||
               dof > 0 || ao > 0 || contact > 0 || shadows > 0 || shafts > 0 || haze > 0 || vignette > 0 || grain > 0 ||
               chroma > 0 || sharpen > 0;
    }
    // The lighting pass (AO, shadows, contact shadows, haze) is needed.
    bool lighting() const { return ao > 0 || contact > 0 || shadows > 0 || haze > 0; }

    // The biggest texture kept for a kind of thing (scene.cpp's kCityTex, kActorTex...) at this texture detail.
    // 4: four times (the file's own top mip for nearly everything: the game's textures are at most 2048 or 4096 across).
    int textureCap(int maxSize) const { return textureDetail > 3.0f ? maxSize * 4 : textureDetail > 1.5f ? maxSize * 2 : maxSize; }
};

// GL_EXT_texture_filter_anisotropic on the bound GL_TEXTURE_2D (`level` 1: off), within what the GPU allows. No extension:
// nothing. postfx.cpp.
void applyAnisotropy(int level);

} // namespace wc
