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
    int looks = 0, leftover = 0, darkRanger = 0, skins = 0, withExtra = 0, overlays = 0, worn = 0;
    Game hd(data, { "patch-z.mpq", "patch-y.mpq" });
    Bytes probe;
    bool hasExtra = hd.Read("Character\\BloodElf\\Female\\BloodElfFemaleSkin00_00_Extra.blp", probe);
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
        if (base == 4)
        {
            ++overlays;
            if (sections.Get(i, 4) || sections.Get(i, 5) || sections.Get(i, 6))
                ++worn;
        }
        if ((race == 13 || race == 14 || (race == 10 && color == 19)) && base == 0)
        {
            ++skins;
            uint32_t at = sections.Get(i, 5);
            std::string second = at < sections.strings.size() ? reinterpret_cast<char const*>(&sections.strings[at]) : "";
            if (!second.empty() && patch->Read(second, probe) == (second.find("Skyborne") != std::string::npos || color == 19))
                ++withExtra;
        }
    }
    // HD looks: a skin's ears, jaw and eyes are its _Extra texture; theirs too (made, or the blood elf's).
    if (hasExtra)
        Check(withExtra == skins, "every skin has its _Extra texture (" + std::to_string(withExtra) + " of " + std::to_string(skins) + ")");
    Check(looks > 2000, "the Skyborne's looks (" + std::to_string(looks) + ")");
    Check(leftover == 0, "no Naga or Broken looks left on races 13 and 14");
    Check(darkRanger == 2, "the blood elf's Dark Ranger skin, both sexes");
    Check(overlays > 0 && worn == 0, "section 4 rows carry no textures (" + std::to_string(overlays) + " rows)");

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
        { "Character\\BloodElf\\Hair00_01.blp", "Character\\Skyborne\\Hair00_16.blp" },
        { "Character\\BloodElf\\Female\\BloodElfFemaleFaceUpper00_00.blp", "Character\\BloodElf\\Female\\BloodElfFemaleFaceUpper00_19.blp" },
        { "Character\\BloodElf\\Female\\BloodElfFemaleSkin00_00_Extra.blp", "Character\\BloodElf\\Female\\BloodElfFemaleSkin00_19_Extra.blp" },
        { "Character\\BloodElf\\Female\\BloodElfFemaleSkin00_00_Extra.blp", "Character\\Skyborne\\Female\\SkyborneFemaleSkin00_20_Extra.blp" },
        { "Character\\BloodElf\\Hair00_01.blp", "Character\\Skyborne\\Hair00_19.blp" },
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

    // Textures the patch writes over the game's keep the game's format (a character skin's palette:
    // the game draws a character skin in no other).
    int sameFormat = 0, rewritten = 0;
    for (char const* race : { "Human", "Orc", "Dwarf", "NightElf", "Scourge", "Tauren", "Gnome", "Troll", "Goblin", "BloodElf", "Draenei" })
    {
        std::string name = std::string("Character\\") + race + "\\Female\\" + race + "FemaleSkin00_00.blp";
        Bytes original, made2;
        if (!game.Read(name, original) || !patch->Read(name, made2))
            continue;
        ++rewritten;
        Image a, b;
        if (std::equal(original.begin() + 8, original.begin() + 20, made2.begin() + 8) && Decode(made2, b) && Decode(original, a))
            ++sameFormat;
    }
    Check(sameFormat == rewritten, "rewritten textures keep the game's own format (" + std::to_string(sameFormat) + " of " + std::to_string(rewritten) + ")");

    std::cout << (failures ? std::to_string(failures) + " failed\n" : "all passed\n");
    return failures ? 1 : 0;
}
