// Preserve an installed client's HD rows when freeing race slots for playable custom races.
#include "SkyborneFiles.h"
#include <iostream>
#include <stdexcept>

using namespace Skyborne;

static void Require(bool ok, char const* message)
{
    if (!ok)
        throw std::runtime_error(message);
}

static std::string Text(fs::path const& path)
{
    std::ifstream in(path);
    Require(bool(in), "missing generated changes");
    return std::string(std::istreambuf_iterator<char>(in), {});
}

static size_t Row(Dbc const& table, uint32_t id)
{
    for (size_t i = 0; i < table.records.size(); ++i)
        if (table.Get(i, 0) == id)
            return i;
    throw std::runtime_error("missing row");
}

static Dbc Fixture(uint32_t fields)
{
    Dbc table;
    table.fields = fields;
    table.size = fields * 4;
    table.strings = { 0 };
    return table;
}

int main(int argc, char** argv)
{
    if (argc != 3)
        return 2;
    try
    {
        for (int module = 0; module < 2; ++module)
        {
            fs::path dir = fs::path(argv[module + 1]) / "dbc";
            uint32_t oldRace = module == 0 ? 13 : 15, newRace = module == 0 ? 22 : 24;
            for (auto const& spec : { std::make_pair("CharSections", 10u),
                                     { "CharHairGeosets", 6u }, { "CreatureDisplayInfoExtra", 21u } })
            {
                Dbc table = Fixture(spec.second);
                uint32_t id = std::string(spec.first) == "CharSections" ? (module == 0 ? 6223 : 6259) :
                    std::string(spec.first) == "CharHairGeosets" ? (module == 0 ? 247 : 298) :
                    (module == 0 ? 11676 : 12424);
                for (uint32_t key : { id, 80001u })
                {
                    table.records.emplace_back(table.size, 0);
                    auto row = table.records.size() - 1;
                    table.Set(row, 0, key);
                    table.Set(row, 1, key == id ? 5 : oldRace);
                    table.Set(row, 4, table.String("HD texture marker"));
                }
                Bytes npc = table.records[0], hd = table.records[1];
                auto problem = ApplyChanges(table, Text(dir / (std::string(spec.first) + ".txt")));
                Require(problem.empty(), problem.c_str());
                Require(table.records[Row(table, id)] == npc, "unrelated HD NPC row overwritten by vanilla row ID");
                Require(table.Get(Row(table, 80001), 1) == newRace, "installed HD race was not remapped");
                table.Set(Row(table, 80001), 1, oldRace);
                Require(table.records[Row(table, 80001)] == hd, "HD row payload lost while moving race");
                table.Set(Row(table, 80001), 1, newRace);
                // Apply again to check that existing moved rows remain stable.
                Require(ApplyChanges(table, Text(dir / (std::string(spec.first) + ".txt"))).empty(), "reapply");
                Require(table.Get(Row(table, 80001), 1) == newRace, "installed HD race was not remapped");
                std::cout << "ok " << dir.parent_path() << ": " << spec.first << " preserves HD NPCs\n";
            }
            Dbc races = Fixture(69);
            races.records.emplace_back(races.size, 0);
            races.Set(0, 0, oldRace);
            races.Set(0, 4, 76543); // An HD display that differs from the server's vanilla DBC.
            Require(ApplyChanges(races, Text(dir / "ChrRaces.txt")).empty(), "race changes");
            Require(races.Get(Row(races, newRace), 4) == 76543, "moved race lost its installed HD display");
        }
    }
    catch (std::exception const& e)
    {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
