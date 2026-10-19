// SPDX-License-Identifier: GPL-3.0-or-later
#include "SkyborneShade.h"
#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstring>
#include <fstream>
#include <limits>
#include <sstream>
#include <zlib.h>

namespace Skyborne
{
    namespace
    {
        constexpr double PI = 3.14159265358979323846;

        double Clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

        double Smooth(double e0, double e1, double x)
        {
            double t = Clamp((x - e0) / (e1 - e0), 0, 1);
            return t * t * (3 - 2 * t);
        }

        double Lum(double const* c) { return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]; }

        // numpy's interp: clamped at both ends.
        double Interp(double x, std::vector<std::pair<double, double>> const& pts)
        {
            if (x <= pts.front().first)
                return pts.front().second;
            if (x >= pts.back().first)
                return pts.back().second;
            size_t i = 1;
            while (pts[i].first < x)
                ++i;
            double t = (x - pts[i - 1].first) / (pts[i].first - pts[i - 1].first);
            return pts[i - 1].second + t * (pts[i].second - pts[i - 1].second);
        }

        double Median(std::vector<double> v)
        {
            if (v.empty())
                return std::numeric_limits<double>::quiet_NaN();
            std::sort(v.begin(), v.end());
            size_t n = v.size();
            return n % 2 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
        }

        // The old texture's rows against the distance up the front, in the first race's millimetres.
        double Row(double s)
        {
            static std::vector<std::pair<double, double>> const table = { { -139.4, 226 }, { -106.2, 232 }, { -53.7, 240 }, { -4.2, 246 }, { 8.8, 258 }, { 41.4, 266 } };
            return Interp(-s, table);
        }

        uint32_t Crc(Bytes const& b) { return uint32_t(crc32(0, b.data(), uInt(b.size()))); }

        std::string Lower(std::string text)
        {
            for (char& c : text)
                c = char(std::tolower(static_cast<unsigned char>(c)));
            return text;
        }
    }

    void BodyShade::Load(fs::path const& dir, Game const& game, std::function<void(std::string const&)> const& log)
    {
        _races.clear();
        std::ifstream in(dir / "shade.txt");
        if (!in)
            return;
        std::string line, name;
        std::map<std::string, Race> parsed;
        std::map<std::string, std::string> names;
        while (std::getline(in, line))
        {
            if (!line.empty() && line.back() == '\r')
                line.pop_back();
            if (line.empty() || line[0] == '#')
                continue;
            if (line[0] == '[')
            {
                name = line.substr(1, line.find(']') - 1);
                names[Lower(name)] = name;
                parsed[Lower(name)];
                continue;
            }
            std::istringstream fields(line);
            std::string key;
            fields >> key;
            Race& race = parsed[Lower(name)];
            if (key == "fingerprint")
            {
                fields >> race.crcModel >> race.crcSkin;
                continue;
            }
            std::vector<double> values;
            for (double v; fields >> v;)
                values.push_back(v);
            race.p[key] = values;
        }
        for (auto& [lower, race] : parsed)
        {
            std::string const& proper = names[lower];
            std::ifstream bin(dir / (proper + ".bin"), std::ios::binary);
            Bytes data((std::istreambuf_iterator<char>(bin)), std::istreambuf_iterator<char>());
            if (data.size() < 28 || std::memcmp(data.data(), "SHDE", 4) != 0)
            {
                log("  shade: no fields for " + proper);
                continue;
            }
            auto u32 = [&](size_t at) { uint32_t v; std::memcpy(&v, &data[at], 4); return v; };
            race.x0 = u32(8);
            race.y0 = u32(12);
            race.w = u32(16);
            race.h = u32(20);
            race.noise = u32(24) != 0;
            size_t n = size_t(race.w) * race.h, at = 28;
            if (data.size() < at + n * 13 + (race.noise ? n * 20 : 0))
            {
                log("  shade: the fields of " + proper + " are cut short");
                continue;
            }
            auto floats = [&](std::vector<float>& to)
            {
                to.resize(n);
                std::memcpy(to.data(), &data[at], n * 4);
                at += n * 4;
            };
            floats(race.y);
            floats(race.s);
            floats(race.z);
            race.ok.assign(data.begin() + std::ptrdiff_t(at), data.begin() + std::ptrdiff_t(at + n));
            at += n;
            if (race.noise)
                for (auto& f : race.field)
                    floats(f);
            std::string base = "Character\\" + proper + "\\Female\\" + proper + "Female";
            Bytes model, skin;
            if (!game.Read(base + ".m2", model) || !game.Read(base + "00.skin", skin) || Crc(model) != race.crcModel || Crc(skin) != race.crcSkin)
            {
                log("  shade: the " + proper + " model isn't the one the shading was made for: left as it is");
                continue;
            }
            _races[lower] = std::move(race);
        }
    }

    bool BodyShade::Apply(std::string const& path, Image& im) const
    {
        if (im.width != 512 || im.height != 512)
            return false;
        std::string lower = Lower(path);
        if (lower.find("_extra") != std::string::npos)
            return false;
        Race const* found = nullptr;
        for (auto const& [name, race] : _races)
            if (lower.find("character\\" + name + "\\female\\" + name + "femaleskin00_") == 0)
                found = &race;
        if (!found)
            return false;
        Race const& r = *found;
        auto const& p = r.p;
        auto one = [&](char const* k) { return p.at(k)[0]; };
        auto has = [&](char const* k) { return p.count(k) != 0; };
        double sH = one("scale"), shift = one("shift"), cut = one("cut"), W = one("width"), depth = one("depth"), foldK = one("fold");
        double rimAt = one("rim_at"), rimW = one("rim_w"), rimRamp = one("rim_ramp"), rimS = one("rim");
        double lift = one("lift"), lw = one("lift_w"), liftTop = one("lift_top");
        size_t n = size_t(r.w) * r.h;

        // Per texel of the box: the position in the first race's millimetres.
        std::vector<double> ay0(n, 0.0), s0(n, 0.0);
        for (size_t i = 0; i < n; ++i)
            if (r.ok[i])
            {
                ay0[i] = double(r.y[i]) / sH;
                s0[i] = double(r.s[i]) / sH - shift;
            }
        auto px = [&](size_t i) -> uint8_t* { return &im.rgba[(size_t(r.y0 + i / r.w) * 512 + (r.x0 + i % r.w)) * 4]; };
        std::vector<std::array<double, 3>> src(n), out(n);
        for (size_t i = 0; i < n; ++i)
            for (int c = 0; c < 3; ++c)
                src[i][c] = out[i][c] = px(i)[c] / 255.0;

        double ref[3] = { 0, 0, 0 };
        std::vector<std::pair<double, double>> toneK;
        bool hasRef = has("ref");
        if (hasRef)
        {
            auto const& b = p.at("ref");
            for (int c = 0; c < 3; ++c)
            {
                std::vector<double> v;
                for (int y = int(b[1]); y < int(b[3]); ++y)
                    for (int x = int(b[0]); x < int(b[2]); ++x)
                        v.push_back(im.rgba[(size_t(y) * 512 + x) * 4 + c] / 255.0);
                ref[c] = Median(v);
            }
            auto const& tk = p.at("tone_k");
            for (size_t i = 0; i + 1 < tk.size(); i += 2)
                toneK.push_back({ tk[i], tk[i + 1] });
        }
        double rc[3] = { 1, 1, 1 };
        if (has("rim_col"))
            for (int c = 0; c < 3; ++c)
                rc[c] = p.at("rim_col")[c];
        if (hasRef && has("rim_k"))
            for (int c = 0; c < 3; ++c)
                rc[c] = Clamp(ref[c] * one("rim_k"), 0, 1);
        std::vector<double> along(n, 0.0);
        for (size_t i = 0; i < n; ++i)
        {
            if (!r.ok[i])
                continue;
            double s = s0[i], a = ay0[i];
            double t = Clamp((Row(s) - 240.0) / (266.3 - 240.0), 0, 1);
            double al = std::pow(std::sin(PI * t), 0.6) * (t > 0) * (t < 1) * Clamp((s + shift - cut) / 6.0, 0, 1);
            along[i] = al;
            double line = std::exp(-std::pow(a / (1.63 * W), 2)) * al;
            double fold = std::exp(-std::pow((a - 4.2 * W) / (3.1 * W), 2)) * al;
            double foldshade = std::exp(-std::pow((a - 8.45 * W) / (2.9 * W), 2)) * al * 0.5;
            double rim = std::exp(-std::pow((a - rimAt * W) / (rimW * W), 2)) * al * (1 - rimRamp * (1 - Clamp(t * 1.25, 0, 1)));
            double dome = std::exp(-std::pow(a / 11.4, 2)) * std::exp(-std::pow((s - 80.0) / 26.0, 2)) * 0.05;
            double f = 1 - depth * line + foldK * fold - 0.06 * foldshade + dome;
            double sprof = Clamp((s + 3.0) / 10.0, 0, 1) * Clamp((liftTop - s) / 30.0, 0, 1);
            double wl = Clamp(lift * std::exp(-std::pow(a / lw, 4)) * sprof, 0, 1);
            double tone[3];
            for (int c = 0; c < 3; ++c)
                tone[c] = hasRef ? ref[c] * Interp(s, toneK) : p.at("tone")[c];
            for (int c = 0; c < 3; ++c)
            {
                double base = src[i][c] + std::max(tone[c] - src[i][c], 0.0) * wl;
                double o = base * f;
                out[i][c] = o + (rc[c] - o) * (rim * rimS);
            }
        }

        if (has("core"))
        {
            std::vector<double> lums;
            for (size_t i = 0; i < n; ++i)
                if (r.ok[i] && s0[i] > 30 && s0[i] < 110 && ay0[i] < 25)
                    lums.push_back(Lum(src[i].data()));
            double lumRef = Median(lums);
            double cw = one("core_w"), coreK = one("core_k"), slotW = one("slot_w"), slotK = one("slot_k");
            double tl = 0.88 + 0.12 * std::min(1.0, lumRef * 2.0);
            for (size_t i = 0; i < n; ++i)
            {
                if (!r.ok[i])
                    continue;
                double a = ay0[i], al = along[i];
                double cwv = cw * std::pow(Clamp(al, 0.05, 1), 0.6);
                double wp = (1 - Smooth(cwv * 0.55, cwv, a)) * coreK * Clamp(al * 1.6, 0, 1);
                double slot = std::exp(-std::pow(a / slotW, 2)) * al;
                for (int c = 0; c < 3; ++c)
                {
                    double cc = p.at("core")[c] * tl;
                    out[i][c] = out[i][c] * (1 - wp) + cc * wp;
                    out[i][c] = out[i][c] * (1 - slotK * slot);
                }
            }
        }

        if (has("d_z0") && r.noise)
        {
            auto d = [&](char const* k, size_t j = 0) { return p.at(std::string("d_") + k)[j]; };
            std::vector<double> lums;
            for (size_t i = 0; i < n; ++i)
            {
                if (!r.ok[i])
                    continue;
                double q = double(r.z[i]) - d("z0"), yr = ay0[i] * sH;
                if (q > d("ref_lo") && q < d("ref_hi") && yr < 25)
                    lums.push_back(Lum(src[i].data()));
            }
            double tl = 0.88 + 0.12 * std::min(1.0, Median(lums) * 2.0);
            double Hh = d("height"), Wm = d("width"), soft = d("soft"), skew = d("skew"), wmin = d("wmin");
            double pink[3], hi[3], deep[3];
            for (int c = 0; c < 3; ++c)
            {
                pink[c] = d("pink", c) * tl;
                hi[c] = d("hi", c) * tl;
                deep[c] = d("deep", c) * tl;
            }
            for (size_t i = 0; i < n; ++i)
            {
                if (!r.ok[i])
                    continue;
                double yr = ay0[i] * sH, q = double(r.z[i]) - d("z0");
                double U = q / Hh, Uc = Clamp(U, 0, 1);
                double env = Smooth(0.0, 0.06, U) * Smooth(1.0, 0.92, U);
                double wob = 0.35 * double(r.field[0][i]);
                double Wf = Wm * std::pow(std::sin(PI * std::pow(Uc, skew)), 0.75) + wmin;
                double mm = Smooth(Wf + soft, Wf - soft, yr) * env;
                double* o = out[i].data();
                double tint[3];
                for (int c = 0; c < 3; ++c)
                    tint[c] = d("tint", c) * Lum(o);
                double wb = std::exp(-std::pow(yr / (0.75 * Wf + 2.0), 2)) * env;
                for (int c = 0; c < 3; ++c)
                    o[c] = o[c] + (tint[c] - o[c]) * (wb * d("tint_k"));
                double ridge = std::exp(-std::pow((yr - 0.5 * Wf) / (0.34 * Wf + 1.0), 2)) * mm;
                double edge = std::exp(-std::pow((yr - (Wf + 1.5)) / (soft * 0.55), 2)) * env * Smooth(0.0, 0.2, U);
                double gapIn = std::exp(-std::pow((yr - 1.5) / 2.0, 2)) * env;
                double shade = 1 + d("ridge_k") * ridge - d("edge_k") * edge - d("gap_edge_k") * gapIn;
                shade = shade * (1 - d("low_k") * (1 - Uc) * mm);
                for (int c = 0; c < 3; ++c)
                    o[c] *= shade;
                double cw = d("gap") + d("gap_open") * Smooth(d("gap_win", 0), d("gap_win", 1), U) * Smooth(d("gap_win", 2), d("gap_win", 3), U) + 0.15 * wob;
                double mb = d("band") * Smooth(d("band_win", 0), d("band_win", 1), U) * Smooth(d("band_win", 2), d("band_win", 3), U) * (1 + 0.12 * double(r.field[1][i]));
                double yb = cw + 0.5 * mb;
                double bmask = std::exp(-std::pow(std::fabs(yr - yb) / (0.55 * mb + 0.35), 2.4)) * env * (mb > 0.2 ? 1.0 : 0.0);
                double wr = 1 + d("ripple") * std::sin(q * 0.8 + 3.0 * double(r.field[2][i]));
                double grad = Clamp((yr - cw) / std::max(mb, 1e-3), 0, 1);
                double bk = bmask * d("band_k");
                for (int c = 0; c < 3; ++c)
                {
                    double tone = pink[c] * (0.78 + 0.38 * std::sin(PI * grad));
                    tone = tone + (deep[c] * 1.2 - tone) * (1 - grad) * 0.35;
                    o[c] = o[c] * (1 - bk) + tone * wr * bk;
                }
                double gl = std::exp(-std::pow((yr - (cw + 0.38 * mb)) / 0.55, 2)) * bmask * Clamp(0.55 + 0.45 * double(r.field[3][i]), 0, 1)
                    * Smooth(0.2, 0.4, U) * Smooth(0.7, 0.5, U);
                for (int c = 0; c < 3; ++c)
                    o[c] = o[c] + (hi[c] - o[c]) * (gl * d("band_hi"));
                double gmask = std::exp(-std::pow(yr / std::max(cw, 0.2), 2.2)) * env * Smooth(0.07, 0.13, U) * Smooth(0.9, 0.8, U);
                double gk = gmask * d("gap_k");
                for (int c = 0; c < 3; ++c)
                {
                    double gcol = deep[c] * (0.6 + 0.4 * Clamp(yr / std::max(cw, 1e-3), 0, 1));
                    o[c] = o[c] * (1 - gk) + gcol * gk;
                }
                double hc = d("cap_c"), hrx = d("cap_rx"), hry = d("cap_ry");
                double cap = std::hypot(yr / hrx, (q - hc) / hry);
                double mCap = Smooth(1.6, 0.4, cap);
                double hl = Clamp(0.5 - 0.5 * (q - hc) / hry, 0, 1);
                for (int c = 0; c < 3; ++c)
                    o[c] = o[c] + (Clamp(o[c] * 1.16 + 0.015, 0, 1) - o[c]) * (mCap * d("cap_k") * (0.35 + 0.65 * hl));
                double reg = std::exp(-std::pow(yr / (Wf + 8.0), 2));
                for (int c = 0; c < 3; ++c)
                    o[c] *= 1 + 0.022 * double(r.field[4][i]) * reg;
            }
        }

        for (size_t i = 0; i < n; ++i)
            if (r.ok[i])
                for (int c = 0; c < 3; ++c)
                    px(i)[c] = uint8_t(std::lround(Clamp(out[i][c], 0, 1) * 255));
        return true;
    }
}
