// SPDX-License-Identifier: GPL-3.0-or-later
// Build a small client fixture, apply the real cinematic tables and MP3s, and follow the
// race -> sequence -> camera -> sound -> archive file links through the resulting patch.
#include "AlliedRacesPatch.h"
#include "SkyborneFiles.h"
#include <chrono>
#include <iostream>
#include <stdexcept>

using namespace Skyborne;

static void Require(bool ok, std::string const& what)
{
    if (!ok)
        throw std::runtime_error(what);
}

static Bytes Read(fs::path const& path)
{
    std::ifstream in(path, std::ios::binary);
    return Bytes(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

static size_t Row(Dbc const& table, uint32_t id)
{
    for (size_t i = 0; i < table.records.size(); ++i)
        if (table.Get(i, 0) == id)
            return i;
    throw std::runtime_error("missing row " + std::to_string(id));
}

static std::string String(Dbc const& table, size_t row, uint32_t field)
{
    auto offset = table.Get(row, field);
    Require(offset < table.strings.size(), "string offset outside table");
    return reinterpret_cast<char const*>(&table.strings[offset]);
}

int main(int argc, char** argv)
{
    if (argc != 2)
        return 2;
    fs::path root = fs::temp_directory_path() / ("allied-intro-" + std::to_string(
        std::chrono::steady_clock::now().time_since_epoch().count()));
    try
    {
        fs::path source = argv[1], module = root / "module", files = root / "files", data = root / "Data";
        fs::create_directories(module / "dbc");
        fs::create_directories(files);
        fs::create_directories(data);
        MpqWriter base(data / "common.MPQ");
        for (auto const& spec : { std::make_pair("ChrRaces", 69u), { "CinematicSequences", 10u },
                                  { "CinematicCamera", 7u }, { "SoundEntries", 30u } })
        {
            Dbc table;
            table.fields = spec.second;
            table.size = table.fields * 4;
            table.strings = { 0 };
            Require(base.Add(std::string("DBFilesClient\\") + spec.first + ".dbc", table.Write()), "write base table");
            fs::copy_file(source / "dbc" / (std::string(spec.first) + ".txt"), module / "dbc" / (std::string(spec.first) + ".txt"));
        }
        Require(base.Finish(), "finish base archive");
        for (char const* name : { "ve.mp3", "nb.mp3" })
            fs::copy_file(source / name, module / name);
        { std::ofstream manifest(files / "alliedraces.files"); manifest << "fixture.bin\n"; }
        { std::ofstream fixture(files / "fixture.bin"); fixture << "fixture"; }
        auto log = [](std::string const&) {};
        Require(AlliedRaces::UpdatePatch(module, files, data, log), "build patch");
        auto made = fs::last_write_time(data / "patch-V.MPQ");
        Require(AlliedRaces::UpdatePatch(module, files, data, log), "reuse patch");
        Require(fs::last_write_time(data / "patch-V.MPQ") == made, "second start must keep patch");
        auto patch = OpenArchive(data / "patch-V.MPQ");
        auto table = [&](char const* name)
        {
            Bytes bytes;
            Dbc result;
            Require(patch->Read(std::string("DBFilesClient\\") + name + ".dbc", bytes) && result.Parse(bytes), name);
            return result;
        };
        Dbc races = table("ChrRaces"), sequences = table("CinematicSequences"), cameras = table("CinematicCamera"), sounds = table("SoundEntries");
        for (uint32_t race : { 15u, 16u })
        {
            auto sequence = Row(sequences, races.Get(Row(races, race), 12));
            auto camera = Row(cameras, sequences.Get(sequence, 2));
            auto sound = Row(sounds, cameras.Get(camera, 2));
            std::string path = String(sounds, sound, 23) + "\\" + String(sounds, sound, 3);
            Bytes narration;
            Require(patch->Read(path, narration), "narration missing at " + path);
            Require(narration.size() > 1000 && narration == Read(source / (race == 15 ? "ve.mp3" : "nb.mp3")), "narration bytes differ");
            Require(sounds.Get(sound, 13) == 1, "narration has no playback weight");
            for (uint32_t i = 1; i < 10; ++i)
                Require(sounds.Get(sound, 13 + i) == 0 && String(sounds, sound, 3 + i).empty(), "empty narration alternative has weight");
            std::cout << "ok race " << race << ": " << path << " (" << narration.size() << " bytes)\n";
        }
        fs::remove_all(root);
        return 0;
    }
    catch (std::exception const& error)
    {
        std::cerr << error.what() << '\n';
        fs::remove_all(root);
        return 1;
    }
}
