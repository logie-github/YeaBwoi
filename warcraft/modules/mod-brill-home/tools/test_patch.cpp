// SPDX-License-Identifier: GPL-3.0-or-later
// The Brill home's patch made for a game folder, and read back: the game's pug with its coat made a
// creature's skin (texture kind 11, the replaceable lookup saying so), the tan coat named for the game's
// pug display (31174), Corn's display (39700) with the user's coat, the cellar's looks (9700, 9701); a
// second start keeping it.
//
//   g++ -std=c++17 -O2 -I../src -I../../mod-skyborne/src test_patch.cpp ../src/BrillHomePatch.cpp
//       ../../mod-skyborne/src/SkyborneFiles.cpp -lz -o test_patch
//   ./test_patch ../data/client GAME_DATA
//
// GAME_DATA: a client's Data folder (its archives, or folders laid out as they are) with the game's
// Creature\Pug\Pug.m2 and its coat, and DBFilesClient's CreatureDisplayInfo and GameObjectDisplayInfo.

#include "BrillHomePatch.h"
#include "SkyborneFiles.h"
#include <cstring>
#include <iostream>

using Skyborne::Bytes;
using Skyborne::Dbc;
namespace fs = std::filesystem;

static int failures = 0;

static void Check(bool ok, std::string const& what)
{
    std::cout << (ok ? "ok   " : "FAIL ") << what << "\n";
    if (!ok)
        ++failures;
}

static uint32_t U32(Bytes const& b, size_t at)
{
    uint32_t v = 0;
    if (at + 4 <= b.size())
        std::memcpy(&v, &b[at], 4);
    return v;
}

int main(int argc, char** argv)
{
    if (argc != 3)
    {
        std::cerr << "usage: test_patch MODULE_CLIENT_DATA GAME_DATA\n";
        return 2;
    }
    fs::path module = argv[1], data = argv[2];
    auto log = [](std::string const& line) { std::cout << "  " << line << "\n"; };
    BrillHome::RemovePatch(data, log);
    bool corn = false;
    Check(BrillHome::UpdatePatch(module, data, log, corn), "the patch is made");
    Check(corn, "Corn is the Forsaken pug");

    Skyborne::Game game(data, {});
    Bytes m2;
    Check(game.Read("Creature\\Pug\\Pug.m2", m2), "the pug model is in the game");
    uint32_t count = U32(m2, 0x50), offset = U32(m2, 0x54);
    int skin = -1;
    for (uint32_t i = 0; i < count; ++i)
        if (U32(m2, offset + i * 16) == 11)
            skin = int(i);
    Check(skin >= 0, "its coat is a creature's skin (kind 11)");
    uint32_t lookups = U32(m2, 0x68), lookupAt = U32(m2, 0x6C);
    int16_t slot = -1;
    if (lookups > 11)
        std::memcpy(&slot, &m2[lookupAt + 22], 2);
    Check(skin >= 0 && slot == skin, "its replaceable texture lookup names the coat for kind 11");

    Bytes blp;
    Check(game.Read("Creature\\Pug\\ForsakenPug_Corn.blp", blp) && blp.size() > 4 && std::memcmp(blp.data(), "BLP2", 4) == 0,
          "Corn's coat is in the game, a BLP2");

    Bytes raw;
    Dbc dbc;
    Check(game.Read("DBFilesClient\\CreatureDisplayInfo.dbc", raw) && dbc.Parse(raw), "CreatureDisplayInfo reads");
    auto row = [&](uint32_t id) -> Bytes const* {
        for (auto const& r : dbc.records)
            if (U32(r, 0) == id)
                return &r;
        return nullptr;
    };
    auto text = [&](Bytes const* r, size_t field) {
        if (!r)
            return std::string();
        uint32_t at = U32(*r, field * 4);
        return at < dbc.strings.size() ? std::string(reinterpret_cast<char const*>(&dbc.strings[at])) : std::string();
    };
    Bytes const* pug = row(31174);
    Bytes const* cornRow = row(39700);
    Check(pug && U32(*pug, 4) == 3291 && !text(pug, 6).empty(), "the game's pug display names its tan coat (" + text(pug, 6) + ")");
    Check(cornRow && U32(*cornRow, 4) == 3291 && text(cornRow, 6) == "ForsakenPug_Corn", "Corn's display: the pug model, his coat");
    Bytes coat;
    Check(pug && game.Read("Creature\\Pug\\" + text(pug, 6) + ".blp", coat), "the tan coat is in the pug's folder");

    Dbc gos;
    Check(game.Read("DBFilesClient\\GameObjectDisplayInfo.dbc", raw) && gos.Parse(raw), "GameObjectDisplayInfo reads");
    int cellar = 0;
    for (auto const& r : gos.records)
        if (U32(r, 0) == 9700 || U32(r, 0) == 9701)
            ++cellar;
    Check(cellar == 2, "the cellar's and its ladder's looks");

    corn = false;
    Check(BrillHome::UpdatePatch(module, data, log, corn) && corn, "a second start keeps it");
    std::cout << (failures ? "FAILED: " + std::to_string(failures) : std::string("all passed")) << "\n";
    return failures ? 1 : 0;
}
