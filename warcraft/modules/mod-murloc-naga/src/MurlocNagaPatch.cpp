// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * The Murlocs' and the Naga's patch for the game (mod-murloc-naga). The game needs its tables to
 * know the two races (ChrRaces, CharSections, CharHairGeosets... with their rows: the module's
 * data/client/dbc, made by tools/build_data.py), the murlocs' character model and skins, the
 * naga's new skin colours, their place on the character creation screen, their intros (the
 * cameras, the narrations) and their homes' things (GameObjectDisplayInfo).
 *
 * All of it is made here from the client's own files (WOW_CLIENT_DIR: an HD look's, when one is
 * in), so nothing of the game's is in this repository or the server package: the murloc's model
 * is the game's murloc (Creature\Murloc\Murloc.m2) made a character's; its skins the game's
 * murloc skins, moved where the model now reads them; the naga's colours the game's naga skins
 * recoloured; the narrations the game's own sounds, mixed (Lady Vashj's words for the naga, the
 * murlocs' own voices for the murlocs).
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
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <sstream>

namespace MurlocNaga
{
    using Skyborne::Dbc;
    using Skyborne::Game;
    using Skyborne::Image;
    using Skyborne::MpqWriter;

    namespace
    {
        constexpr char const* VERSION = "murloc-naga-1";
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

        Image Rows(Image const& in, uint32_t from, uint32_t count)
        {
            Image out;
            out.width = in.width;
            out.height = count;
            out.rgba.assign(in.rgba.begin() + size_t(from) * in.width * 4, in.rgba.begin() + size_t(from + count) * in.width * 4);
            return out;
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

        // The murloc: the game's model made a character's, for each sex (the same body), with its
        // skins (00.skin...) and animations kept outside the model, by the character's names.
        size_t MurlocModel(Game const& game, std::string const& source, MpqWriter& writer, std::vector<std::string>& problems)
        {
            Bytes m2;
            if (!game.Read(source, m2))
            {
                problems.push_back(source + " isn't in the game: no murloc model");
                return 0;
            }
            Bytes model = m2;
            if (!CharacterModel(model))
            {
                problems.push_back(source + " isn't a model this knows (3.3.5's, with a creature's skin): no murloc model");
                return 0;
            }
            std::string base = source.substr(0, source.size() - 3);           // Creature\Murloc\Murloc
            size_t made = 0;
            for (char const* sex : { "Male", "Female" })
            {
                std::string ours = std::string("Character\\Murloc\\") + sex + "\\Murloc" + sex;
                writer.Add(ours + ".m2", model);
                ++made;
                for (uint32_t view = 0; view < U32(m2, 0x44) && view < 4; ++view)
                {
                    char suffix[8];
                    std::snprintf(suffix, sizeof(suffix), "%02u.skin", view);
                    Bytes skin;
                    if (game.Read(base + suffix, skin))
                    {
                        writer.Add(ours + suffix, skin);
                        ++made;
                    }
                    else
                        problems.push_back(base + suffix + " isn't in the game");
                }
                uint32_t sequences = U32(m2, 0x1C), at = U32(m2, 0x20);
                for (uint32_t i = 0; i < sequences && at + (i + 1) * 64 <= m2.size(); ++i)
                {
                    uint16_t id, variation;
                    std::memcpy(&id, &m2[at + i * 64], 2);
                    std::memcpy(&variation, &m2[at + i * 64 + 2], 2);
                    if (U32(m2, at + i * 64 + 12) & 0x20)
                        continue;                                          // (its keys in the model)
                    char suffix[24];
                    std::snprintf(suffix, sizeof(suffix), "%04u-%02u.anim", unsigned(id), unsigned(variation));
                    Bytes anim;
                    if (game.Read(base + suffix, anim))
                    {
                        writer.Add(ours + suffix, anim);
                        ++made;
                    }
                }
            }
            return made;
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

    bool CharacterModel(Bytes& m2)
    {
        if (m2.size() < 0x130 || std::memcmp(m2.data(), "MD20", 4) != 0 || U32(m2, 4) != 264)
            return false;
        uint32_t textures = U32(m2, 0x50), texturesAt = U32(m2, 0x54);
        if (textures == 0 || textures > 64 || texturesAt + textures * 16 > m2.size())
            return false;
        bool skin = false;
        for (uint32_t i = 0; i < textures && !skin; ++i)
        {
            uint32_t type = U32(m2, texturesAt + i * 16);
            if (type == 11 || type == 12 || type == 13 || type == 1)
            {
                Put32(m2, texturesAt + i * 16, 1);                  // the character's composed texture
                skin = true;
            }
        }
        if (!skin)
            return false;
        uint32_t vertices = U32(m2, 0x3C), verticesAt = U32(m2, 0x40);
        if (verticesAt + uint64_t(vertices) * 48 > m2.size())
            return false;
        // The face region, a texel in from its edges (so the game's filtering never reaches the
        // regions armour is painted into).
        constexpr float U0 = 0.004f, U1 = 0.496f, V0 = 0.629f, V1 = 0.996f;
        for (uint32_t i = 0; i < vertices; ++i)
        {
            float uv[2];
            std::memcpy(uv, &m2[verticesAt + i * 48 + 32], 8);
            uv[0] = U0 + std::clamp(uv[0], 0.0f, 1.0f) * (U1 - U0);
            uv[1] = V0 + std::clamp(uv[1], 0.0f, 1.0f) * (V1 - V0);
            std::memcpy(&m2[verticesAt + i * 48 + 32], uv, 8);
        }
        return true;
    }

    bool FaceTextures(Bytes const& skin, Bytes& upper, Bytes& lower)
    {
        Image image;
        if (!Skyborne::Decode(skin, image) || image.width == 0 || image.height == 0)
            return false;
        Image face = Resized(image, 256, 192);
        for (size_t i = 3; i < face.rgba.size(); i += 4)
            face.rgba[i] = 255;                                     // opaque: nothing of the skin under it shows
        upper = Skyborne::EncodeDxt5(Rows(face, 0, 64));
        lower = Skyborne::EncodeDxt5(Rows(face, 64, 128));
        return true;
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
        blp = Skyborne::EncodeDxt5(image);
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
        if (ours && fs::exists(patch, error) && Text(mark) == stamp.Hex() + "\n")
            return true;

        log("Making the races' patch for the game (" + patch.string() + ")");
        fs::path part = With(patch, ".part");
        MpqWriter writer(part);
        size_t tables = 0, files = 0;
        std::vector<std::string> problems;

        // Tables.
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
            Skyborne::SortCharacterTable(dbc, name);
            writer.Add("DBFilesClient\\" + name + ".dbc", dbc.Write());
            ++tables;
        }

        // The murloc's model and skins, the naga's new colours (data/client/looks.txt).
        std::string murlocModel;
        std::map<uint32_t, std::string> murlocSkins;
        for (auto const& line : Lines(Text(moduleData / "looks.txt")))
        {
            if (line[0] == "murloc_model" && line.size() == 2)
                murlocModel = line[1];
            else if (line[0] == "murloc_skin" && line.size() == 3)
                murlocSkins[uint32_t(std::stoul(line[1]))] = line[2];
            else if (line[0] == "naga_colour" && line.size() == 7)
            {
                char const* sex = line[1] == "0" ? "Male" : "Female";
                uint32_t colour = std::stoul(line[2]), source = std::stoul(line[3]);
                double hue = std::stod(line[4]), saturation = std::stod(line[5]), lightness = std::stod(line[6]);
                std::string p = std::string("Character\\Naga_\\") + sex + "\\Naga_" + sex;
                char from[8], to[8];
                std::snprintf(from, sizeof(from), "00_%02u", source);
                std::snprintf(to, sizeof(to), "00_%02u", colour);
                for (char const* kind : { "Skin", "FaceLower", "FaceUpper", "NakedPelvisSkin", "NakedTorsoSkin" })
                    for (char const* extra : { "", "_Extra" })
                    {
                        if (*extra && std::strcmp(kind, "Skin") != 0)
                            continue;
                        Bytes texture;
                        std::string name = p + kind + from + extra + ".blp";
                        if (!game.Read(name, texture))
                        {
                            problems.push_back(name + " isn't in the game");
                            continue;
                        }
                        if (!Recolour(texture, hue, saturation, lightness))
                        {
                            problems.push_back(name + " isn't a texture this knows");
                            continue;
                        }
                        writer.Add(p + kind + to + extra + ".blp", texture);
                        ++files;
                    }
            }
        }
        if (!murlocModel.empty() && MurlocModel(game, murlocModel, writer, problems))
        {
            files += 1;
            Bytes first;
            for (auto const& [colour, source] : murlocSkins)
            {
                Bytes skin;
                if (!game.Read(source, skin))
                {
                    // (a colour the client hasn't: the first's, so every colour on the slider draws)
                    problems.push_back(source + " isn't in the game" + (first.empty() ? "" : ": its colour is the first's"));
                    if (first.empty())
                        continue;
                    skin = first;
                }
                if (first.empty())
                    first = skin;
                Bytes upper, lower;
                if (!FaceTextures(skin, upper, lower))
                {
                    problems.push_back(source + " isn't a texture this knows");
                    continue;
                }
                char suffix[16];
                std::snprintf(suffix, sizeof(suffix), "00_%02u.blp", colour);
                for (char const* sex : { "Male", "Female" })
                {
                    std::string p = std::string("Character\\Murloc\\") + sex + "\\Murloc" + sex;
                    writer.Add(p + "FaceUpper" + suffix, upper);
                    writer.Add(p + "FaceLower" + suffix, lower);
                    files += 2;
                }
            }
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
