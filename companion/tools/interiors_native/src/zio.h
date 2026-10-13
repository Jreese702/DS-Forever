// zlib streams through deflate/inflate only (the host's bundled zlib has no compress.c / uncompr.c).
#pragma once
#include "common.h"

namespace wc {
Bytes zCompress(const uint8_t* data, size_t size, int level);
// exactly [want] bytes, no trailing data; false otherwise
bool zDecompressExact(const Bytes& in, size_t want, Bytes& out);
}  // namespace wc
