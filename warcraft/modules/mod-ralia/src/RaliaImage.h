// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Pictures for Ralia's mod's patch (RaliaPatch.cpp): today's character textures made the size the
 * game's are, a skin's changes made again on another skin colour, and the palettized BLP2 textures
 * 3.3.5 composes characters from. The decoding and the archives are the Skyborne's (SkyborneFiles).
 * Nothing here needs the server, so the module's test (tools/test_patch.cpp) builds it on its own.
 */

#ifndef MOD_RALIA_IMAGE_H
#define MOD_RALIA_IMAGE_H

#include "../../mod-skyborne/src/SkyborneFiles.h"

#include <cstdint>

namespace Ralia
{
    using Skyborne::Bytes;
    using Skyborne::Image;

    // [image]'s pixels from (x, y), [width] by [height].
    Image Crop(Image const& image, uint32_t x, uint32_t y, uint32_t width, uint32_t height);

    // [image] at [width] by [height]: each pixel the average of the ones it covers (smaller), or
    // read between the four nearest (larger).
    Image Resize(Image const& image, uint32_t width, uint32_t height);

    // The body of a character texture of today: the left half of one twice as wide as it is high
    // (the body as classic's layout has it, the head in the right half), the whole of any other.
    Image Body(Image const& image);

    // The changes [after] makes to [before] (the same skin, as the game has it), made to [target]
    // (another skin colour, or the same with a tattoo): where the two differ, [target]'s colours
    // scaled as [after]'s are from [before]'s; elsewhere [target] as it is. The three are the same
    // size. [changed]: the share of the pixels changed (most of the picture when [after] isn't
    // [before] changed, but another picture).
    Image Repaint(Image const& target, Image const& before, Image const& after, double& changed);

    // How far apart two pictures' colours are (their 16 x 16 thumbnails'), to pick the nearest.
    double Distance(Image const& a, Image const& b);

    // A palettized BLP2 of [image]: 256 colours (median cut), its alpha (8 bits) when [alpha], and
    // its smaller copies down to 1 x 1.
    Bytes EncodePalette(Image const& image, bool alpha);

    // [blp] decoded (its full size), its smaller copies' entries that point past its end left out
    // first: today's files made by some tools have one (the last, at the file's end, of no size).
    bool DecodeFull(Bytes blp, Image& image);

    // A BLP2's size and the bits of alpha it keeps; false when [blp] isn't one.
    bool Header(Bytes const& blp, uint32_t& width, uint32_t& height, uint8_t& alphaDepth);
}

#endif
