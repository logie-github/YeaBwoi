// SPDX-License-Identifier: GPL-3.0-or-later
// The Brill home's patch made for a game folder, and read back: Corn's model, skin and coat at the game's
// pug's paths (the creature tables untouched), the cellar's looks (9700, 9701); a second start keeping it.
//
//   g++ -std=c++17 -O2 -I../src -I../../mod-skyborne/src test_patch.cpp ../src/BrillHomePatch.cpp
//       ../../mod-skyborne/src/SkyborneFiles.cpp -lz -o test_patch
//   ./test_patch ../data/client GAME_DATA
//
// GAME_DATA: a client's Data folder (its archives, or folders laid out as they are) with DBFilesClient's
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
    Skyborne::Game below(data, { "patch-enus-v.mpq" });
    auto mine = [&](char const* file)
    {
        std::ifstream in(module / "corn" / file, std::ios::binary);
        return Bytes(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    };
    Bytes m2, skin, coat, original;
    Check(game.Read("Creature\\Pug\\Pug.m2", m2) && !m2.empty() && m2 == mine("Pug.m2"), "the pug model is Corn's");
    Check(m2.size() > 8 && std::memcmp(m2.data(), "MD20", 4) == 0 && U32(m2, 4) == 264, "an MD20 of 3.3.5a's version (264)");
    Check(game.Read("Creature\\Pug\\Pug00.skin", skin) && !skin.empty() && skin == mine("Pug00.skin"), "its skin is Corn's");
    Check(game.Read("Creature\\Pug\\ForsakenPug_Corn.blp", coat) && coat.size() > 4 && std::memcmp(coat.data(), "BLP2", 4) == 0
          && coat == mine("ForsakenPug_Corn.blp"), "the coat it names is the user's, a BLP2");
    std::string names(m2.begin(), m2.end());
    Check(names.find("Creature\\Pug\\ForsakenPug_Corn.blp") != std::string::npos, "the model names the coat");

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
