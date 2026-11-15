// The character's skin, composed as the game composes it: the layers a look's choices give
// (ChrCustomizationMaterial -> ChrModelTextureLayer), each texture stretched over its section of the
// model's texture (CharComponentTextureSections, or the whole ChrModelMaterial for a mask of -1),
// in layer order, with the layer's blend mode (ChrModelTextureLayer.BlendMode) and tints, drawn on
// the GPU into one texture per texture type (1 the skin, 6 hair, 19 and 10 the eyes'...).
#pragma once

#include <cstdint>
#include <functional>
#include <vector>

namespace wc {

struct SkinLayer {
    uint32_t fileId = 0;
    int textureType = 1;      // the model's texture slot it composes into (ChrModelTextureLayer.TextureType)
    int layer = 0;            // ChrModelTextureLayer.Layer: lower first
    int blend = 1;            // ChrModelTextureLayer.BlendMode: 0 none, 1 blit, 2 blit alpha mask, 3 add,
                              // 4 multiply, 5 mod2x, 6 overlay, 7 screen, 8 hard light, 9 alpha straight,
                              // 15 infer alpha blend (others: alpha blend)
    int x = 0, y = 0, w = 0, h = 0;          // its section in the material's pixels (w or h 0: the whole material)
    int materialW = 0, materialH = 0;        // ChrModelMaterial.Width/Height of its texture type
    uint32_t tint1 = 0, tint2 = 0;           // 0xAARRGGBB; alpha 0: none. tint1 alone multiplies the layer's
                                             // colour; with tint2 the layer's colour maps tint2 (dark) to tint1 (light)
};

// The layers of one texture type that a single game texture shows as it is (one blit layer over the
// whole material, untinted): bound directly, at the file's own size, with nothing composed.
bool directLayer(const std::vector<SkinLayer>& layers);

class SkinCompositor {
public:
    bool initGl();
    void releaseGl();

    // Composes `layers` (one texture type, any order: sorted here by layer) into a new texture of
    // the material's size (no side bigger than `maxSize`), mipmapped. `texture(fileId)` gives each
    // layer's GL texture (0 skips the layer). Restores the framebuffer and viewport it found.
    // Returns the texture, or 0 when there is nothing to compose. The caller deletes it.
    unsigned compose(const std::vector<SkinLayer>& layers, const std::function<unsigned(uint32_t)>& texture, int maxSize);

private:
    unsigned prog_ = 0, vao_ = 0, vbo_ = 0, fbo_ = 0;
    bool ready_ = false;
};

} // namespace wc
