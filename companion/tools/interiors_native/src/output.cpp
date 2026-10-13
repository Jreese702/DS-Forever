#include "output.h"

#include "zio.h"

#include <zlib.h>

#include <cstdio>
#include <set>

namespace wc {
namespace {
void be32(Bytes& b, uint32_t v) {
    for (int s = 24; s >= 0; s -= 8) b.push_back(static_cast<uint8_t>(v >> s));
}
uint32_t rd32(const uint8_t* p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]; }
void chunk(Bytes& out, const char* type, const Bytes& data) {
    be32(out, static_cast<uint32_t>(data.size()));
    const size_t at = out.size();
    out.insert(out.end(), type, type + 4);
    out.insert(out.end(), data.begin(), data.end());
    be32(out, static_cast<uint32_t>(crc32(0, out.data() + at, static_cast<uInt>(data.size() + 4))));
}
bool safeName(const std::string& s) {
    return !s.empty() && s != "." && s != ".." && s.find_first_of("/\\:") == std::string::npos;
}
}  // namespace

Bytes pngEncode(const Image& im) {
    require(im.w > 0 && im.h > 0, "empty plan image");
    Bytes raw;
    raw.reserve(static_cast<size_t>(im.h) * (im.w * 4 + 1));
    for (int y = 0; y < im.h; ++y) {
        raw.push_back(0);  // filter: none
        for (int x = 0; x < im.w; ++x) {
            const Rgba& p = im.at(x, y);
            raw.insert(raw.end(), {p.r, p.g, p.b, p.a});
        }
    }
    const Bytes z = zCompress(raw.data(), raw.size(), 6);
    Bytes out{0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    Bytes ihdr;
    be32(ihdr, static_cast<uint32_t>(im.w));
    be32(ihdr, static_cast<uint32_t>(im.h));
    ihdr.insert(ihdr.end(), {8, 6, 0, 0, 0});
    chunk(out, "IHDR", ihdr);
    chunk(out, "IDAT", z);
    chunk(out, "IEND", {});
    return out;
}

bool pngDecode(const Bytes& d, Image& out) {
    static const uint8_t sig[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    if (d.size() < 8 || std::memcmp(d.data(), sig, 8) != 0) return false;
    size_t at = 8;
    uint32_t w = 0, h = 0;
    Bytes idat;
    bool end = false;
    while (at + 12 <= d.size()) {
        const uint32_t len = rd32(&d[at]);
        if (at + 12 + static_cast<size_t>(len) > d.size()) return false;
        const std::string type(reinterpret_cast<const char*>(&d[at + 4]), 4);
        const uint8_t* body = &d[at + 8];
        if (static_cast<uint32_t>(crc32(0, &d[at + 4], len + 4)) != rd32(body + len)) return false;
        if (type == "IHDR") {
            if (len != 13 || body[8] != 8 || body[9] != 6 || body[10] || body[11] || body[12]) return false;
            w = rd32(body);
            h = rd32(body + 4);
        } else if (type == "IDAT") {
            idat.insert(idat.end(), body, body + len);
        } else if (type == "IEND") {
            end = true;
            at += 12 + len;
            break;
        }
        at += 12 + len;
    }
    if (!end || at != d.size() || !w || !h || static_cast<uint64_t>(w) * h > 64ull * 1024 * 1024) return false;
    const size_t stride = static_cast<size_t>(w) * 4;
    Bytes raw;
    if (!zDecompressExact(idat, static_cast<size_t>(h) * (stride + 1), raw)) return false;
    out = Image(static_cast<int>(w), static_cast<int>(h));
    Bytes prev(stride, 0), line(stride);
    for (uint32_t y = 0; y < h; ++y) {
        const uint8_t filter = raw[y * (stride + 1)];
        const uint8_t* src = &raw[y * (stride + 1) + 1];
        for (size_t i = 0; i < stride; ++i) {
            const int a = i >= 4 ? line[i - 4] : 0, b = prev[i], c = i >= 4 ? prev[i - 4] : 0;
            int v = src[i];
            switch (filter) {
                case 0: break;
                case 1: v += a; break;
                case 2: v += b; break;
                case 3: v += (a + b) / 2; break;
                case 4: {
                    const int p = a + b - c, pa = std::abs(p - a), pb = std::abs(p - b), pc = std::abs(p - c);
                    v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                    break;
                }
                default: return false;
            }
            line[i] = static_cast<uint8_t>(v);
        }
        for (uint32_t x = 0; x < w; ++x) out.at(x, y) = {line[x * 4], line[x * 4 + 1], line[x * 4 + 2], line[x * 4 + 3]};
        prev = line;
    }
    return true;
}

Json modelJson(const Model& m, const std::string& path) {
    Json model = Json::object();
    model["path"] = path;
    Json levels = Json::array();
    for (const Level& lv : m.levels) {
        Json l = Json::object();
        l["lxmin"] = lv.lxmin;
        l["lxmax"] = lv.lxmax;
        l["lymax"] = lv.lymax;
        l["lymin"] = lv.lymin;
        l["level"] = lv.level;
        l["z0"] = lv.z0;
        l["z1"] = lv.z1;
        l["zlo"] = lv.zlo;
        l["zhi"] = lv.zhi;
        l["img"] = lv.img;
        // Absent, never JSON null: Android's org.json optString() turns a null into the string "null", which the store and
        // MapView then look up as a file (T3 2026-10-13: 8 buildings deferred as "missing building asset").
        if (!lv.outline.empty()) l["outline"] = lv.outline;
        l["w"] = lv.w;
        l["h"] = lv.h;
        l["segs"] = lv.segs;
        l["groups"] = lv.groups;
        if (!lv.src.empty()) l["src"] = lv.src;
        if (!lv.zones.empty()) {
            Json zones = Json::array();
            for (const Level::Zone& z : lv.zones) {
                Json zj = Json::object();
                zj["kind"] = z.kind;
                auto ring = [](const std::vector<P2>& r) {
                    Json a = Json::array();
                    for (const P2& p : r) { Json q = Json::array(); q.push(p.first); q.push(p.second); a.push(q); }
                    return a;
                };
                zj["poly"] = ring(z.poly);
                if (!z.holes.empty()) {
                    Json hs = Json::array();
                    for (const auto& h : z.holes) hs.push(ring(h));
                    zj["holes"] = hs;
                }
                zones.push(zj);
            }
            l["zones"] = zones;
            l["zsrc"] = lv.zsrc;
        }
        levels.push(l);
    }
    model["levels"] = levels;
    Json stairs = Json::array();
    for (const Stair& s : m.stairs) {
        Json j = Json::object();
        j["zbot"] = s.zbot;
        j["ztop"] = s.ztop;
        Json ls = Json::array();
        ls.push(s.lower);
        ls.push(s.upper);
        j["levels"] = ls;
        j["source"] = "checked-game-walk";
        stairs.push(j);
    }
    model["stairs"] = stairs;
    Json g = Json::object();
    g["cell"] = m.grid.cell;
    g["nx"] = m.grid.nx;
    g["ny"] = m.grid.ny;
    g["lx0"] = m.grid.lx0;
    g["ly0"] = m.grid.ly0;
    g["zmin"] = m.grid.zmin;
    g["zscale"] = m.grid.zscale;
    g["filled"] = m.grid.filled;
    g["file"] = m.grid.file;
    if (m.grid.slots != 4) g["slots"] = m.grid.slots;
    if (m.grid.aliases) g["quantized_aliases"] = m.grid.aliases;
    model["grid"] = g;
    model["src"] = "wmo-walk-section-native-not-approved";
    return model;
}

Json placeRecord(const std::string& key, const Json& model, const Placement& placed) {
    require(std::fabs(placed.scale - 1) <= 1e-6, "MapView does not yet support scaled placements");
    const double c = std::cos(placed.yaw), s = std::sin(placed.yaw);
    double wxmin = 1e300, wxmax = -1e300, wymin = 1e300, wymax = -1e300;
    for (const Json& lv : model["levels"].arr())
        for (const char* xk : {"lxmin", "lxmax"})
            for (const char* yk : {"lymin", "lymax"}) {
                const double x = lv[xk].num(), y = lv[yk].num();
                const double wx = placed.origin.x - x * c + y * s, wy = placed.origin.y - x * s - y * c;
                wxmin = std::min(wxmin, wx); wxmax = std::max(wxmax, wx);
                wymin = std::min(wymin, wy); wymax = std::max(wymax, wy);
            }
    Json p = Json::object();
    Json k = Json::array();
    k.push(placed.fid);
    k.push(placed.uniqueId);
    k.push(key);
    p["key"] = k;
    p["fid"] = placed.fid;
    p["m"] = key;
    Json l2w = Json::array();
    for (double v : {placed.origin.x, placed.origin.y, c, s}) l2w.push(v);
    p["l2w"] = l2w;
    p["wxmin"] = wxmin;
    p["wxmax"] = wxmax;
    p["wymin"] = wymin;
    p["wymax"] = wymax;
    return p;
}

void validateModel(const Json& model, const std::map<std::string, Bytes>& files, const YieldFn& yield) {
    auto number = [](const Json& v) {
        require(v.isNum() && std::isfinite(v.num()), "non-finite or invalid output number");
        return v.num();
    };
    auto asset = [&](const Json& name) -> const Bytes& {
        require(name.isStr() && safeName(name.str()), "asset must be a plain file name");
        auto it = files.find(name.str());
        require(it != files.end(), "missing building asset " + name.str());
        return it->second;
    };
    const Json& levels = model["levels"];
    require(levels.isArr() && levels.size() > 0 && levels.size() <= 64, "model has no levels");
    std::vector<std::pair<double, double>> bands;
    for (size_t i = 0; i < levels.size(); ++i) {
        const Json& lv = levels[i];
        require(lv["level"].integer() == static_cast<int64_t>(i), "storeys must be indexed in array order");
        for (auto pr : {std::make_pair("lxmin", "lxmax"), std::make_pair("lymin", "lymax"), std::make_pair("z0", "z1"), std::make_pair("zlo", "zhi")})
            require(number(lv[pr.first]) <= number(lv[pr.second]), "invalid storey bounds");
        Image im;
        require(pngDecode(asset(lv["img"]), im), "plan image does not decode");
        require(im.w == lv["w"].integer() && im.h == lv["h"].integer() && im.w > 0 && im.h > 0, "plan dimensions do not match metadata");
        require(!lv.has("outline") || lv["outline"].isStr(), "outline must be a file name or absent, never null");
        if (lv.has("outline")) {
            Image o;
            require(pngDecode(asset(lv["outline"]), o), "outline image does not decode");
            require(o.w == im.w && o.h == im.h, "outline dimensions do not match plan");
        }
        if (lv.has("zones"))
            for (const Json& z : lv["zones"].arr()) {
                const std::string kind = z["kind"].str();
                require(kind == "exit" || kind == "up" || kind == "down", "unknown transition zone");
                std::vector<const Json*> rings{&z["poly"]};
                if (z.has("holes")) for (const Json& hh : z["holes"].arr()) rings.push_back(&hh);
                for (const Json* r : rings) {
                    require(r->size() >= 3, "invalid transition ring");
                    for (const Json& p : r->arr()) { require(p.size() == 2, "invalid transition ring"); number(p[0]); number(p[1]); }
                }
            }
        bands.push_back({lv["zlo"].num(), lv["zhi"].num()});
        if (yield) yield();
    }
    for (const Json& s : model["stairs"].arr()) {
        require(number(s["ztop"]) > number(s["zbot"]), "stair heights must increase");
        bands.push_back({s["zbot"].num(), s["ztop"].num()});
    }
    const Json& grid = model["grid"];
    const int64_t nx = grid["nx"].integer(), ny = grid["ny"].integer(), slots = grid.has("slots") ? grid["slots"].integer() : 4;
    require(slots >= 4 && slots <= 16 && nx > 0 && ny > 0 && nx * ny * slots <= 16LL * 1024 * 1024, "invalid grid allocation size");
    const Bytes& raw = asset(grid["file"]);
    const size_t want = static_cast<size_t>(nx * ny * slots);
    Bytes data;
    require(zDecompressExact(raw, want, data), "height grid must be exactly nx*ny*slots bytes without trailing data");
    require(number(grid["cell"]) > 0 && number(grid["zscale"]) > 0 && grid["zscale"].num() <= 1.6, "invalid grid scale");
    for (const char* k : {"lx0", "ly0", "zmin"}) number(grid[k]);
    for (size_t c = 0; c < want; c += slots)
        for (int64_t s = 0; s + 1 < slots; ++s) {
            const int a = data[c + s], b = data[c + s + 1];
            require(!(b > 0 && (a == 0 || b <= a)), "height slots must be ascending and zero-terminated");
        }
    const double zmin = grid["zmin"].num(), zscale = grid["zscale"].num(), slop = 1.6 + zscale;
    std::vector<size_t> counts(256, 0);
    for (uint8_t v : data) ++counts[v];
    size_t samples = 0, unassigned = 0;
    double lo = 1e300, hi = -1e300;
    for (int v = 1; v < 256; ++v) {
        if (!counts[v]) continue;
        samples += counts[v];
        const double z = zmin + (v - 1.0) * zscale;
        bool covered = false;
        for (auto& b : bands) covered = covered || (z >= b.first - slop && z <= b.second + slop);
        if (!covered) { unassigned += counts[v]; lo = std::min(lo, z); hi = std::max(hi, z); }
    }
    require(samples > 0, "height grid has no walkable samples");
    if (unassigned) {
        char buf[160];
        std::snprintf(buf, sizeof buf, "height grid contains surfaces assigned to no storey or stair: %zu/%zu samples, %.3f..%.3f yd",
                      unassigned, samples, lo, hi);
        throw GeometryError(buf);
    }
}

BuildingFiles buildingFiles(const std::string& key, const Model& m, const std::string& path, const std::vector<PlacedBuilding>& placements,
                            const YieldFn& yield) {
    BuildingFiles out;
    for (const Level& lv : m.levels) {
        require(safeName(lv.img), "invalid image name");
        out.files[lv.img] = pngEncode(lv.image);
        if (!lv.outline.empty()) out.files[lv.outline] = pngEncode(lv.outlineImage);
        if (yield) yield();
    }
    out.files[m.grid.file] = m.grid.compressed;
    out.model = modelJson(m, path);
    validateModel(out.model, out.files, yield);
    Json models = Json::object();
    models[key] = out.model;
    Json interiors = Json::object();
    interiors["models"] = models;
    Json places = Json::object();
    for (const PlacedBuilding& p : placements) {
        require(p.continent >= 0, "placement index must be keyed by continent");
        places[std::to_string(p.continent)].push(placeRecord(key, out.model, p.placement));
    }
    auto bytes = [](const Json& j) { const std::string s = j.dump(); return Bytes(s.begin(), s.end()); };
    out.files["models.json"] = bytes(models);
    out.files["interiors.json"] = bytes(interiors);
    out.files["places.json"] = bytes(places);
    for (auto& kv : out.files) require(!kv.second.empty() && kv.second.size() <= 32u * 1024 * 1024, "building output file outside the store's size limits");
    return out;
}

void writeFiles(const std::string& directory, const std::map<std::string, Bytes>& files) {
    for (auto& kv : files) {
        require(safeName(kv.first), "invalid output name");
        const std::string final = directory + "/" + kv.first, temp = final + ".part";
        FILE* f = std::fopen(temp.c_str(), "wb");
        require(f != nullptr, "cannot write " + kv.first);
        const bool ok = std::fwrite(kv.second.data(), 1, kv.second.size(), f) == kv.second.size();
        const bool closed = std::fclose(f) == 0;
        require(ok && closed, "cannot write " + kv.first);
        std::remove(final.c_str());
        require(std::rename(temp.c_str(), final.c_str()) == 0, "cannot publish " + kv.first);
    }
}

}  // namespace wc
