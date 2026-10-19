// SPDX-License-Identifier: GPL-3.0-or-later
// The races' patch made for a game folder, and checked (no server needed):
//
//   g++ -std=c++17 -O2 -DSKYBORNE_BZIP2 -I../src -I../../mod-skyborne/src test_patch.cpp \
//       ../../mod-skyborne/src/SkyborneFiles.cpp ../src/AlliedRacesPatch.cpp -lz -lbz2
//   ./a.out MODULE_DATA FILES GAME_DATA
//
// MODULE_DATA: the module's data/client; FILES: the races' files (tools/package.py's output, with
// alliedraces.files); GAME_DATA: a client's Data folder. Makes Data/patch-V.MPQ, reads it back,
// checks the races, their looks, models, voices, spells and the character screen are in it, and
// that a second run leaves it as it is.

#include "AlliedRacesPatch.h"
#include "SkyborneFiles.h"
#include <cstdio>
#include <fstream>
#include <iostream>
#include <set>
#include <sstream>

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
    if (argc < 4)
    {
        std::cerr << "test_patch MODULE_DATA FILES GAME_DATA\n";
        return 2;
    }
    fs::path module = argv[1], files = argv[2], data = argv[3];
    std::error_code error;
    fs::remove(data / "patch-V.MPQ", error);
    fs::remove(data / "patch-V.MPQ.alliedraces", error);

    auto log = [](std::string const& line) { std::cout << "  " << line << "\n"; };
    Check(AlliedRaces::UpdatePatch(module, files, data, log), "the patch is made");
    auto made = fs::last_write_time(data / "patch-V.MPQ", error);
    Check(AlliedRaces::UpdatePatch(module, files, data, log), "a second start keeps it");
    Check(fs::last_write_time(data / "patch-V.MPQ", error) == made, "...without making it again");

    auto patch = OpenArchive(data / "patch-V.MPQ");
    Bytes bytes;
    Dbc races;
    Check(patch->Read("DBFilesClient\\ChrRaces.dbc", bytes) && races.Parse(bytes), "ChrRaces.dbc is in it");
    int playable = 0, moved = 0;
    for (size_t i = 0; i < races.records.size(); ++i)
    {
        uint32_t id = races.Get(i, 0), flags = races.Get(i, 1);
        if ((id == 15 || id == 16) && !(flags & 1))
            ++playable;
        if (id == 24 || id == 25)
            ++moved;
    }
    Check(playable == 2, "the void elves and the nightborne are playable");
    Check(moved == 2, "the Skeleton and the Vrykul moved to 24 and 25");

    Dbc sections;
    Check(patch->Read("DBFilesClient\\CharSections.dbc", bytes) && sections.Parse(bytes), "CharSections.dbc is in it");
    std::set<uint32_t> faces[2], skins[2], hair[2];
    size_t missing = 0, checked = 0;
    for (size_t i = 0; i < sections.records.size(); ++i)
    {
        uint32_t race = sections.Get(i, 1), sex = sections.Get(i, 2), kind = sections.Get(i, 3);
        if (race != 15 && race != 16)
            continue;
        int r = race == 15 ? 0 : 1;
        if (kind == 0)
            skins[r].insert(sections.Get(i, 9));
        if (kind == 1)
            faces[r].insert(sections.Get(i, 8));
        if (kind == 3)
            hair[r].insert(sections.Get(i, 8));
        // every texture a row names is in the patch
        if (sex == 1 && (checked++ % 97) == 0)
            for (uint32_t field : { 4u, 5u })
            {
                uint32_t at = sections.Get(i, field);
                std::string path = at < sections.strings.size() ? reinterpret_cast<char const*>(&sections.strings[at]) : "";
                Bytes texture;
                if (!path.empty() && !patch->Read(path, texture))
                    ++missing;
            }
    }
    Check(skins[0].size() == 30 && skins[1].size() == 70, "the skins: 30 void elf, 70 nightborne (" + std::to_string(skins[0].size()) + ", " + std::to_string(skins[1].size()) + ")");
    Check(faces[0].size() == 252 && faces[1].size() == 72, "the faces: 252 void elf (28 x 9 eyes), 72 nightborne (12 x 6) (" + std::to_string(faces[0].size()) + ", " + std::to_string(faces[1].size()) + ")");
    Check(hair[0].size() >= 10 && hair[1].size() == 16, "the hair styles");
    Check(missing == 0, "every texture the looks name is in the patch (" + std::to_string(missing) + " missing of " + std::to_string((checked + 96) / 97) + " looked at)");

    for (char const* name : { "Character\\VoidElf\\Female\\VoidElfFemale.m2", "Character\\VoidElf\\Female\\VoidElfFemale00.skin",
                              "Character\\VoidElf\\Male\\VoidElfMale.m2", "Character\\Nightborne\\Female\\NightborneFemale.m2",
                              "Character\\Nightborne\\Male\\NightborneMale.m2", "Character\\Nightborne\\Male\\NightborneMale00.skin",
                              "Interface\\GlueXML\\AlliedRacesCreate.lua", "Interface\\GlueXML\\AlliedRacesCreate.xml",
                              "Sound\\CinematicVoices\\VoidElfNarration.mp3", "Sound\\CinematicVoices\\NightborneNarration.mp3" })
        Check(patch->Read(name, bytes) && bytes.size() > 1000, std::string(name) + " is in it");
    Check(patch->Read("Interface\\GlueXML\\GlueXML.toc", bytes) && std::string(bytes.begin(), bytes.end()).find("AlliedRacesCreate.lua") != std::string::npos,
          "GlueXML.toc lists the character screen's files");

    Dbc spells;
    Check(patch->Read("DBFilesClient\\Spell.dbc", bytes) && spells.Parse(bytes), "Spell.dbc is in it");
    int racials = 0;
    for (size_t i = 0; i < spells.records.size(); ++i)
        if (spells.Get(i, 0) >= 98711 && spells.Get(i, 0) <= 98722)
            ++racials;
    Check(racials == 12, "the twelve racial spells are in it (" + std::to_string(racials) + ")");

    Dbc displays, models, sounds, vocal, emotes, names;
    Check(patch->Read("DBFilesClient\\CreatureDisplayInfo.dbc", bytes) && displays.Parse(bytes), "CreatureDisplayInfo.dbc is in it");
    Check(patch->Read("DBFilesClient\\CreatureModelData.dbc", bytes) && models.Parse(bytes), "CreatureModelData.dbc is in it");
    int looks = 0, modelRows = 0;
    for (size_t i = 0; i < displays.records.size(); ++i)
        if (displays.Get(i, 0) >= 33001 && displays.Get(i, 0) <= 33004)
            ++looks;
    for (size_t i = 0; i < models.records.size(); ++i)
        if (models.Get(i, 0) >= 3601 && models.Get(i, 0) <= 3604)
            ++modelRows;
    Check(looks == 4 && modelRows == 4, "the four looks and models");
    Check(patch->Read("DBFilesClient\\SoundEntries.dbc", bytes) && sounds.Parse(bytes), "SoundEntries.dbc is in it");
    size_t voices = 0, wavs = 0;
    for (size_t i = 0; i < sounds.records.size(); ++i)
        if (sounds.Get(i, 0) >= 910100 && sounds.Get(i, 0) < 920000)
        {
            ++voices;
            if (voices % 23 == 1)
            {
                uint32_t dir = sounds.Get(i, 23), file = sounds.Get(i, 3);
                std::string path = std::string(reinterpret_cast<char const*>(&sounds.strings[dir])) + "\\" + reinterpret_cast<char const*>(&sounds.strings[file]);
                Bytes wav;
                if (patch->Read(path, wav) && wav.size() > 400)
                    ++wavs;
                else
                    std::cout << "  no voice file " << path << "\n";
            }
        }
    Check(voices > 200 && wavs == (voices + 22) / 23, "the voices' sounds and their files (" + std::to_string(voices) + " sounds)");
    Check(patch->Read("DBFilesClient\\VocalUISounds.dbc", bytes) && vocal.Parse(bytes), "VocalUISounds.dbc is in it");
    Check(patch->Read("DBFilesClient\\EmotesTextSound.dbc", bytes) && emotes.Parse(bytes), "EmotesTextSound.dbc is in it");
    Check(patch->Read("DBFilesClient\\NameGen.dbc", bytes) && names.Parse(bytes), "NameGen.dbc is in it");
    Check(patch->Read("Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-Races.blp", bytes) && bytes.size() > 1000, "the race icons are in it");

    std::cout << (failures ? "FAILED" : "PASSED") << " (" << failures << " failures)\n";
    return failures ? 1 : 0;
}
