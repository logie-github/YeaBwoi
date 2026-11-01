// SPDX-License-Identifier: GPL-3.0-or-later
// The Brill home's patch made for a game folder, and read back: the user's coat where the game's pug's
// own is (the pug's model and the tables untouched), the cellar's looks (9700, 9701); a second start
// keeping it.
//
//   g++ -std=c++17 -O2 -I../src -I../../mod-skyborne/src test_patch.cpp ../src/BrillHomePatch.cpp
//       ../../mod-skyborne/src/SkyborneFiles.cpp -lz -o test_patch
//   ./test_patch ../data/client GAME_DATA
//
// GAME_DATA: a client's Data folder (its archives, or folders laid out as they are) with the game's
// Creature\Pug\Pug.m2 (its coat Creature\Pug\Pug_Tan.blp, as the stand-in names it), and DBFilesClient's
// CreatureDisplayInfo and GameObjectDisplayInfo.

#include "BrillHomePatch.h"
#include "SkyborneFiles.h"
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>

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
    Bytes m2, original;
    Check(game.Read("Creature\\Pug\\Pug.m2", m2), "the pug model is in the game");
    Skyborne::Game below(data, { "patch-enus-v.mpq" });
    Check(below.Read("Creature\\Pug\\Pug.m2", original) && original == m2, "the pug model is the game's own, untouched");
    Bytes coat, mine;
    Check(game.Read("Creature\\Pug\\Pug_Tan.blp", coat) && coat.size() > 4 && std::memcmp(coat.data(), "BLP2", 4) == 0,
          "the pug's coat is in the game, a BLP2");
    std::ifstream in(module / "corn" / "ForsakenPug_Corn.blp", std::ios::binary);
    mine.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    Check(!mine.empty() && coat == mine, "the pug's coat is Corn's (the user's texture)");

    Bytes raw;
    Dbc cdi;
    Check(game.Read("DBFilesClient\\CreatureDisplayInfo.dbc", raw) && cdi.Parse(raw) && below.Read("DBFilesClient\\CreatureDisplayInfo.dbc", original)
          && raw == original, "no creature display table changed");

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
