// SPDX-License-Identifier: GPL-3.0-or-later
// The Skyborne's patch made for a game folder, and checked (no server needed):
//
//   g++ -std=c++17 -O2 -DSKYBORNE_BZIP2 -I../src test_client.cpp ../src/SkyborneFiles.cpp ../src/SkybornePatch.cpp -lz -lbz2
//   ./a.out MODULE_DATA GAME_DATA [OUT]
//
// MODULE_DATA: the module's data/client; GAME_DATA: a client's Data folder. Makes Data/patch-Z.MPQ,
// reads it back, checks the races, looks, spells and textures are in it, that a second run leaves it
// as it is, and with OUT writes some of its textures there (as .blp) to look at.

#include "SkyborneFiles.h"
#include "SkybornePatch.h"
#include <cstdio>
#include <iostream>

using namespace Skyborne;

static int failures = 0;

static void Check(bool ok, std::string const& what)
{
    std::cout << (ok ? "ok   " : "FAIL ") << what << "\n";
    if (!ok)
        ++failures;
}

int main(int argc, char** argv)
{
    if (argc < 3)
    {
        std::cerr << "test_client MODULE_DATA GAME_DATA [OUT]\n";
        return 2;
    }
    fs::path module = argv[1], data = argv[2];
    std::error_code error;
    fs::remove(data / PATCH, error);
    fs::remove(data / MARK, error);

    auto log = [](std::string const& line) { std::cout << "  " << line << "\n"; };
    Check(UpdatePatch(module, data, log), "the patch is made");
    auto made = fs::last_write_time(data / PATCH, error);
    Check(UpdatePatch(module, data, log), "a second start keeps it");
    Check(fs::last_write_time(data / PATCH, error) == made, "...without making it again");

    auto patch = OpenArchive(data / PATCH);
    Bytes bytes;
    Dbc races;
    Check(patch->Read("DBFilesClient\\ChrRaces.dbc", bytes) && races.Parse(bytes), "ChrRaces.dbc is in it");
    int skyborne = 0, moved = 0;
    for (size_t i = 0; i < races.records.size(); ++i)
    {
        uint32_t id = races.Get(i, 0), flags = races.Get(i, 1);
        if ((id == 13 || id == 14) && !(flags & 1))
            ++skyborne;
        if (id == 22 || id == 23)
            ++moved;
    }
    Check(skyborne == 2, "the two Skyborne races are playable");
    Check(moved == 2, "the Naga and Broken moved to 22 and 23");

    Dbc sections;
    Check(patch->Read("DBFilesClient\\CharSections.dbc", bytes) && sections.Parse(bytes), "CharSections.dbc is in it");
    int looks = 0, leftover = 0, darkRanger = 0;
    for (size_t i = 0; i < sections.records.size(); ++i)
    {
        uint32_t race = sections.Get(i, 1), base = sections.Get(i, 3), color = sections.Get(i, 9);
        if (race == 13 || race == 14)
        {
            ++looks;
            if (sections.Get(i, 0) < 900000)
                ++leftover;
        }
        if (race == 10 && base == 0 && color == 19)
            ++darkRanger;
    }
    Check(looks > 2000, "the Skyborne's looks (" + std::to_string(looks) + ")");
    Check(leftover == 0, "no Naga or Broken looks left on races 13 and 14");
    Check(darkRanger == 2, "the blood elf's Dark Ranger skin, both sexes");

    Dbc spells;
    Check(patch->Read("DBFilesClient\\Spell.dbc", bytes) && spells.Parse(bytes), "Spell.dbc is in it");
    int racials = 0;
    for (size_t i = 0; i < spells.records.size(); ++i)
        if (spells.Get(i, 0) >= 98701 && spells.Get(i, 0) <= 98707)
            ++racials;
    Check(racials == 7, "the racials (" + std::to_string(racials) + ")");

    Bytes toc;
    Check(patch->Read("Interface\\GlueXML\\GlueXML.toc", toc) && std::string(toc.begin(), toc.end()).find("SkyborneCreate.lua") != std::string::npos,
          "the character screen loads theirs");
    Check(patch->Read("Sound\\CinematicVoices\\SkyborneNarration.mp3", bytes) && bytes.size() > 100000, "the narration");

    Bytes icons;
    Image image;
    Check(patch->Read("Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-Races.blp", icons) && Decode(icons, image), "the race icons");

    // Textures: one of each kind made, decodable, the size of the original.
    Game game(data, { "patch-z.mpq", "patch-y.mpq" });
    char const* pairs[][2] = {
        { "Character\\BloodElf\\Female\\BloodElfFemaleSkin00_00.blp", "Character\\Skyborne\\Female\\SkyborneFemaleSkin00_19.blp" },
        { "Character\\BloodElf\\Female\\BloodElfFemaleFaceUpper00_00.blp", "Character\\Skyborne\\Female\\SkyborneFemaleFaceUpper00_00.blp" },
        { "Character\\BloodElf\\Female\\BloodElfFemaleFaceUpper00_00.blp", "Character\\Skyborne\\Female\\SkyborneFemaleFaceUpper10_22.blp" },
        { "Character\\BloodElf\\Female\\BloodElfFemaleNakedTorsoSkin00_00.blp", "Character\\Skyborne\\Female\\SkyborneFemaleNakedTorsoSkin00_20.blp" },
        { "Character\\BloodElf\\Hair00_01.blp", "Character\\Skyborne\\Hair00_16.blp" },
        { "Character\\BloodElf\\Female\\BloodElfFemaleFaceUpper00_00.blp", "Character\\BloodElf\\Female\\BloodElfFemaleFaceUpper00_19.blp" },
    };
    for (auto const& p : pairs)
    {
        Bytes original, made2;
        Image a, b;
        bool ok = game.Read(p[0], original) && patch->Read(p[1], made2) && Decode(original, a) && Decode(made2, b)
            && a.width == b.width && a.height == b.height && a.rgba != b.rgba;
        Check(ok, std::string("made: ") + p[1]);
        if (ok && argc > 3)
        {
            std::string name = fs::path(std::string(p[1]).substr(std::string(p[1]).find_last_of('\\') + 1)).string();
            std::ofstream(fs::path(argv[3]) / name, std::ios::binary).write(reinterpret_cast<char const*>(made2.data()), std::streamsize(made2.size()));
        }
    }
    if (argc > 3)
        std::ofstream(fs::path(argv[3]) / "UI-CharacterCreate-Races.blp", std::ios::binary).write(reinterpret_cast<char const*>(icons.data()), std::streamsize(icons.size()));

    std::cout << (failures ? std::to_string(failures) + " failed\n" : "all passed\n");
    return failures ? 1 : 0;
}
