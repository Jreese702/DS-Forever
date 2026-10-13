#include "zio.h"

#include <zlib.h>

namespace wc {

Bytes zCompress(const uint8_t* data, size_t size, int level) {
    z_stream zs{};
    require(deflateInit(&zs, level) == Z_OK, "zlib deflate init failed");
    Bytes out(deflateBound(&zs, static_cast<uLong>(size)) + 64);
    zs.next_in = const_cast<Bytef*>(data);
    zs.avail_in = static_cast<uInt>(size);
    zs.next_out = out.data();
    zs.avail_out = static_cast<uInt>(out.size());
    const int rc = deflate(&zs, Z_FINISH);
    const size_t n = zs.total_out;
    deflateEnd(&zs);
    require(rc == Z_STREAM_END, "zlib deflate failed");
    out.resize(n);
    return out;
}

bool zDecompressExact(const Bytes& in, size_t want, Bytes& out) {
    z_stream zs{};
    if (inflateInit(&zs) != Z_OK) return false;
    out.assign(want + 1, 0);
    zs.next_in = const_cast<Bytef*>(in.data());
    zs.avail_in = static_cast<uInt>(in.size());
    zs.next_out = out.data();
    zs.avail_out = static_cast<uInt>(out.size());
    const int rc = inflate(&zs, Z_FINISH);
    const bool ok = rc == Z_STREAM_END && zs.total_out == want && zs.avail_in == 0;
    inflateEnd(&zs);
    out.resize(want);
    return ok;
}

}  // namespace wc
