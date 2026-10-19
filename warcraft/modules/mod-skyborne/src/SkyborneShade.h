// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The shading of the female body textures (data/client/shade, made by tools/art/races/shade_export.py:
 * for each race the fields of its texture's pelvis box and the settings, tools/art/races/lines.py is
 * the same calculation). Nothing here needs the server.
 */

#ifndef MOD_SKYBORNE_SHADE_H
#define MOD_SKYBORNE_SHADE_H

#include "SkyborneFiles.h"
#include <functional>
#include <map>

namespace Skyborne
{
    class BodyShade
    {
    public:
        // Reads [dir] (shade.txt and the races' files); a race whose model isn't the one its fields were
        // made for (the female model's and its first skin file's checksums in [game]) is left out.
        void Load(fs::path const& dir, Game const& game, std::function<void(std::string const&)> const& log);
        // [image] (512 by 512), the texture at [path] (the game's path of a female body texture), shaded.
        // False when the path isn't one with settings or the image isn't 512 by 512.
        bool Apply(std::string const& path, Image& image) const;
        size_t Races() const { return _races.size(); }

        struct Race
        {
            uint32_t x0 = 0, y0 = 0, w = 0, h = 0;
            bool noise = false;
            std::vector<float> y, s, z;
            std::vector<uint8_t> ok;
            std::vector<float> field[5];
            std::map<std::string, std::vector<double>> p;
            uint32_t crcModel = 0, crcSkin = 0;
        };

    private:
        std::map<std::string, Race> _races;      // by the race's name in the game's paths, lower case
    };
}

#endif
