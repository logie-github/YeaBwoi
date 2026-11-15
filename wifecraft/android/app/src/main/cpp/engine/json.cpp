#include "json.h"

#include <cstdlib>

namespace wc {

namespace {

const Json kNull;

struct P {
    const std::string& t;
    size_t i = 0;
    std::string err;

    void ws() { while (i < t.size() && (t[i] == ' ' || t[i] == '\n' || t[i] == '\r' || t[i] == '\t')) ++i; }
    bool fail(const char* why) { if (err.empty()) err = std::string(why) + " at " + std::to_string(i); return false; }

    static void utf8(std::string& out, unsigned c) {
        if (c < 0x80) out += char(c);
        else if (c < 0x800) { out += char(0xC0 | (c >> 6)); out += char(0x80 | (c & 0x3F)); }
        else if (c < 0x10000) { out += char(0xE0 | (c >> 12)); out += char(0x80 | ((c >> 6) & 0x3F)); out += char(0x80 | (c & 0x3F)); }
        else { out += char(0xF0 | (c >> 18)); out += char(0x80 | ((c >> 12) & 0x3F)); out += char(0x80 | ((c >> 6) & 0x3F)); out += char(0x80 | (c & 0x3F)); }
    }

    bool string(std::string& out) {
        if (i >= t.size() || t[i] != '"') return fail("expected a string");
        ++i;
        while (i < t.size() && t[i] != '"') {
            char c = t[i++];
            if (c != '\\') { out += c; continue; }
            if (i >= t.size()) return fail("bad escape");
            char e = t[i++];
            switch (e) {
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case 'r': out += '\r'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'u': {
                    if (i + 4 > t.size()) return fail("bad \\u");
                    unsigned c1 = unsigned(std::strtoul(t.substr(i, 4).c_str(), nullptr, 16));
                    i += 4;
                    if (c1 >= 0xD800 && c1 < 0xDC00 && i + 6 <= t.size() && t[i] == '\\' && t[i + 1] == 'u') {
                        unsigned c2 = unsigned(std::strtoul(t.substr(i + 2, 4).c_str(), nullptr, 16));
                        i += 6;
                        c1 = 0x10000 + ((c1 - 0xD800) << 10) + (c2 - 0xDC00);
                    }
                    utf8(out, c1);
                    break;
                }
                default: out += e;
            }
        }
        if (i >= t.size()) return fail("unterminated string");
        ++i;
        return true;
    }

    bool value(Json& v, int depth) {
        if (depth > 64) return fail("too deep");
        ws();
        if (i >= t.size()) return fail("unexpected end");
        char c = t[i];
        if (c == '{') {
            v.kind = Json::Object;
            ++i; ws();
            if (i < t.size() && t[i] == '}') { ++i; return true; }
            while (true) {
                ws();
                std::string k;
                if (!string(k)) return false;
                ws();
                if (i >= t.size() || t[i] != ':') return fail("expected ':'");
                ++i;
                if (!value(v.o[k], depth + 1)) return false;
                ws();
                if (i < t.size() && t[i] == ',') { ++i; continue; }
                if (i < t.size() && t[i] == '}') { ++i; return true; }
                return fail("expected ',' or '}'");
            }
        }
        if (c == '[') {
            v.kind = Json::Array;
            ++i; ws();
            if (i < t.size() && t[i] == ']') { ++i; return true; }
            while (true) {
                v.a.emplace_back();
                if (!value(v.a.back(), depth + 1)) return false;
                ws();
                if (i < t.size() && t[i] == ',') { ++i; continue; }
                if (i < t.size() && t[i] == ']') { ++i; return true; }
                return fail("expected ',' or ']'");
            }
        }
        if (c == '"') { v.kind = Json::String; return string(v.s); }
        if (t.compare(i, 4, "true") == 0) { v.kind = Json::Bool; v.b = true; i += 4; return true; }
        if (t.compare(i, 5, "false") == 0) { v.kind = Json::Bool; v.b = false; i += 5; return true; }
        if (t.compare(i, 4, "null") == 0) { v.kind = Json::Null; i += 4; return true; }
        const char* start = t.c_str() + i;
        char* end = nullptr;
        double d = std::strtod(start, &end);
        if (end == start) return fail("unexpected character");
        v.kind = Json::Number;
        v.n = d;
        i += size_t(end - start);
        return true;
    }
};

} // namespace

bool Json::parse(const std::string& text, Json& out, std::string* error) {
    P p{text};
    out = Json();
    bool ok = p.value(out, 0);
    if (!ok && error) *error = p.err;
    return ok;
}

const Json& Json::operator[](const std::string& key) const {
    if (kind != Object) return kNull;
    auto it = o.find(key);
    return it == o.end() ? kNull : it->second;
}

const Json& Json::operator[](size_t i) const {
    return kind == Array && i < a.size() ? a[i] : kNull;
}

} // namespace wc
