// A small JSON reader for the engine's own data (assets/locations.json's entries): numbers, strings, true/false/null,
// arrays and objects. Not a validator: it reads what is there and stops at the first thing it can't read.
#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

namespace wc {

struct Json {
    enum Kind { Null, Bool, Number, String, Array, Object } kind = Null;
    bool b = false;
    double n = 0;
    std::string s;
    std::vector<Json> a;
    std::map<std::string, Json> o;

    // `text` read into `out`; false (and `error` says where) when it isn't JSON.
    static bool parse(const std::string& text, Json& out, std::string* error = nullptr);

    const Json& operator[](const std::string& key) const;   // a member, or null
    const Json& operator[](size_t i) const;                 // an element, or null
    bool has(const std::string& key) const { return kind == Object && o.count(key) > 0; }
    size_t size() const { return kind == Array ? a.size() : kind == Object ? o.size() : 0; }
    double num(double def = 0) const { return kind == Number ? n : kind == Bool ? (b ? 1 : 0) : def; }
    std::string str(const std::string& def = "") const { return kind == String ? s : def; }
    bool isNull() const { return kind == Null; }
};

} // namespace wc
