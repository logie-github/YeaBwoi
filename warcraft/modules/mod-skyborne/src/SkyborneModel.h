// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Bottom Features (a body detail of the female models, in the style picked at creation): the model
 * changed from the game's own files when the patch is made, and the tall "extra" textures the
 * changed model draws. Nothing here needs the server, so tools/test_model.cpp builds it on its own.
 */

#ifndef MOD_SKYBORNE_MODEL_H
#define MOD_SKYBORNE_MODEL_H

#include "SkyborneFiles.h"

namespace Skyborne
{
    // The rows at the top of a tall texture's lower half (of its 1024) kept transparent, so the face's
    // lower edge and the detail don't run into each other where the game filters the texture. The
    // mesh's texture coordinates start below them (V from 0.5 + DETAIL_GUTTER / 1024).
    constexpr uint32_t DETAIL_GUTTER = 16;

    // What tells a model (data/client/bodydetail/models.txt, a mesh's "model" line): the checksums
    // (CRC32) of the model's vertices and of its first skin file. False when [m2] isn't a model of
    // the 3.3.5 format.
    bool ModelPrint(Bytes const& m2, Bytes const& skin, uint32_t& vertices, uint32_t& skinCrc);

    // [m2] and [skin] (a female model and its 00.skin, the 3.3.5 format) with the body detail: the V
    // of each vertex of the model's extra texture (type 8) halved, that texture being twice as tall,
    // and the mesh of [mesh] (the text of data/client/bodydetail/<Race>.mesh) added, a section of
    // geoset 1101 for each of the model's sections it was copied from, drawn with the extra texture
    // blended. The new vertices go at the end of the model's, so its other skin files stay as they
    // are. Says what went wrong (the files then as they were), or nothing.
    //
    // The mesh, a record a line ('#': a comment):
    //   model <vertices' CRC32, hex> <00.skin's CRC32, hex> <its vertex lines> <its indices: 3 a tri>
    //   lift <metres the copies stand off the body, along the normal>
    //   vertex <the skin's vertex (its lookup index)> <u> <v>     (in the tall texture's lower half)
    //   tri <the skin's section> <a> <b> <c>                     (the vertex lines, from 0)
    std::string AddBodyDetail(Bytes& m2, Bytes& skin, std::string const& mesh);

    // A style's art (data/client/bodydetail/<Race>_<NN>.bin: "BDET", version 1, the width and height,
    // then the RGBA bytes zlib-compressed). False when [data] isn't one.
    bool ReadDetail(Bytes const& data, Image& image);

    // The tall extra texture of a row, 512 by 1024, a palette with 8 bits of alpha: [extra] (the row's
    // 512 by 512 palette texture) as its top half, its palette and pixels at every level as they were
    // (but for the least used colours, given to the detail), and [detail] (512 by 512; none: width 0)
    // as the lower half, transparent where it is and in the gutter. False when [extra] or [detail]
    // isn't one of these.
    bool TallExtra(Bytes const& extra, Image const& detail, Bytes& out);
}

#endif
