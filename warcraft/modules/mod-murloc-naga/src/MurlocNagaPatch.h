// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Murlocs' and the Naga's patch for the game (mod-murloc-naga): Data/<locale>/patch-<locale>-T.MPQ.
 */

#ifndef MOD_MURLOC_NAGA_PATCH_H
#define MOD_MURLOC_NAGA_PATCH_H

#include "../../mod-skyborne/src/SkyborneFiles.h"
#include <filesystem>
#include <functional>
#include <string>

namespace MurlocNaga
{
    namespace fs = std::filesystem;
    using Skyborne::Bytes;

    using Log = std::function<void(std::string const&)>;

    // Makes the patch in the client's Data folder [clientData] from the module's files
    // ([moduleData]: data/client) and the client's own (made again only when either changed).
    bool UpdatePatch(fs::path const& moduleData, fs::path const& clientData, Log const& log);
    // Takes the patch out of the game (the module's switch off).
    void RemovePatch(fs::path const& clientData, Log const& log);
    // Keeps the patch beside the game, out of it, for a realm without the races (Wrath Remix's).
    void SetPatchAside(fs::path const& clientData, Log const& log);
    // The intros' cameras in the server's data folder (its Cameras), where the world server reads
    // them to follow the camera.
    void PutCameras(fs::path const& moduleData, fs::path const& serverData, Log const& log);

    // The pieces, for the test (tools/test_patch.cpp).
    // A creature's model made a character's: its body texture (the first, a creature's skin) the
    // character's composed texture, and its texture coordinates moved into that texture's face
    // region (FaceUpper over FaceLower: x 0 to 1/2, y 5/8 to 1), which the game draws from the
    // face textures alone, so armour never shows on it. False if [m2] isn't a model this knows.
    bool CharacterModel(Bytes& m2);
    // A murloc skin (any size) made its face textures for a composed texture [skinWidth] wide (the
    // skin it's drawn over: 512 in the HD looks): squeezed into the face region (half the width, three
    // eighths of the height), its top third FaceUpper, the rest FaceLower, palettized and opaque, as the
    // game's own character textures are (the game composes only those).
    bool FaceTextures(Bytes const& skin, uint32_t skinWidth, Bytes& upper, Bytes& lower);
    // [blp] recoloured: every colour's hue made [hue] (0 to 1 around the colour wheel; below 0, kept),
    // its saturation and lightness multiplied (a palette's colours; any other texture's pixels, then
    // palettized).
    bool Recolour(Bytes& blp, double hue, double saturation, double lightness);
    // [image] as a palettized BLP2 (256 colours by median cut, every mip level; alpha 8 bits when
    // [alpha]), the format of the game's own character textures.
    Bytes EncodePalette(Skyborne::Image const& image, bool alpha);
    // The narration: the game's sounds ([parts]: when, the sound's WAV), mixed into one WAV, 22050
    // Hz, mono, 16 bits.
    Bytes MixNarration(std::vector<std::pair<uint32_t, Bytes>> const& parts);
}

#endif
