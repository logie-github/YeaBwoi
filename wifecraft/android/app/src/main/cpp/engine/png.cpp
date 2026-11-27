// A PNG decoder for the HD sets' textures (hdbody.cpp): 8-bit, not interlaced, inflated with zlib.
#include "png.h"

#include <cstdlib>
#include <cstring>
#include <string>
#include <zlib.h>

namespace wc {

bool decodePng(const uint8_t* d, size_t size, std::vector<uint8_t>& rgba, int& w, int& h, std::string& error) {
    static const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    if (size < 33 || std::memcmp(d, sig, 8) != 0) { error = "is not a PNG"; return false; }
    auto be = [&](size_t o) { return uint32_t(d[o]) << 24 | uint32_t(d[o + 1]) << 16 | uint32_t(d[o + 2]) << 8 | d[o + 3]; };
    size_t o = 8;
    int depth = 0, ctype = 0, interlace = 0;
    std::vector<uint8_t> idat, pal, trns;
    w = h = 0;
    while (o + 12 <= size) {
        uint32_t len = be(o);
        if (o + 12 + uint64_t(len) > size) { error = "is cut short"; return false; }
        const uint8_t* c = d + o + 8;
        std::string type(reinterpret_cast<const char*>(d + o + 4), 4);
        if (type == "IHDR" && len >= 13) {
            w = int(be(o + 8)); h = int(be(o + 12)); depth = c[8]; ctype = c[9]; interlace = c[12];
        } else if (type == "PLTE") pal.assign(c, c + len);
        else if (type == "tRNS") trns.assign(c, c + len);
        else if (type == "IDAT") idat.insert(idat.end(), c, c + len);
        else if (type == "IEND") break;
        o += 12 + len;
    }
    if (w <= 0 || h <= 0 || w > 8192 || h > 8192) { error = "has a bad size"; return false; }
    if (depth != 8 || interlace != 0) { error = "is not an 8-bit, non-interlaced PNG"; return false; }
    int ch = ctype == 0 ? 1 : ctype == 2 ? 3 : ctype == 3 ? 1 : ctype == 4 ? 2 : ctype == 6 ? 4 : 0;
    if (!ch) { error = "has an unknown colour type"; return false; }
    size_t stride = size_t(w) * ch;
    std::vector<uint8_t> raw((stride + 1) * size_t(h));
    z_stream zs{};
    if (inflateInit(&zs) != Z_OK) { error = "can't be inflated"; return false; }
    zs.next_in = idat.data(); zs.avail_in = uInt(idat.size());
    zs.next_out = raw.data(); zs.avail_out = uInt(raw.size());
    int zr = inflate(&zs, Z_FINISH);
    size_t got = raw.size() - zs.avail_out;
    inflateEnd(&zs);
    if ((zr != Z_STREAM_END && zr != Z_OK && zr != Z_BUF_ERROR) || got != raw.size()) { error = "has damaged image data"; return false; }
    std::vector<uint8_t> px(stride * size_t(h));
    for (int y = 0; y < h; ++y) {
        uint8_t f = raw[size_t(y) * (stride + 1)];
        const uint8_t* s = &raw[size_t(y) * (stride + 1) + 1];
        uint8_t* r = &px[size_t(y) * stride];
        const uint8_t* p = y ? &px[size_t(y - 1) * stride] : nullptr;
        for (size_t x = 0; x < stride; ++x) {
            int a = x >= size_t(ch) ? r[x - ch] : 0, b = p ? p[x] : 0, c = (p && x >= size_t(ch)) ? p[x - ch] : 0;
            int v = s[x];
            switch (f) {
                case 0: break;
                case 1: v += a; break;
                case 2: v += b; break;
                case 3: v += (a + b) / 2; break;
                case 4: { int pp = a + b - c, pa = std::abs(pp - a), pb = std::abs(pp - b), pc = std::abs(pp - c);
                          v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c); break; }
                default: error = "has an unknown filter"; return false;
            }
            r[x] = uint8_t(v);
        }
    }
    rgba.assign(size_t(w) * h * 4, 255);
    for (size_t i = 0; i < size_t(w) * h; ++i) {
        uint8_t* q = &rgba[i * 4];
        const uint8_t* s = &px[i * ch];
        switch (ctype) {
            case 0: q[0] = q[1] = q[2] = s[0]; break;
            case 4: q[0] = q[1] = q[2] = s[0]; q[3] = s[1]; break;
            case 2: q[0] = s[0]; q[1] = s[1]; q[2] = s[2]; break;
            case 6: std::memcpy(q, s, 4); break;
            case 3: {
                size_t k = s[0];
                if (k * 3 + 2 < pal.size()) { q[0] = pal[k * 3]; q[1] = pal[k * 3 + 1]; q[2] = pal[k * 3 + 2]; }
                if (k < trns.size()) q[3] = trns[k];
                break;
            }
        }
    }
    return true;
}

} // namespace wc
