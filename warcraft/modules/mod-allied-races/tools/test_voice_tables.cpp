// SPDX-License-Identifier: GPL-3.0-or-later
// Apply both race patches in runtime order: each race/sex's voice must survive the later patch.
#include "SkyborneFiles.h"
#include <array>
#include <iostream>
#include <map>
#include <set>
#include <stdexcept>

using namespace Skyborne;
using VoiceKey = std::pair<uint32_t, uint32_t>; // race, VocalUIEnum

static void Require(bool ok, std::string const& what)
{
    if (!ok)
        throw std::runtime_error(what);
}

static std::string Text(fs::path const& path)
{
    std::ifstream in(path);
    Require(bool(in), "missing generated changes: " + path.string());
    return std::string(std::istreambuf_iterator<char>(in), {});
}

static Dbc Empty(uint32_t fields)
{
    Dbc table;
    table.fields = fields;
    table.size = fields * 4;
    table.strings = { 0 };
    return table;
}

static size_t Row(Dbc const& table, uint32_t id)
{
    for (size_t i = 0; i < table.records.size(); ++i)
        if (table.Get(i, 0) == id)
            return i;
    throw std::runtime_error("missing sound row " + std::to_string(id));
}

static std::string String(Dbc const& table, size_t row, uint32_t field)
{
    auto offset = table.Get(row, field);
    Require(offset < table.strings.size(), "sound string offset outside table");
    auto end = offset;
    while (end < table.strings.size() && table.strings[end])
        ++end;
    Require(end < table.strings.size(), "unterminated sound string");
    return std::string(table.strings.begin() + offset, table.strings.begin() + end);
}

static void Apply(Dbc& table, fs::path const& changes)
{
    auto error = ApplyChanges(table, Text(changes));
    Require(error.empty(), changes.string() + ": " + error);
    // The next patch reads the preceding patch's serialized table, including its strings.
    Dbc parsed;
    Require(parsed.Parse(table.Write()), "voice table serialization failed");
    table = std::move(parsed);
}

static Dbc Source(fs::path const& client, char const* name, uint32_t fields)
{
    Dbc table = Empty(fields);
    Apply(table, client / "dbc" / (std::string(name) + ".txt"));
    return table;
}

static void AddNativeSound(Dbc& sounds, uint32_t id)
{
    sounds.records.emplace_back(sounds.size, 0);
    auto row = sounds.records.size() - 1;
    sounds.Set(row, 0, id);
    sounds.Set(row, 1, 12); // native UI voice type
    std::string label = id == 9549 ? "BloodElfMale_InventoryFull" :
        id == 9550 ? "BloodElfFemale_InventoryFull" : "NativeVoice" + std::to_string(id);
    sounds.Set(row, 2, sounds.String(label));
    sounds.Set(row, 3, sounds.String(label + ".mp3"));
    sounds.Set(row, 13, 1);
    sounds.Set(row, 23, sounds.String("Sound\\Character\\Fixture"));
    sounds.Set(row, 24, 0x3f800000); // 1.0f
}

static void CheckSound(Dbc const& final, Dbc const& expected, uint32_t id)
{
    auto row = Row(final, id), source = Row(expected, id);
    Require(final.Get(row, 1) == expected.Get(source, 1), "voice sound type changed");
    Require(String(final, row, 23) == String(expected, source, 23), "voice directory changed");
    bool occupied = false;
    for (uint32_t variant = 0; variant < 10; ++variant)
    {
        std::string file = String(final, row, 3 + variant);
        Require(file == String(expected, source, 3 + variant), "voice filename changed");
        Require(final.Get(row, 13 + variant) == expected.Get(source, 13 + variant), "voice playback weight changed");
        Require((file.empty() && final.Get(row, 13 + variant) == 0) ||
            (!file.empty() && final.Get(row, 13 + variant) > 0), "empty or unweighted voice alternative");
        occupied = occupied || !file.empty();
    }
    Require(occupied, "voice kit contains no files");
}

int main(int argc, char** argv)
{
    if (argc != 3)
        return 2;
    try
    {
        fs::path allied = argv[1], skyborne = argv[2];
        Dbc alliedVoices = Source(allied, "VocalUISounds", 7);
        Dbc skyborneVoices = Source(skyborne, "VocalUISounds", 7);
        Dbc alliedEmotes = Source(allied, "EmotesTextSound", 5);
        Dbc skyborneEmotes = Source(skyborne, "EmotesTextSound", 5);
        Dbc sounds = Empty(30), voices = Empty(7), emotes = Empty(5);

        // Skyborne uses native Blood Elf kits. Provide those fixtures without game files.
        std::set<uint32_t> nativeSounds;
        for (size_t i = 0; i < skyborneVoices.records.size(); ++i)
            for (uint32_t sex = 0; sex < 2; ++sex)
                if (auto id = skyborneVoices.Get(i, 3 + sex))
                    nativeSounds.insert(id);
        for (size_t i = 0; i < skyborneEmotes.records.size(); ++i)
            if (auto id = skyborneEmotes.Get(i, 4))
                nativeSounds.insert(id);
        for (uint32_t id : nativeSounds)
            AddNativeSound(sounds, id);
        voices.records.emplace_back(voices.size, 0);
        for (auto const& field : { std::make_pair(0u, 742u), { 1u, 0u }, { 2u, 10u },
                                  { 3u, 9549u }, { 4u, 9550u } })
            voices.Set(0, field.first, field.second);
        Bytes nativeVoice = voices.records[0];
        Bytes nativeMaleSound = sounds.records[Row(sounds, 9549)];
        Bytes nativeFemaleSound = sounds.records[Row(sounds, 9550)];

        // Each owner's expected payload stays independent of the later patch. Sharing the
        // merged expected table would conceal sound-ID collisions between the two modules.
        Dbc alliedSounds = sounds, skyborneSounds = sounds;
        Apply(alliedSounds, allied / "dbc/SoundEntries.txt");
        Apply(skyborneSounds, skyborne / "dbc/SoundEntries.txt");

        // This is the actual startup order: patch-V first, followed by Skyborne's patch-Z.
        for (auto const& client : { allied, skyborne })
        {
            Apply(sounds, client / "dbc/SoundEntries.txt");
            Apply(voices, client / "dbc/VocalUISounds.txt");
            Apply(emotes, client / "dbc/EmotesTextSound.txt");
        }
        Require(voices.records[0] == nativeVoice, "ordinary race voice row changed");
        Require(sounds.records[Row(sounds, 9549)] == nativeMaleSound, "native male inventory voice changed");
        Require(sounds.records[Row(sounds, 9550)] == nativeFemaleSound, "native female inventory voice changed");

        std::map<VoiceKey, size_t> byVoice;
        for (size_t i = 0; i < voices.records.size(); ++i)
        {
            VoiceKey key { voices.Get(i, 2), voices.Get(i, 1) };
            Require(byVoice.emplace(key, i).second, "duplicate race/enum voice mapping");
        }
        std::map<uint32_t, size_t> raceCounts;
        std::set<uint32_t> checkedSounds;
        for (auto const& owner : { std::make_pair(&alliedVoices, &alliedSounds), { &skyborneVoices, &skyborneSounds } })
        {
            auto const* source = owner.first;
            for (size_t i = 0; i < source->records.size(); ++i)
            {
                VoiceKey key { source->Get(i, 2), source->Get(i, 1) };
                auto found = byVoice.find(key);
                Require(found != byVoice.end(), "missing race " + std::to_string(key.first) +
                    " vocal enum " + std::to_string(key.second) + " after both patches");
                ++raceCounts[key.first];
                for (uint32_t field = 3; field < 7; ++field)
                {
                    auto expected = source->Get(i, field), actual = voices.Get(found->second, field);
                    Require(actual == expected, "wrong sex-specific voice for race " + std::to_string(key.first));
                    if (actual)
                    {
                        CheckSound(sounds, *owner.second, actual);
                        checkedSounds.insert(actual);
                    }
                }
                if (key.first == 15 || key.first == 16)
                    Require(source->Get(i, 3) && source->Get(i, 4) && source->Get(i, 3) != source->Get(i, 4),
                        "allied vocal lacks distinct male/female sound references");
            }
        }
        Require(raceCounts[15] == 51 && raceCounts[16] == 51,
            "expected 49 supplied vocal events plus two native aliases for each allied race");
        for (uint32_t race : { 15u, 16u })
            for (auto const& alias : { std::make_pair(6u, 47u), { 66u, 11u } })
            {
                auto legacy = byVoice.find({ race, alias.first });
                auto canonical = byVoice.find({ race, alias.second });
                Require(legacy != byVoice.end() && canonical != byVoice.end(), "native vocal alias missing");
                for (uint32_t field = 3; field < 7; ++field)
                    Require(voices.Get(legacy->second, field) == voices.Get(canonical->second, field),
                        "native vocal alias selects the wrong sex-specific sound");
            }
        for (uint32_t race : { 13u, 14u, 15u, 16u })
        {
            auto found = byVoice.find({ race, 0 }); // inventory full
            Require(found != byVoice.end(), "inventory-full mapping missing");
            Require(voices.Get(found->second, 3) && voices.Get(found->second, 4), "inventory-full voice missing for one sex");
            std::cout << "ok race " << race << ": " << raceCounts[race] << " vocal events, both inventory-full voices preserved\n";
        }

        // Spoken emotes share the same sound table: check the later patch preserves their keys too.
        using EmoteKey = std::array<uint32_t, 3>; // emote, race, sex
        std::map<EmoteKey, uint32_t> byEmote;
        for (size_t i = 0; i < emotes.records.size(); ++i)
            Require(byEmote.emplace(EmoteKey { emotes.Get(i, 1), emotes.Get(i, 2), emotes.Get(i, 3) },
                emotes.Get(i, 4)).second, "duplicate spoken-emote key");
        for (auto const& owner : { std::make_pair(&alliedEmotes, &alliedSounds), { &skyborneEmotes, &skyborneSounds } })
        {
            auto const* source = owner.first;
            for (size_t i = 0; i < source->records.size(); ++i)
            {
                EmoteKey key { source->Get(i, 1), source->Get(i, 2), source->Get(i, 3) };
                auto found = byEmote.find(key);
                Require(found != byEmote.end() && found->second == source->Get(i, 4), "spoken emote overwritten");
                if (found->second)
                {
                    CheckSound(sounds, *owner.second, found->second);
                    checkedSounds.insert(found->second);
                }
            }
        }
        std::cout << "ok " << byEmote.size() << " spoken emotes and " << checkedSounds.size()
                  << " voice kits: files, male/female references and occupied playback weights preserved\n";
    }
    catch (std::exception const& error)
    {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
