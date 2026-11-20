// The desktop harness's side of a part's texture (what the app does in Kotlin before Native.attachPart): the part's PNG
// read (8-bit RGBA or RGB, not interlaced: what bodyparts/PartPackage writes), and bodyparts/Recolor.kt's recolour ported
// line for line (in linear light, each channel times target / the part's own average over the texels its mesh uses), and
// Recolor.averageColor to sample a skin colour from a region of the model's own skin texture.
#pragma once

#include <zlib.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "../android/app/src/main/cpp/engine/bodypart.h"
#include "../android/app/src/main/cpp/engine/bytes.h"

namespace partpaint {

struct Rgba {
    int w = 0, h = 0;
    std::vector<uint8_t> px;   // top row first
};

inline bool readPng(const std::string& path, Rgba& out) {
    wc::Bytes b;
    if (!wc::readFile(path, b) || b.size() < 33 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return false;
    auto be = [&](size_t o) { return uint32_t(b[o]) << 24 | uint32_t(b[o + 1]) << 16 | uint32_t(b[o + 2]) << 8 | b[o + 3]; };
    int depth = 0, color = 0, interlace = 0;
    std::vector<uint8_t> z;
    for (size_t o = 8; o + 8 <= b.size();) {
        uint32_t n = be(o);
        std::string type(b.begin() + long(o + 4), b.begin() + long(o + 8));
        if (o + 12 + n > b.size()) return false;
        if (type == "IHDR") { out.w = int(be(o + 8)); out.h = int(be(o + 12)); depth = b[o + 16]; color = b[o + 17]; interlace = b[o + 20]; }
        else if (type == "IDAT") z.insert(z.end(), b.begin() + long(o + 8), b.begin() + long(o + 8 + n));
        else if (type == "IEND") break;
        o += 12 + n;
    }
    if (depth != 8 || (color != 6 && color != 2) || interlace || out.w <= 0 || out.h <= 0) return false;
    int ch = color == 6 ? 4 : 3;
    size_t stride = size_t(out.w) * ch;
    std::vector<uint8_t> raw((stride + 1) * size_t(out.h));
    uLongf len = uLongf(raw.size());
    if (uncompress(raw.data(), &len, z.data(), uLong(z.size())) != Z_OK || len != raw.size()) return false;
    std::vector<uint8_t> cur(stride), prev(stride, 0);
    out.px.assign(size_t(out.w) * out.h * 4, 255);
    for (int y = 0; y < out.h; ++y) {
        const uint8_t* line = &raw[size_t(y) * (stride + 1)];
        uint8_t f = line[0];
        for (size_t i = 0; i < stride; ++i) {
            int a = i >= size_t(ch) ? cur[i - ch] : 0, up = prev[i], c = i >= size_t(ch) ? prev[i - ch] : 0, x = line[1 + i];
            int v;
            switch (f) {
                case 1: v = x + a; break;
                case 2: v = x + up; break;
                case 3: v = x + (a + up) / 2; break;
                case 4: { int p = a + up - c, pa = std::abs(p - a), pb = std::abs(p - up), pc = std::abs(p - c);
                          v = x + (pa <= pb && pa <= pc ? a : pb <= pc ? up : c); break; }
                default: v = x;
            }
            cur[i] = uint8_t(v);
        }
        for (int x = 0; x < out.w; ++x)
            for (int k = 0; k < ch; ++k) out.px[(size_t(y) * out.w + x) * 4 + k] = cur[size_t(x) * ch + k];
        prev.swap(cur);
    }
    return true;
}

inline float toLinear(int v) { float c = v / 255.0f; return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f); }
inline float toSrgb(float c) { return c <= 0.0031308f ? c * 12.92f : 1.055f * std::pow(c, 1 / 2.4f) - 0.055f; }
inline int byte(float c) { return int(std::fmin(1.0f, std::fmax(0.0f, c)) * 255.0f + 0.5f); }

// Recolor.averageColor: the mean in linear light of the pixels with alpha >= minAlpha inside `mask` (empty: all) and the
// region [x0, y0, x1, y1); -1 when none.
inline int averageColor(const uint8_t* rgba, int w, int h, const std::vector<bool>& mask, int x0, int y0, int x1, int y1, int minAlpha = 128) {
    double r = 0, g = 0, b = 0; long n = 0;
    for (int y = std::max(0, y0); y < std::min(h, y1); ++y)
        for (int x = std::max(0, x0); x < std::min(w, x1); ++x) {
            size_t i = size_t(y) * w + x;
            if (!mask.empty() && !mask[i]) continue;
            if (rgba[i * 4 + 3] < minAlpha) continue;
            r += toLinear(rgba[i * 4]); g += toLinear(rgba[i * 4 + 1]); b += toLinear(rgba[i * 4 + 2]); ++n;
        }
    if (!n) return -1;
    return byte(toSrgb(float(r / n))) << 16 | byte(toSrgb(float(g / n))) << 8 | byte(toSrgb(float(b / n)));
}

// bodyparts/PartSkin.sampleAt: the mean in linear light of the texels within `radius` of each (u, v) point (v 0 at the top
// row), opaque ones only; -1 when none.
inline int sampleAt(const uint8_t* rgba, int w, int h, const std::vector<float>& uvs, int radius) {
    double r = 0, g = 0, b = 0; long n = 0;
    for (size_t i = 0; i + 1 < uvs.size(); i += 2) {
        int cx = int(uvs[i] * w), cy = int(uvs[i + 1] * h);
        for (int y = std::max(0, cy - radius); y <= std::min(h - 1, cy + radius); ++y)
            for (int x = std::max(0, cx - radius); x <= std::min(w - 1, cx + radius); ++x) {
                size_t k = size_t(y) * w + x;
                if (rgba[k * 4 + 3] < 128) continue;
                r += toLinear(rgba[k * 4]); g += toLinear(rgba[k * 4 + 1]); b += toLinear(rgba[k * 4 + 2]); ++n;
            }
    }
    if (!n) return -1;
    return byte(toSrgb(float(r / n))) << 16 | byte(toSrgb(float(g / n))) << 8 | byte(toSrgb(float(b / n)));
}

// Recolor.uvMask: the texels the part's triangles cover.
inline std::vector<bool> uvMask(const wc::PartData& p, int w, int h) {
    std::vector<bool> m(size_t(w) * h, false);
    for (size_t t = 0; t + 2 < p.indices.size(); t += 3) {
        float ax = p.uvs[p.indices[t] * 2] * w, ay = p.uvs[p.indices[t] * 2 + 1] * h;
        float bx = p.uvs[p.indices[t + 1] * 2] * w, by = p.uvs[p.indices[t + 1] * 2 + 1] * h;
        float cx = p.uvs[p.indices[t + 2] * 2] * w, cy = p.uvs[p.indices[t + 2] * 2 + 1] * h;
        int minX = std::max(0, int(std::fmin(ax, std::fmin(bx, cx)))), maxX = std::min(w - 1, int(std::fmax(ax, std::fmax(bx, cx))));
        int minY = std::max(0, int(std::fmin(ay, std::fmin(by, cy)))), maxY = std::min(h - 1, int(std::fmax(ay, std::fmax(by, cy))));
        for (int y = minY; y <= maxY; ++y)
            for (int x = minX; x <= maxX; ++x) {
                float px = x + 0.5f, py = y + 0.5f;
                float w0 = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
                float w1 = (cx - bx) * (py - by) - (cy - by) * (px - bx);
                float w2 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx);
                if ((w0 >= 0 && w1 >= 0 && w2 >= 0) || (w0 <= 0 && w1 <= 0 && w2 <= 0)) m[size_t(y) * w + x] = true;
            }
    }
    return m;
}

// Recolor.recolor at full strength: the image's reference colour (its own average over `mask`) becomes `target`.
inline void recolor(Rgba& img, int target, const std::vector<bool>& mask) {
    int ref = averageColor(img.px.data(), img.w, img.h, mask, 0, 0, img.w, img.h);
    if (ref < 0) ref = averageColor(img.px.data(), img.w, img.h, {}, 0, 0, img.w, img.h);
    if (ref < 0) return;
    uint8_t lut[3][256];
    for (int k = 0; k < 3; ++k) {
        float t = toLinear((target >> (16 - 8 * k)) & 0xFF), r = toLinear((ref >> (16 - 8 * k)) & 0xFF);
        float gain = std::fmin(64.0f, std::fmax(0.0f, t / std::fmax(r, 1e-4f)));
        for (int v = 0; v < 256; ++v) lut[k][v] = uint8_t(byte(toSrgb(toLinear(v) * gain)));
    }
    for (size_t i = 0; i < size_t(img.w) * img.h; ++i)
        for (int k = 0; k < 3; ++k) img.px[i * 4 + k] = lut[k][img.px[i * 4 + k]];
}

} // namespace partpaint
