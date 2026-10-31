// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Murlocs' and the Naga's patch for the game (mod-murloc-naga). The game needs its tables to
 * know the two races (ChrRaces, CharSections, CharHairGeosets... with their rows: the module's
 * data/client/dbc, made by tools/build_data.py), the murlocs' character model and skins, the
 * naga's new skin colours, their place on the character creation screen, their intros (the
 * cameras, the narrations) and their homes' things (GameObjectDisplayInfo).
 *
 * The tables, the naga's colours, the screen's files and the intros are made here from the client's
 * own files (WOW_CLIENT_DIR: an HD look's, when one is in): the naga's looks are the game's naga
 * (Character\Naga_), its new colours the game's naga skins recoloured; the narrations the game's own
 * sounds, mixed (Lady Vashj's words for the naga, the murlocs' own voices for the murlocs). The
 * murlocs' model and textures are Blizzard's newer murloc and its kin's parts, made 3.3.5's as the
 * server is packaged (tools/murloc_model.py: data/files, never in this repository).
 *
 * It's Data/<locale>/patch-<locale>-T.MPQ: read after the HD looks' patches (A, F, 6, E, Q, the
 * Alternative HD Patch's S with its SoundEntries; ChromieCraft's H, F, G, S, T), whose tables it
 * builds on, and before the Worgen and Goblins' patch-U, the allied races' patch-V, Wrath Remix's
 * patch-Y and the Skyborne's patch-Z, which are made after it from the game with it in and so
 * carry its rows. The letter T in the language's folder is free in both HD looks.
 *
 * The archive reading and writing, the tables and the texture codec are the Skyborne's
 * (SkyborneFiles).
 */

#include "MurlocNagaPatch.h"
#include <algorithm>
#include <array>
#include <cctype>
#include <climits>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <sstream>
#include <tuple>

namespace MurlocNaga
{
    using Skyborne::Dbc;
    using Skyborne::Game;
    using Skyborne::Image;
    using Skyborne::MpqWriter;

    namespace
    {
        constexpr char const* VERSION = "murloc-naga-2";
        constexpr char const* MARK_SUFFIX = ".murlocnaga";
        constexpr char const* ASIDE_SUFFIX = ".aside";

        Bytes ReadFile(fs::path const& path)
        {
            std::ifstream in(path, std::ios::binary);
            return Bytes(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
        }

        std::string Text(fs::path const& path)
        {
            Bytes b = ReadFile(path);
            return std::string(b.begin(), b.end());
        }

        std::string Lower(std::string s)
        {
            std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return char(std::tolower(c)); });
            return s;
        }

        uint32_t U32(Bytes const& b, size_t at)
        {
            uint32_t v = 0;
            if (at + 4 <= b.size())
                std::memcpy(&v, &b[at], 4);
            return v;
        }

        void Put32(Bytes& b, size_t at, uint32_t v) { std::memcpy(&b[at], &v, 4); }

        // FNV-1a: what the patch is made from, to know when to make it again.
        struct Stamp
        {
            uint64_t value = 1469598103934665603ull;
            void Add(std::string const& text)
            {
                for (unsigned char c : text)
                {
                    value ^= c;
                    value *= 1099511628211ull;
                }
                value ^= 0xFF;
                value *= 1099511628211ull;
            }
            std::string Hex() const
            {
                char buf[17];
                std::snprintf(buf, sizeof(buf), "%016llx", static_cast<unsigned long long>(value));
                return buf;
            }
        };

        // The client's language: the folder in Data with its locale-<name>.MPQ (a file or a folder).
        std::string Locale(fs::path const& clientData)
        {
            std::error_code error;
            for (auto it = fs::directory_iterator(clientData, error); !error && it != fs::directory_iterator(); it.increment(error))
            {
                std::string name = it->path().filename().string();
                if (name.size() != 4 || !it->is_directory(error))
                    continue;
                for (auto in = fs::directory_iterator(it->path(), error); !error && in != fs::directory_iterator(); in.increment(error))
                    if (Lower(in->path().filename().string()) == Lower("locale-" + name + ".mpq"))
                        return name;
            }
            return "";
        }

        fs::path PatchPath(fs::path const& clientData, std::string const& locale)
        {
            return clientData / locale / ("patch-" + locale + "-T.MPQ");
        }

        fs::path With(fs::path path, char const* suffix)
        {
            path += suffix;
            return path;
        }

        // The archives read after this one (the letters after T, the language's too) and this one:
        // left out of what it's made from, as it's read before them (they're made from the game with
        // it in, and a patch made from theirs would be made again after each of them, and they after it).
        std::vector<std::string> Skip(std::string const& locale)
        {
            std::string loc = Lower(locale);
            std::vector<std::string> skip = { "patch-" + loc + "-t.mpq" };
            for (char c = 'u'; c <= 'z'; ++c)
            {
                skip.push_back(std::string("patch-") + c + ".mpq");
                skip.push_back("patch-" + loc + "-" + c + ".mpq");
            }
            return skip;
        }

        struct Hls { double h, l, s; };

        Hls ToHls(double r, double g, double b)
        {
            double mx = std::max({ r, g, b }), mn = std::min({ r, g, b });
            double l = (mx + mn) / 2, h = 0, s = 0;
            if (mx > mn)
            {
                double d = mx - mn;
                s = l <= 0.5 ? d / (mx + mn) : d / (2 - mx - mn);
                if (mx == r)
                    h = (g - b) / d / 6;
                else if (mx == g)
                    h = ((b - r) / d + 2) / 6;
                else
                    h = ((r - g) / d + 4) / 6;
                if (h < 0)
                    h += 1;
            }
            return { h, l, s };
        }

        double HueValue(double m1, double m2, double h)
        {
            h = std::fmod(h, 1.0);
            if (h < 0)
                h += 1;
            if (h < 1.0 / 6)
                return m1 + (m2 - m1) * h * 6;
            if (h < 0.5)
                return m2;
            if (h < 2.0 / 3)
                return m1 + (m2 - m1) * (2.0 / 3 - h) * 6;
            return m1;
        }

        void FromHls(Hls c, double& r, double& g, double& b)
        {
            if (c.s <= 0)
            {
                r = g = b = c.l;
                return;
            }
            double m2 = c.l <= 0.5 ? c.l * (1 + c.s) : c.l + c.s - c.l * c.s, m1 = 2 * c.l - m2;
            r = HueValue(m1, m2, c.h + 1.0 / 3);
            g = HueValue(m1, m2, c.h);
            b = HueValue(m1, m2, c.h - 1.0 / 3);
        }

        uint8_t Byte(double v) { return uint8_t(std::lround(std::clamp(v, 0.0, 1.0) * 255)); }

        void RecolourRgb(uint8_t& r, uint8_t& g, uint8_t& b, double hue, double saturation, double lightness)
        {
            Hls c = ToHls(r / 255.0, g / 255.0, b / 255.0);
            if (hue >= 0)
                c.h = hue;
            c.s = std::clamp(c.s * saturation, 0.0, 1.0);
            c.l = std::clamp(c.l * lightness, 0.0, 1.0);
            double nr, ng, nb;
            FromHls(c, nr, ng, nb);
            r = Byte(nr);
            g = Byte(ng);
            b = Byte(nb);
        }

        // [image] resampled to [w] x [h] (bilinear).
        Image Resized(Image const& in, uint32_t w, uint32_t h)
        {
            Image out;
            out.width = w;
            out.height = h;
            out.rgba.assign(size_t(w) * h * 4, 255);
            for (uint32_t y = 0; y < h; ++y)
                for (uint32_t x = 0; x < w; ++x)
                {
                    double sx = (x + 0.5) * in.width / w - 0.5, sy = (y + 0.5) * in.height / h - 0.5;
                    int x0 = int(std::floor(sx)), y0 = int(std::floor(sy));
                    double fx = sx - x0, fy = sy - y0;
                    auto at = [&](int px, int py, int c) -> double
                    {
                        px = std::clamp(px, 0, int(in.width) - 1);
                        py = std::clamp(py, 0, int(in.height) - 1);
                        return in.rgba[(size_t(py) * in.width + px) * 4 + c];
                    };
                    for (int c = 0; c < 4; ++c)
                    {
                        double v = at(x0, y0, c) * (1 - fx) * (1 - fy) + at(x0 + 1, y0, c) * fx * (1 - fy)
                            + at(x0, y0 + 1, c) * (1 - fx) * fy + at(x0 + 1, y0 + 1, c) * fx * fy;
                        out.rgba[(size_t(y) * w + x) * 4 + c] = uint8_t(std::clamp(v + 0.5, 0.0, 255.0));
                    }
                }
            return out;
        }

        // [in] a mip level down (each 2 x 2 pixels averaged; a side of 1 kept).
        Image Halved(Image const& in)
        {
            Image out;
            out.width = std::max(1u, in.width / 2);
            out.height = std::max(1u, in.height / 2);
            out.rgba.resize(size_t(out.width) * out.height * 4);
            for (uint32_t y = 0; y < out.height; ++y)
                for (uint32_t x = 0; x < out.width; ++x)
                    for (int c = 0; c < 4; ++c)
                    {
                        uint32_t sum = 0, n = 0;
                        for (uint32_t dy = 0; dy < 2; ++dy)
                            for (uint32_t dx = 0; dx < 2; ++dx)
                            {
                                uint32_t sx = std::min(in.width - 1, x * 2 + dx), sy = std::min(in.height - 1, y * 2 + dy);
                                sum += in.rgba[(size_t(sy) * in.width + sx) * 4 + c];
                                ++n;
                            }
                        out.rgba[(size_t(y) * out.width + x) * 4 + c] = uint8_t((sum + n / 2) / n);
                    }
            return out;
        }

        bool Translucent(Image const& image)
        {
            for (size_t i = 3; i < image.rgba.size(); i += 4)
                if (image.rgba[i] != 255)
                    return true;
            return false;
        }

        std::string StringOf(Dbc const& dbc, uint32_t at)
        {
            if (at >= dbc.strings.size())
                return "";
            auto begin = dbc.strings.begin() + at;
            return std::string(begin, std::find(begin, dbc.strings.end(), uint8_t(0)));
        }

        uint32_t BlpWidth(Bytes const& blp)
        {
            return blp.size() >= 20 && std::memcmp(blp.data(), "BLP2", 4) == 0 ? U32(blp, 12) : 0;
        }
    }

    Bytes EncodePalette(Image const& image, bool alpha)
    {
        // The palette: the image's colours split by median cut (the box with the widest spread of a
        // colour channel halved at its median) into at most 256, each its box's average.
        size_t count = size_t(image.width) * image.height;
        std::vector<uint32_t> pixels(count);
        for (size_t i = 0; i < count; ++i)
            pixels[i] = uint32_t(image.rgba[i * 4]) | uint32_t(image.rgba[i * 4 + 1]) << 8 | uint32_t(image.rgba[i * 4 + 2]) << 16;
        struct Box { size_t begin, end; int channel; int spread; };
        auto measure = [&](Box& box)
        {
            int lo[3] = { 255, 255, 255 }, hi[3] = { 0, 0, 0 };
            for (size_t i = box.begin; i < box.end; ++i)
                for (int c = 0; c < 3; ++c)
                {
                    int v = (pixels[i] >> (8 * c)) & 0xFF;
                    lo[c] = std::min(lo[c], v);
                    hi[c] = std::max(hi[c], v);
                }
            box.channel = 0;
            for (int c = 1; c < 3; ++c)
                if (hi[c] - lo[c] > hi[box.channel] - lo[box.channel])
                    box.channel = c;
            box.spread = box.end - box.begin > 1 ? hi[box.channel] - lo[box.channel] : 0;
        };
        std::vector<Box> boxes;
        if (count)
        {
            boxes.push_back({ 0, count, 0, 0 });
            measure(boxes[0]);
        }
        while (boxes.size() < 256)
        {
            size_t widest = boxes.size();
            for (size_t b = 0; b < boxes.size(); ++b)
                if (boxes[b].spread > 0 && (widest == boxes.size() || boxes[b].spread > boxes[widest].spread))
                    widest = b;
            if (widest == boxes.size())
                break;
            Box box = boxes[widest];
            int shift = 8 * box.channel;
            size_t middle = box.begin + (box.end - box.begin) / 2;
            std::nth_element(pixels.begin() + box.begin, pixels.begin() + middle, pixels.begin() + box.end,
                [shift](uint32_t a, uint32_t b) { return ((a >> shift) & 0xFF) < ((b >> shift) & 0xFF); });
            Box first = { box.begin, middle, 0, 0 }, second = { middle, box.end, 0, 0 };
            measure(first);
            measure(second);
            boxes[widest] = first;
            boxes.push_back(second);
        }
        std::vector<std::array<int, 3>> palette;
        for (auto const& box : boxes)
        {
            uint64_t sum[3] = { 0, 0, 0 };
            for (size_t i = box.begin; i < box.end; ++i)
                for (int c = 0; c < 3; ++c)
                    sum[c] += (pixels[i] >> (8 * c)) & 0xFF;
            size_t n = std::max<size_t>(1, box.end - box.begin);
            palette.push_back({ int(sum[0] / n), int(sum[1] / n), int(sum[2] / n) });
        }
        if (palette.empty())
            palette.push_back({ 0, 0, 0 });

        auto nearest = [&](uint8_t const* rgb)
        {
            int best = 0, bestDistance = INT32_MAX;
            for (size_t p = 0; p < palette.size(); ++p)
            {
                int dr = rgb[0] - palette[p][0], dg = rgb[1] - palette[p][1], db = rgb[2] - palette[p][2];
                int d = dr * dr + dg * dg + db * db;
                if (d < bestDistance)
                {
                    bestDistance = d;
                    best = int(p);
                    if (d == 0)
                        break;
                }
            }
            return uint8_t(best);
        };

        // Every mip level (the game's character textures have them all), each as indices into the
        // palette then its alpha, 8 bits a pixel.
        std::vector<Bytes> levels;
        Image level = image;
        while (true)
        {
            Bytes data(size_t(level.width) * level.height * (alpha ? 2 : 1));
            size_t pixelsHere = size_t(level.width) * level.height;
            for (size_t i = 0; i < pixelsHere; ++i)
            {
                data[i] = nearest(&level.rgba[i * 4]);
                if (alpha)
                    data[pixelsHere + i] = level.rgba[i * 4 + 3];
            }
            levels.push_back(std::move(data));
            if ((level.width == 1 && level.height == 1) || levels.size() == 16)
                break;
            level = Halved(level);
        }

        Bytes out(148 + 1024);
        std::memcpy(out.data(), "BLP2", 4);
        Put32(out, 4, 1);
        out[8] = 1;                                                 // a palette
        out[9] = alpha ? 8 : 0;                                     // alpha bits
        out[10] = alpha ? 8 : 0;
        out[11] = 1;                                                // mip levels
        Put32(out, 12, image.width);
        Put32(out, 16, image.height);
        uint32_t at = 148 + 1024;
        for (size_t i = 0; i < levels.size(); ++i)
        {
            Put32(out, 20 + i * 4, at);
            Put32(out, 84 + i * 4, uint32_t(levels[i].size()));
            at += uint32_t(levels[i].size());
        }
        for (size_t p = 0; p < palette.size(); ++p)
        {
            out[148 + p * 4] = uint8_t(palette[p][2]);              // B, G, R, A
            out[148 + p * 4 + 1] = uint8_t(palette[p][1]);
            out[148 + p * 4 + 2] = uint8_t(palette[p][0]);
            out[148 + p * 4 + 3] = 255;
        }
        for (auto const& data : levels)
            out.insert(out.end(), data.begin(), data.end());
        return out;
    }

    namespace
    {
        // A texture the game draws into a character's composed texture (a face, underwear), made as the
        // game's own are: palettized, and sized for a skin [skinWidth] wide (the composed texture's size:
        // the HD looks' 512, the game's 256): [w] x [h] of a 256 one, scaled. [recipe]: its colours
        // changed first (a naga colour of the game's that's made from another), or not.
        Bytes Composited(Image const& source, uint32_t skinWidth, uint32_t w, uint32_t h, double const* recipe)
        {
            Image image = Resized(source, w * skinWidth / 256, h * skinWidth / 256);
            if (recipe)
                for (size_t i = 0; i + 3 < image.rgba.size(); i += 4)
                    RecolourRgb(image.rgba[i], image.rgba[i + 1], image.rgba[i + 2], recipe[0], recipe[1], recipe[2]);
            return EncodePalette(image, Translucent(image));
        }

        // The races' icons for the character creation screen, a sheet of their own (one column:
        // Murloc men, Naga men, Murloc women, Naga women; the game's sheet has no column left: the
        // allied races and the Skyborne have its free ones), from the game's own icons: the murloc
        // head, the murloc oracle, Warlord Kalithresh and Lady Vashj. [round]: each one cut round.
        bool Icons(Game const& game, bool round, Bytes& out, std::vector<std::string>& problems)
        {
            std::vector<std::vector<char const*>> cells = {
                { "Interface\\Icons\\INV_Misc_Head_Murloc_01.blp" },
                { "Interface\\Icons\\Achievement_Boss_WarlordKalithresh.blp", "Interface\\Icons\\Achievement_Boss_LadyVashj.blp" },
                { "Interface\\Icons\\achievement_reputation_murlocoracle.blp", "Interface\\Icons\\INV_Misc_Head_Murloc_01.blp" },
                { "Interface\\Icons\\Achievement_Boss_LadyVashj.blp" },
            };
            constexpr uint32_t CELL = 64;
            Image sheet;
            sheet.width = CELL;
            sheet.height = CELL * 4;
            sheet.rgba.assign(size_t(sheet.width) * sheet.height * 4, 0);
            for (size_t row = 0; row < cells.size(); ++row)
            {
                Image icon;
                bool found = false;
                for (char const* name : cells[row])
                {
                    Bytes data;
                    if (game.Read(name, data) && Skyborne::Decode(data, icon))
                    {
                        found = true;
                        break;
                    }
                }
                if (!found)
                {
                    problems.push_back("no icon for the creation screen's row " + std::to_string(row));
                    continue;
                }
                Image cell = Resized(icon, CELL, CELL);
                for (uint32_t y = 0; y < CELL; ++y)
                    for (uint32_t x = 0; x < CELL; ++x)
                    {
                        uint8_t* p = &sheet.rgba[(size_t(row * CELL + y) * CELL + x) * 4];
                        std::memcpy(p, &cell.rgba[(size_t(y) * CELL + x) * 4], 4);
                        p[3] = 255;
                        if (round)
                        {
                            double dx = x + 0.5 - CELL / 2.0, dy = y + 0.5 - CELL / 2.0;
                            double edge = CELL / 2.0 - std::sqrt(dx * dx + dy * dy);
                            p[3] = Byte(std::clamp(edge, 0.0, 1.0));
                        }
                    }
            }
            out = Skyborne::EncodeDxt5(sheet);
            return true;
        }

        // A WAV's samples as numbers from -1 to 1, mono, at 22050 Hz.
        bool Samples(Bytes const& wav, std::vector<float>& out)
        {
            if (wav.size() < 12 || std::memcmp(wav.data(), "RIFF", 4) != 0 || std::memcmp(&wav[8], "WAVE", 4) != 0)
                return false;
            uint16_t format = 0, channels = 0, bits = 0;
            uint32_t rate = 0;
            size_t data = 0, size = 0;
            for (size_t at = 12; at + 8 <= wav.size();)
            {
                uint32_t length = U32(wav, at + 4);
                if (std::memcmp(&wav[at], "fmt ", 4) == 0 && at + 24 <= wav.size())
                {
                    std::memcpy(&format, &wav[at + 8], 2);
                    std::memcpy(&channels, &wav[at + 10], 2);
                    rate = U32(wav, at + 12);
                    std::memcpy(&bits, &wav[at + 22], 2);
                }
                else if (std::memcmp(&wav[at], "data", 4) == 0)
                {
                    data = at + 8;
                    size = std::min<size_t>(length, wav.size() - data);
                }
                at += 8 + length + (length & 1);
            }
            if (format != 1 || channels == 0 || rate == 0 || (bits != 16 && bits != 8) || data == 0)
                return false;
            size_t frame = channels * bits / 8, frames = size / frame;
            std::vector<float> mono(frames);
            for (size_t i = 0; i < frames; ++i)
            {
                double sum = 0;
                for (uint16_t c = 0; c < channels; ++c)
                {
                    size_t at = data + i * frame + c * bits / 8;
                    if (bits == 16)
                    {
                        int16_t s;
                        std::memcpy(&s, &wav[at], 2);
                        sum += s / 32768.0;
                    }
                    else
                        sum += (wav[at] - 128) / 128.0;
                }
                mono[i] = float(sum / channels);
            }
            double step = double(rate) / 22050.0;
            out.assign(size_t(frames / step), 0.0f);
            for (size_t i = 0; i < out.size(); ++i)
            {
                double s = i * step;
                size_t i0 = size_t(s);
                double f = s - i0;
                float a = mono[std::min(i0, frames - 1)], b = mono[std::min(i0 + 1, frames - 1)];
                out[i] = float(a * (1 - f) + b * f);
            }
            return true;
        }

        std::vector<std::vector<std::string>> Lines(std::string const& text)
        {
            std::vector<std::vector<std::string>> out;
            std::istringstream in(text);
            std::string line;
            while (std::getline(in, line))
            {
                if (!line.empty() && line.back() == '\r')
                    line.pop_back();
                if (line.empty() || line[0] == '#')
                    continue;
                std::vector<std::string> parts;
                std::istringstream fields(line);
                std::string part;
                while (std::getline(fields, part, '\t'))
                    parts.push_back(part);
                out.push_back(parts);
            }
            return out;
        }

        // A list of files in a game folder of the toc's kind: [files] added after its own, once.
        Bytes WithFiles(Bytes const& toc, std::vector<std::string> const& files)
        {
            std::string text(toc.begin(), toc.end());
            for (auto const& file : files)
                if (text.find(file) == std::string::npos)
                {
                    if (!text.empty() && text.back() != '\n')
                        text += "\r\n";
                    text += file + "\r\n";
                }
            return Bytes(text.begin(), text.end());
        }
    }

    bool Recolour(Bytes& blp, double hue, double saturation, double lightness)
    {
        if (blp.size() < 148 + 1024 || std::memcmp(blp.data(), "BLP2", 4) != 0)
            return false;
        if (blp[8] == 1)                                            // a palette: its colours
        {
            for (int i = 0; i < 256; ++i)
            {
                uint8_t* bgra = &blp[148 + i * 4];
                RecolourRgb(bgra[2], bgra[1], bgra[0], hue, saturation, lightness);
            }
            return true;
        }
        Image image;
        if (!Skyborne::Decode(blp, image))
            return false;
        for (size_t i = 0; i + 3 < image.rgba.size(); i += 4)
            RecolourRgb(image.rgba[i], image.rgba[i + 1], image.rgba[i + 2], hue, saturation, lightness);
        blp = EncodePalette(image, Translucent(image));             // as the game's character textures are
        return true;
    }

    Bytes MixNarration(std::vector<std::pair<uint32_t, Bytes>> const& parts)
    {
        std::vector<float> mix;
        for (auto const& [ms, wav] : parts)
        {
            std::vector<float> samples;
            if (!Samples(wav, samples))
                continue;
            size_t at = size_t(ms) * 22050 / 1000;
            if (mix.size() < at + samples.size())
                mix.resize(at + samples.size(), 0.0f);
            for (size_t i = 0; i < samples.size(); ++i)
                mix[at + i] += samples[i];
        }
        Bytes out(44 + mix.size() * 2);
        std::memcpy(&out[0], "RIFF", 4);
        Put32(out, 4, uint32_t(out.size() - 8));
        std::memcpy(&out[8], "WAVEfmt ", 8);
        Put32(out, 16, 16);
        uint16_t pcm = 1, mono = 1, align = 2, bits = 16;
        std::memcpy(&out[20], &pcm, 2);
        std::memcpy(&out[22], &mono, 2);
        Put32(out, 24, 22050);
        Put32(out, 28, 44100);
        std::memcpy(&out[32], &align, 2);
        std::memcpy(&out[34], &bits, 2);
        std::memcpy(&out[36], "data", 4);
        Put32(out, 40, uint32_t(mix.size() * 2));
        for (size_t i = 0; i < mix.size(); ++i)
        {
            int16_t s = int16_t(std::lround(std::clamp(mix[i], -1.0f, 1.0f) * 32767));
            std::memcpy(&out[44 + i * 2], &s, 2);
        }
        return out;
    }

    void RemovePatch(fs::path const& clientData, Log const& log)
    {
        std::string locale = Locale(clientData);
        if (locale.empty())
            return;
        fs::path patch = PatchPath(clientData, locale);
        std::error_code error;
        if (!fs::exists(With(patch, MARK_SUFFIX), error))
            return;
        fs::remove(patch, error);
        fs::remove(With(patch, ASIDE_SUFFIX), error);
        fs::remove(With(patch, MARK_SUFFIX), error);
        log("The races' patch is out of the game");
    }

    void SetPatchAside(fs::path const& clientData, Log const& log)
    {
        std::string locale = Locale(clientData);
        if (locale.empty())
            return;
        fs::path patch = PatchPath(clientData, locale);
        std::error_code error;
        if (!fs::exists(With(patch, MARK_SUFFIX), error) || !fs::exists(patch, error))
            return;
        fs::remove(With(patch, ASIDE_SUFFIX), error);
        fs::rename(patch, With(patch, ASIDE_SUFFIX), error);
        if (error)
            log("The races' patch couldn't be set aside: " + error.message());
        else
            log("The races' patch is out of the game, kept beside it for a realm with the races");
    }

    void PutCameras(fs::path const& moduleData, fs::path const& serverData, Log const& log)
    {
        std::error_code error;
        fs::path folder = serverData / "Cameras";
        fs::create_directories(folder, error);
        for (char const* name : { "FlybyMurloc.m2", "FlybyNaga.m2" })
        {
            Bytes ours = ReadFile(moduleData / "Cameras" / name);
            if (ours.empty())
            {
                log(std::string("The intro's camera ") + name + " isn't in the module");
                continue;
            }
            if (ReadFile(folder / name) == ours)
                continue;
            std::ofstream out(folder / name, std::ios::binary | std::ios::trunc);
            out.write(reinterpret_cast<char const*>(ours.data()), std::streamsize(ours.size()));
            if (!out)
                log("Couldn't write " + (folder / name).string());
        }
    }

    bool UpdatePatch(fs::path const& moduleData, fs::path const& clientData, Log const& log)
    {
        std::string locale = Locale(clientData);
        if (locale.empty())
        {
            log("No game language folder in " + clientData.string() + ": the races' patch isn't made");
            return false;
        }
        std::error_code error;
        fs::path patch = PatchPath(clientData, locale), mark = With(patch, MARK_SUFFIX);
        bool ours = fs::exists(mark, error);
        if (!ours && fs::exists(patch, error))
        {
            log(patch.string() + " isn't this module's: left alone, so the game won't know the races");
            return false;
        }
        if (ours && !fs::exists(patch, error) && fs::exists(With(patch, ASIDE_SUFFIX), error))
            fs::rename(With(patch, ASIDE_SUFFIX), patch, error);
        auto start = std::chrono::steady_clock::now();
        Game game(clientData, Skip(locale));
        if (game.archives.empty())
        {
            log("No game in " + clientData.string());
            return false;
        }

        // What it's made from: the module's files and the game's archives.
        Stamp stamp;
        stamp.Add(VERSION);
        stamp.Add(game.Describe());
        std::vector<fs::path> moduleFiles;
        for (auto it = fs::recursive_directory_iterator(moduleData, error); !error && it != fs::recursive_directory_iterator(); it.increment(error))
            if (it->is_regular_file(error))
                moduleFiles.push_back(it->path());
        std::sort(moduleFiles.begin(), moduleFiles.end());
        for (auto const& f : moduleFiles)
        {
            stamp.Add(f.lexically_relative(moduleData).generic_string());
            stamp.Add(Text(f));
        }
        // The murlocs' model and textures: made as the server was packaged (tools/murloc_model.py, from
        // the game's files of today) into data/files, their list (murloc.files) written last.
        fs::path packaged = moduleData.parent_path() / "files";
        std::vector<std::string> murlocFiles;
        {
            std::istringstream in(Text(packaged / "murloc.files"));
            std::string name;
            while (std::getline(in, name))
            {
                if (!name.empty() && name.back() == '\r')
                    name.pop_back();
                if (name.empty())
                    continue;
                murlocFiles.push_back(name);
                stamp.Add(name);
                stamp.Add(std::to_string(fs::file_size(packaged / name, error)));
            }
        }
        if (ours && fs::exists(patch, error) && Text(mark) == stamp.Hex() + "\n")
            return true;

        log("Making the races' patch for the game (" + patch.string() + ")");
        fs::path part = With(patch, ".part");
        MpqWriter writer(part);
        size_t tables = 0, files = 0;
        std::vector<std::string> problems;

        // Every texture the patch writes (lower case), so the tables name only textures that are there.
        std::set<std::string> written;
        auto add = [&](std::string const& name, Bytes const& data)
        {
            writer.Add(name, data);
            written.insert(Lower(name));
            ++files;
        };

        // The naga's colours (data/client/looks.txt), then the murlocs' packaged files. What the game draws
        // into a character's composed texture (its skin, faces and underwear) is made as the game's
        // own character textures are, palettized and at the scale of the skin it's drawn over: the
        // game reads only those there (a compressed or missing one was drawn green, and broke the
        // game's memory as the character was made: crash report #110).
        struct Colour { uint32_t source; double recipe[3]; bool changed; };
        std::map<std::string, std::map<uint32_t, Colour>> nagaColours;       // sex, colour
        for (char const* sex : { "Male", "Female" })
            for (uint32_t c = 0; c < 5; ++c)
                nagaColours[sex][c] = { c, { -1, 1, 1 }, false };          // the game's five
        for (auto const& line : Lines(Text(moduleData / "looks.txt")))
        {
            if (line[0] == "naga_colour" && line.size() == 7)
                nagaColours[line[1] == "0" ? "Male" : "Female"][uint32_t(std::stoul(line[2]))]
                    = { uint32_t(std::stoul(line[3])), { std::stod(line[4]), std::stod(line[5]), std::stod(line[6]) }, true };
        }
        for (auto const& [sex, colours] : nagaColours)
        {
            std::string p = "Character\\Naga_\\" + sex + "\\Naga_" + sex;
            for (auto const& [colour, how] : colours)
            {
                char from[8], to[8];
                std::snprintf(from, sizeof(from), "00_%02u", how.source);
                std::snprintf(to, sizeof(to), "00_%02u", colour);
                // The skin and its extra (the fins and face the model draws as they are): the game's,
                // recoloured for a new colour.
                uint32_t skinWidth = 0;
                for (char const* extra : { "", "_Extra" })
                {
                    Bytes texture;
                    std::string name = p + "Skin" + from + extra + ".blp";
                    if (!game.Read(name, texture))
                    {
                        problems.push_back(name + " isn't in the game");
                        continue;
                    }
                    if (!*extra)
                        skinWidth = BlpWidth(texture);
                    if (how.changed || (!*extra && texture.size() > 8 && texture[8] != 1))
                    {
                        if (!Recolour(texture, how.changed ? how.recipe[0] : -1, how.changed ? how.recipe[1] : 1, how.changed ? how.recipe[2] : 1))
                        {
                            problems.push_back(name + " isn't a texture this knows");
                            continue;
                        }
                        add(p + "Skin" + to + extra + ".blp", texture);
                    }
                }
                if (!skinWidth)
                    continue;
                // The faces and underwear (the game's own are a quarter the size and compressed): made
                // the skin's scale, palettized, under the colour's own names.
                for (auto [kind, w, h] : { std::tuple<char const*, uint32_t, uint32_t>{ "FaceLower", 128, 64 }, { "FaceUpper", 128, 32 },
                                           { "NakedPelvisSkin", 128, 64 }, { "NakedTorsoSkin", 128, 64 } })
                {
                    Bytes texture;
                    Image image;
                    std::string name = p + kind + from + ".blp";
                    if (!game.Read(name, texture) || !Skyborne::Decode(texture, image) || !image.width || !image.height)
                    {
                        problems.push_back(name + " isn't in the game");
                        continue;
                    }
                    add(p + kind + to + ".blp", Composited(image, skinWidth, w, h, how.changed ? how.recipe : nullptr));
                }
            }
        }
        if (murlocFiles.empty())
            problems.push_back("the murlocs' model and textures aren't in " + packaged.string()
                + " (no murloc.files: tools/murloc_model.py makes them as the server is packaged): the game won't draw them");
        for (auto const& name : murlocFiles)
        {
            Bytes data = ReadFile(packaged / name);
            if (data.empty())
            {
                problems.push_back(name + " isn't in " + packaged.string());
                continue;
            }
            std::string inGame = name;
            std::replace(inGame.begin(), inGame.end(), '/', '\\');
            add(inGame, data);
        }

        // Tables. The races' CharSections rows name only textures that are there (in the game or this
        // patch): the game's own rows for the naga name a hair and a facial hair texture it hasn't.
        for (auto it = fs::directory_iterator(moduleData / "dbc", error); !error && it != fs::directory_iterator(); it.increment(error))
        {
            if (it->path().extension() != ".txt")
                continue;
            std::string name = it->path().stem().string();
            Bytes data;
            Dbc dbc;
            if (!game.Read("DBFilesClient\\" + name + ".dbc", data) || !dbc.Parse(data))
            {
                problems.push_back(name + ".dbc isn't in the game");
                continue;
            }
            std::string problem = Skyborne::ApplyChanges(dbc, Text(it->path()));
            if (!problem.empty())
            {
                problems.push_back(name + ".dbc: " + problem);
                continue;
            }
            if (name == "CharSections")
            {
                uint32_t empty = dbc.String("");
                for (size_t r = 0; r < dbc.records.size(); ++r)
                {
                    uint32_t race = dbc.Get(r, 1);
                    if (race != 19 && race != 20)
                        continue;
                    for (uint32_t field : { 4u, 5u, 6u })
                    {
                        std::string texture = StringOf(dbc, dbc.Get(r, field));
                        if (texture.empty() || written.count(Lower(texture)) || game.Has(texture))
                            continue;
                        problems.push_back(texture + " isn't in the game: left out of its look");
                        dbc.Set(r, field, empty);
                    }
                }
            }
            Skyborne::SortCharacterTable(dbc, name);
            writer.Add("DBFilesClient\\" + name + ".dbc", dbc.Write());
            ++tables;
        }

        // The character creation screen: the races' icons, the screen's own file list with theirs; the
        // in-game interface's, for add-ons with their own tables of the races (Questie's).
        for (bool round : { false, true })
        {
            Bytes sheet;
            if (Icons(game, round, sheet, problems))
                writer.Add(std::string("Interface\\Glues\\CharacterCreate\\UI-CharacterCreate-MurlocNaga") + (round ? "Round" : "") + ".blp", sheet);
        }
        Bytes toc;
        if (game.Read("Interface\\GlueXML\\GlueXML.toc", toc))
        {
            writer.Add("Interface\\GlueXML\\GlueXML.toc", WithFiles(toc, { "MurlocNagaCreate.xml", "MurlocNagaCreate.lua" }));
            for (char const* name : { "MurlocNagaCreate.xml", "MurlocNagaCreate.lua" })
                writer.Add(std::string("Interface\\GlueXML\\") + name, ReadFile(moduleData / "Interface" / "GlueXML" / name));
        }
        else
            problems.push_back("GlueXML.toc isn't in the game: the character screen won't show them");
        Bytes frameToc;
        if (game.Read("Interface\\FrameXML\\FrameXML.toc", frameToc))
        {
            writer.Add("Interface\\FrameXML\\FrameXML.toc", WithFiles(frameToc, { "MurlocNagaAddons.lua" }));
            writer.Add("Interface\\FrameXML\\MurlocNagaAddons.lua", ReadFile(moduleData / "Interface" / "FrameXML" / "MurlocNagaAddons.lua"));
        }
        else
            problems.push_back("FrameXML.toc isn't in the game: add-ons such as Questie won't know the races");

        // The intros: their cameras (tools/cameras.py) and their narrations, mixed from the game's sounds.
        for (char const* name : { "FlybyMurloc.m2", "FlybyMurloc00.skin", "FlybyNaga.m2", "FlybyNaga00.skin" })
        {
            Bytes data = ReadFile(moduleData / "Cameras" / name);
            if (data.empty())
                problems.push_back(std::string("the intro's ") + name + " isn't in the module");
            else
                writer.Add(std::string("Cameras\\") + name, data);
        }
        std::map<std::string, std::vector<std::pair<uint32_t, Bytes>>> narrations;
        for (auto const& line : Lines(Text(moduleData / "narration.txt")))
        {
            if (line.size() != 3)
                continue;
            Bytes sound;
            if (game.Read(line[2], sound))
                narrations[line[0]].emplace_back(uint32_t(std::stoul(line[1])), sound);
            else
                problems.push_back(line[2] + " isn't in the game");
        }
        for (auto const& [race, parts] : narrations)
        {
            writer.Add("Sound\\CinematicVoices\\" + race + "Narration.wav", MixNarration(parts));
            ++files;
        }

        if (!writer.Finish())
        {
            fs::remove(part, error);
            log("Couldn't write " + part.string());
            return false;
        }
        fs::rename(part, patch, error);
        if (error)
        {
            fs::remove(part, error);
            log("Couldn't put " + patch.string() + " in place: " + error.message());
            return false;
        }
        {
            std::ofstream out(mark, std::ios::trunc);
            out << stamp.Hex() << "\n";
        }
        for (auto const& p : problems)
            log("  " + p);
        auto seconds = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - start).count();
        log("The races' patch is in the game: " + std::to_string(tables) + " tables, " + std::to_string(files) + " files ("
            + std::to_string(seconds) + " s)");
        return true;
    }
}
