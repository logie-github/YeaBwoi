// SPDX-License-Identifier: GPL-3.0-or-later
// The Murlocs' and the Naga's patch made for a game folder, and read back: the tables (the races,
// their looks, the creatures' races moved), the murloc's character model (its skin the composed
// texture, its texture coordinates in the face region) and skins, the naga's new colours, the
// icons, the screen's files, the intros (cameras, narrations); a second start keeping it.
//
//   g++ -std=c++17 -O2 -DSKYBORNE_BZIP2 -I../src -I../../mod-skyborne/src test_patch.cpp
//       ../src/MurlocNagaPatch.cpp ../../mod-skyborne/src/SkyborneFiles.cpp -lz -lbz2 -o test_patch
//   ./test_patch ../data/client GAME_DATA [--no-textures]
//
// GAME_DATA: a client's Data folder (its archives, or folders laid out as they are). With
// --no-textures (the server's tables alone, as CI's server test has), the models, textures and
// sounds aren't checked.

#include "MurlocNagaPatch.h"
#include <cmath>
#include <cstring>
#include <iostream>

using namespace MurlocNaga;
using Skyborne::Dbc;

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
    if (argc < 3)
    {
        std::cerr << "test_patch MODULE_CLIENT GAME_DATA [--no-textures]\n";
        return 2;
    }
    fs::path module = argv[1], data = argv[2];
    bool textures = !(argc > 3 && std::string(argv[3]) == "--no-textures");
    std::string log;
    auto keep = [&](std::string const& line) { log += line + "\n"; std::cout << "  | " << line << "\n"; };
    fs::path patch = data / "enUS" / "patch-enUS-T.MPQ";
    std::error_code error;
    fs::remove(patch, error);
    fs::remove(fs::path(patch.string() + ".murlocnaga"), error);
    Check(UpdatePatch(module, data, keep), "the patch is made");
    Check(fs::exists(patch), "it's Data/enUS/patch-enUS-T.MPQ");
    auto archive = Skyborne::OpenArchive(patch);
    Check(archive != nullptr, "it reads as an archive");
    if (!archive)
        return 1;

    auto table = [&](std::string const& name, Dbc& dbc) { Bytes b; return archive->Read("DBFilesClient\\" + name + ".dbc", b) && dbc.Parse(b); };
    auto str = [](Dbc const& dbc, uint32_t at) { return std::string(reinterpret_cast<char const*>(&dbc.strings[at])); };

    Dbc races;
    Check(table("ChrRaces", races), "ChrRaces is in it");
    bool murloc = false, naga = false, taunka = false, skeleton = false;
    for (size_t i = 0; i < races.records.size(); ++i)
    {
        uint32_t id = races.Get(i, 0);
        std::string file = str(races, races.Get(i, 11));
        murloc |= id == 19 && file == "Murloc" && races.Get(i, 13) == 0 && races.Get(i, 12) == 9021;
        naga |= id == 20 && file == "Naga_" && races.Get(i, 13) == 1 && races.Get(i, 4) == 17402 && races.Get(i, 12) == 9022;
        taunka |= id == 30 && file == "Taunka";
        skeleton |= id == 31 && file == "NorthrendSkeleton";
    }
    Check(murloc, "race 19 is the Murloc (Alliance, its intro)");
    Check(naga, "race 20 is the Naga (Horde, the game's naga looks, its intro)");
    Check(taunka && skeleton, "the Taunka and the Northrend Skeleton moved to 30 and 31");

    Dbc sections;
    Check(table("CharSections", sections), "CharSections is in it");
    size_t murlocFaces = 0, nagaSkins = 0, nagaNew = 0, movedRows = 0, leftAt19 = 0;
    for (size_t i = 0; i < sections.records.size(); ++i)
    {
        uint32_t race = sections.Get(i, 1), kind = sections.Get(i, 3), colour = sections.Get(i, 9);
        std::string tex = str(sections, sections.Get(i, 4));
        if (race == 19 && kind == 1 && tex.find("Character\\Murloc\\") == 0)
            ++murlocFaces;
        if (race == 20 && kind == 0)
            ++nagaSkins;
        if (race == 20 && kind == 0 && colour >= 5)
            ++nagaNew;
        if (race == 30 || race == 31)
            ++movedRows;
        if ((race == 19 || race == 20) && tex.find("Character\\Murloc\\") != 0 && tex.find("Character\\Naga_\\") != 0 && !tex.empty())
            ++leftAt19;
    }
    Check(murlocFaces == 20, "the murlocs' faces: 10 skins a sex (" + std::to_string(murlocFaces) + ")");
    Check(nagaSkins == 18, "the naga's skins: 9 a sex (" + std::to_string(nagaSkins) + ")");
    Check(nagaNew == 8, "the naga's new colours: 4 a sex (" + std::to_string(nagaNew) + ")");
    Check(leftAt19 == 0, "no creature's look left at races 19 and 20 (" + std::to_string(leftAt19) + ")");
    std::cout << "     (" << movedRows << " looks of the creatures moved to 30 and 31)\n";

    for (char const* name : { "CharBaseInfo", "CharHairGeosets", "CharacterFacialHairStyles", "CharStartOutfit", "CinematicCamera",
                              "CinematicSequences", "CreatureDisplayInfo", "CreatureModelData", "Faction", "GameObjectDisplayInfo",
                              "Item", "NameGen", "SoundEntries", "Spell" })
    {
        Dbc t;
        Check(table(name, t), std::string(name) + " is in it");
    }

    Bytes toc;
    Check(archive->Read("Interface\\GlueXML\\GlueXML.toc", toc) && std::string(toc.begin(), toc.end()).find("MurlocNagaCreate.lua") != std::string::npos,
        "the creation screen's file list has theirs");
    Bytes lua;
    Check(archive->Read("Interface\\GlueXML\\MurlocNagaCreate.lua", lua) && !lua.empty(), "their screen code is in it");
    for (char const* camera : { "Cameras\\FlybyMurloc.m2", "Cameras\\FlybyNaga.m2", "Cameras\\FlybyNaga00.skin" })
    {
        Bytes c;
        Check(archive->Read(camera, c) && !c.empty(), std::string("the intro's ") + camera);
    }

    if (textures)
    {
        Bytes model;
        Check(archive->Read("Character\\Murloc\\Male\\MurlocMale.m2", model), "the murlocs' model (men)");
        Bytes female;
        Check(archive->Read("Character\\Murloc\\Female\\MurlocFemale.m2", female), "the murlocs' model (women)");
        Bytes skin;
        Check(archive->Read("Character\\Murloc\\Male\\MurlocMale00.skin", skin), "its 00.skin");
        if (model.size() > 0x130)
        {
            uint32_t textures = U32(model, 0x50), at = U32(model, 0x54);
            Check(textures > 0 && U32(model, at) == 1, "its body texture is the character's");
            uint32_t vertices = U32(model, 0x3C), vat = U32(model, 0x40);
            bool inside = vertices > 0;
            for (uint32_t i = 0; i < vertices; ++i)
            {
                float uv[2];
                std::memcpy(uv, &model[vat + i * 48 + 32], 8);
                inside &= uv[0] >= 0.0f && uv[0] <= 0.5f && uv[1] >= 0.625f && uv[1] <= 1.0f;
            }
            Check(inside, "every vertex reads the face region (" + std::to_string(vertices) + " vertices)");
        }
        for (char const* sex : { "Male", "Female" })
            for (int c : { 0, 9 })
            {
                char name[96];
                std::snprintf(name, sizeof(name), "Character\\Murloc\\%s\\Murloc%sFaceLower00_%02d.blp", sex, sex, c);
                Bytes face;
                Skyborne::Image image;
                Check(archive->Read(name, face) && Skyborne::Decode(face, image) && image.width == 256 && image.height == 128, name);
                std::snprintf(name, sizeof(name), "Character\\Murloc\\%s\\Murloc%sFaceUpper00_%02d.blp", sex, sex, c);
                Check(archive->Read(name, face) && Skyborne::Decode(face, image) && image.width == 256 && image.height == 64, name);
            }
        for (char const* name : { "Character\\Naga_\\Male\\Naga_MaleSkin00_05.blp", "Character\\Naga_\\Male\\Naga_MaleSkin00_08_Extra.blp",
                                  "Character\\Naga_\\Female\\Naga_FemaleFaceLower00_06.blp", "Character\\Naga_\\Female\\Naga_FemaleNakedTorsoSkin00_07.blp" })
        {
            Bytes t;
            Skyborne::Image image;
            Check(archive->Read(name, t) && Skyborne::Decode(t, image), name);
        }
        for (auto [name, least] : { std::pair<char const*, double>{ "Sound\\CinematicVoices\\NagaNarration.wav", 58.0 },
                                    std::pair<char const*, double>{ "Sound\\CinematicVoices\\MurlocNarration.wav", 39.0 } })
        {
            Bytes wav;
            bool ok = archive->Read(name, wav) && wav.size() > 44;
            double seconds = ok ? (wav.size() - 44) / 44100.0 : 0;
            Check(ok && seconds >= least, std::string(name) + " (" + std::to_string(seconds) + " s)");
        }
        Bytes icons;
        Skyborne::Image sheet;
        Check(archive->Read("Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-MurlocNaga.blp", icons) && Skyborne::Decode(icons, sheet)
            && sheet.width == 64 && sheet.height == 256, "their icons");
    }

    // A second start: nothing changed, the patch kept as it is.
    auto before = fs::last_write_time(patch);
    archive.reset();
    log.clear();
    Check(UpdatePatch(module, data, keep) && fs::last_write_time(patch) == before && log.empty(), "a second start keeps it");

    std::cout << (failures ? std::to_string(failures) + " FAILED" : "all passed") << "\n";
    return failures ? 1 : 0;
}
