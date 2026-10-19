// SPDX-License-Identifier: GPL-3.0-or-later
// The body shading on its own: each race's first female skin shaded, with the skin before and after written as
// PPM files, to compare with tools/art/races/lines.py's.
//
//   g++ -std=c++17 -O2 -DSKYBORNE_BZIP2 -I../src test_shade.cpp ../src/SkyborneFiles.cpp ../src/SkyborneShade.cpp -lz -lbz2
//   ./a.out MODULE_DATA GAME_DATA OUT [SKIN]

#include "SkyborneShade.h"
#include <iostream>

using namespace Skyborne;

static void Ppm(fs::path const& path, Image const& image)
{
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << image.width << " " << image.height << "\n255\n";
    for (size_t i = 0; i < size_t(image.width) * image.height; ++i)
        out.write(reinterpret_cast<char const*>(&image.rgba[i * 4]), 3);
}

int main(int argc, char** argv)
{
    if (argc < 4)
    {
        std::cerr << "test_shade MODULE_DATA GAME_DATA OUT [SKIN]\n";
        return 2;
    }
    fs::path module = argv[1], data = argv[2], out = argv[3];
    std::string skin = argc > 4 ? argv[4] : "00";
    Game game(data, { "patch-z.mpq", "patch-y.mpq" });
    BodyShade shade;
    shade.Load(module / "shade", game, [](std::string const& line) { std::cout << line << "\n"; });
    std::cout << shade.Races() << " races\n";
    fs::create_directories(out);
    for (char const* race : { "BloodElf", "Human", "Dwarf", "NightElf", "Gnome", "Draenei", "Orc", "Scourge", "Tauren", "Troll", "Goblin" })
    {
        std::string path = std::string("Character\\") + race + "\\Female\\" + race + "FemaleSkin00_" + skin + ".blp";
        Bytes bytes;
        Image image;
        if (!game.Read(path, bytes) || !Decode(bytes, image))
        {
            std::cout << race << ": no texture\n";
            continue;
        }
        Ppm(out / (std::string(race) + "_in.ppm"), image);
        bool ok = shade.Apply(path, image);
        Ppm(out / (std::string(race) + "_out.ppm"), image);
        std::cout << race << (ok ? ": shaded\n" : ": left\n");
    }
    return 0;
}
