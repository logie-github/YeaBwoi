// A PNG decoder (png.cpp) for the HD sets' textures (hdbody.cpp).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace wc {

// A PNG (8-bit greyscale, grey+alpha, RGB, RGBA or palette; not interlaced) as RGBA. False and `error` otherwise.
bool decodePng(const uint8_t* data, size_t size, std::vector<uint8_t>& rgba, int& w, int& h, std::string& error);

} // namespace wc
