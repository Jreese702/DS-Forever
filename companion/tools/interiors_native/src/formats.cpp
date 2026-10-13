#include "formats.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <unordered_map>

namespace wc {

void forEachChunk(Span data, const std::function<void(const std::string&, Span)>& f) {
    size_t offset = 0;
    while (offset < data.n) {
        require(data.n - offset >= 8, "truncated WMO chunk header");
        std::string tag(4, ' ');
        for (int i = 0; i < 4; ++i) tag[i] = static_cast<char>(data.p[offset + 3 - i]);
        const uint32_t size = data.u32(offset + 4);
        const size_t end = offset + 8 + static_cast<size_t>(size);
        require(end <= data.n, "truncated " + tag + " chunk");
        f(tag, data.sub(offset + 8, size));
        offset = end;
    }
}

ChunkMap chunkMap(Span data) {
    ChunkMap out;
    forEachChunk(data, [&](const std::string& tag, Span body) {
        if (out.has(tag)) {
            require(tag == "MOTV" || tag == "MOCV", "duplicate " + tag + " chunk");
            return;
        }
        out.chunks[tag] = body;
    });
    return out;
}

static bool finite(double v) { return std::isfinite(v); }

Root wmoRoot(Bytes data) {
    Root root;
    root.data = std::move(data);
    root.ch = chunkMap(Span(root.data));
    Span header = root.ch.get("MOHD");
    require(header.n >= 64, "missing WMO header");
    const uint32_t count = header.u32(4);
    Span info = root.ch.get("MOGI"), refs = root.ch.get("GFID");
    require(count > 0 && count <= 4096 && info.n == count * 32ull, "invalid declared group metadata");
    require(refs.n >= count * 4ull && refs.n % (count * 4ull) == 0, "missing or invalid GFID groups");
    for (uint32_t i = 0; i < count; ++i) {
        const uint32_t fid = refs.u32(i * 4);
        require(fid != 0, "unresolved full-detail group ID");
        root.groupIds.push_back(fid);
        root.flags.push_back(info.u32(i * 32));
        std::array<double, 6> b{};
        for (int j = 0; j < 6; ++j) b[j] = info.f32(i * 32 + 4 + j * 4);
        for (int j = 0; j < 6; ++j) require(finite(b[j]), "invalid group bounds");
        for (int j = 0; j < 3; ++j) require(b[j] <= b[j + 3], "invalid group bounds");
        root.bounds.push_back(b);
    }
    return root;
}

static std::vector<V3> vectors(Span data, const std::string& label) {
    require(data.n % 12 == 0, "invalid " + label + " vector size");
    std::vector<V3> out(data.n / 12);
    for (size_t i = 0; i < out.size(); ++i) {
        out[i] = {data.f32(i * 12), data.f32(i * 12 + 4), data.f32(i * 12 + 8)};
        require(finite(out[i].x) && finite(out[i].y) && finite(out[i].z), "non-finite " + label + " vectors");
    }
    return out;
}

Group wmoGroup(Span data, const Root& root, int index) {
    require(index >= 0 && index < static_cast<int>(root.groupIds.size()), "group index outside current root");
    ChunkMap outer = chunkMap(data);
    Span parent = outer.get("MOGP");
    require(parent.n >= 68, "missing MOGP header");
    Group g;
    for (int k = 0; k < 6; ++k) g.headerBounds[k] = parent.f32(12 + k * 4);
    for (int k = 0; k < 6; ++k) require(finite(g.headerBounds[k]), "invalid MOGP group bounds");
    for (int k = 0; k < 3; ++k) require(g.headerBounds[k] <= g.headerBounds[k + 3], "invalid MOGP group bounds");
    const int32_t nameOffset = static_cast<int32_t>(parent.u32(0));
    Span names = root.ch.get("MOGN");
    if (nameOffset >= 0 && !names.empty()) {
        require(static_cast<size_t>(nameOffset) < names.n &&
                    std::memchr(names.p + nameOffset, 0, names.n - nameOffset) != nullptr,
                "invalid MOGP group name offset");
        g.name = reinterpret_cast<const char*>(names.p + nameOffset);
    }
    ChunkMap ch = chunkMap(parent.sub(68, parent.n - 68));
    g.gi = index;
    g.fid = root.groupIds[index];
    g.flags = root.flags[index];
    g.bb = root.bounds[index];
    g.v = vectors(ch.get("MOVT"), "MOVT");
    Span raw = ch.get("MOVI");
    require(raw.n % 6 == 0, "invalid triangle index size");
    g.tri.resize(raw.n / 6);
    int32_t maxIndex = -1;
    for (size_t i = 0; i < g.tri.size(); ++i)
        for (int k = 0; k < 3; ++k) {
            g.tri[i][k] = raw.u16(i * 6 + k * 2);
            maxIndex = std::max(maxIndex, g.tri[i][k]);
        }
    require(g.tri.empty() || (!g.v.empty() && maxIndex < static_cast<int32_t>(g.v.size())), "triangle index outside vertices");
    const bool modern = ch.has("MPY2");
    g.collisionFormat = modern ? "MPY2" : "MOPY";
    Span flags = ch.get(g.collisionFormat);
    require(flags.n == g.tri.size() * (modern ? 4 : 2),
            "group " + std::to_string(g.fid) + ": missing or mismatched " + g.collisionFormat + " collision flags");
    g.mopy.resize(g.tri.size());
    for (size_t i = 0; i < g.tri.size(); ++i) g.mopy[i] = modern ? flags.u16(i * 4) : flags.u8(i * 2);
    if (ch.has("MONR")) {
        g.monr = vectors(ch.get("MONR"), "MONR");
        require(g.monr.size() == g.v.size(), "mismatched vertex normals");
        g.hasMonr = true;
    }
    return g;
}

M2Collision m2Collision(Span data) {
    Span body = data;
    if (!(data.n >= 4 && std::memcmp(data.p, "MD20", 4) == 0)) {
        size_t offset = 0;
        bool found = false;
        while (offset < data.n) {
            require(data.n - offset >= 8, "truncated M2 chunk header");
            const uint32_t size = data.u32(offset + 4);
            const size_t end = offset + 8 + static_cast<size_t>(size);
            require(end <= data.n, "truncated M2 chunk body");
            if (std::memcmp(data.p + offset, "MD21", 4) == 0) {
                require(!found, "duplicate MD21 body");
                body = data.sub(offset + 8, size);
                found = true;
            }
            offset = end;
        }
        if (!found) body = Span();
    }
    require(body.n >= 0xF0 && std::memcmp(body.p, "MD20", 4) == 0, "unsupported M2 header");
    const uint32_t version = body.u32(4);
    require(version == 264 || version == 265 || version == 272 || version == 273 || version == 274,
            "unsupported M2 collision layout " + std::to_string(version));
    auto array = [&](size_t at, size_t itemSize, size_t width, uint32_t& count) -> size_t {
        count = body.u32(at);
        const uint32_t offset = body.u32(at + 4);
        const uint64_t size = static_cast<uint64_t>(count) * itemSize * width;
        if (count) require(offset >= 0xF0 && offset + size <= body.n, "M2 collision array outside body");
        return offset;
    };
    uint32_t ni, nv, nn;
    const size_t oi = array(0xD8, 2, 1, ni), ov = array(0xE0, 4, 3, nv), on = array(0xE8, 4, 3, nn);
    M2Collision out;
    out.v.resize(nv);
    for (uint32_t i = 0; i < nv; ++i) {
        out.v[i] = {body.f32(ov + i * 12), body.f32(ov + i * 12 + 4), body.f32(ov + i * 12 + 8)};
    }
    std::vector<V3> normals(nn);
    for (uint32_t i = 0; i < nn; ++i)
        normals[i] = {body.f32(on + i * 12), body.f32(on + i * 12 + 4), body.f32(on + i * 12 + 8)};
    require(ni % 3 == 0, "invalid M2 collision indices");
    out.tri.resize(ni / 3);
    for (uint32_t i = 0; i < ni; ++i) {
        const uint16_t index = body.u16(oi + i * 2);
        require(index < nv, "invalid M2 collision indices");
        out.tri[i / 3][i % 3] = index;
    }
    for (auto& p : out.v) require(finite(p.x) && finite(p.y) && finite(p.z), "non-finite M2 collision geometry");
    for (auto& p : normals) require(finite(p.x) && finite(p.y) && finite(p.z), "non-finite M2 collision geometry");
    require(nn == 0 || nn == out.tri.size(), "invalid M2 face normal count");
    if (nn) {
        for (size_t i = 0; i < out.tri.size(); ++i) {
            auto& t = out.tri[i];
            const V3 c = cross(out.v[t[1]] - out.v[t[0]], out.v[t[2]] - out.v[t[0]]);
            if (dot(c, normals[i]) < 0) std::swap(t[1], t[2]);
        }
    }
    return out;
}

static std::array<std::array<double, 3>, 3> rotation(const double q4[4]) {
    double n = std::sqrt(q4[0] * q4[0] + q4[1] * q4[1] + q4[2] * q4[2] + q4[3] * q4[3]);
    for (int i = 0; i < 4; ++i) require(finite(q4[i]), "invalid doodad quaternion");
    require(n >= 0.98 && n <= 1.02, "invalid doodad quaternion");
    const double x = q4[0] / n, y = q4[1] / n, z = q4[2] / n, w = q4[3] / n;
    return {{{1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
             {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
             {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)}}};
}

static std::vector<int> doodadRecords(const Root& root, int setIndex) {
    Span records = root.ch.get("MODD"), sets = root.ch.get("MODS"), ids = root.ch.get("MODI");
    if (records.empty()) return {};
    require(records.n % 40 == 0 && sets.n % 32 == 0 && ids.n % 4 == 0, "invalid doodad chunk size");
    const int count = static_cast<int>(sets.n / 32);
    require(setIndex >= 0 && setIndex < count, "placement doodad set is unavailable");
    std::set<int> wanted;
    for (int chosen : std::set<int>{0, setIndex}) {
        const uint32_t first = sets.u32(chosen * 32 + 20), size = sets.u32(chosen * 32 + 24);
        require(static_cast<uint64_t>(first) + size <= records.n / 40, "doodad set outside record array");
        for (uint32_t i = first; i < first + size; ++i) wanted.insert(static_cast<int>(i));
    }
    return {wanted.begin(), wanted.end()};
}

static uint32_t doodadFid(const Root& root, int record) {
    Span records = root.ch.get("MODD"), ids = root.ch.get("MODI");
    const uint32_t slot = records.u32(record * 40) & 0xFFFFFF;
    require(slot < ids.n / 4, "doodad model index outside MODI");
    return ids.u32(slot * 4);
}

std::vector<uint32_t> doodadFids(const Root& root, int setIndex) {
    std::set<uint32_t> out;
    for (int i : doodadRecords(root, setIndex)) {
        const uint32_t fid = doodadFid(root, i);
        if (fid) out.insert(fid);
    }
    return {out.begin(), out.end()};
}

std::vector<Group> doodadGroups(const Root& root, int setIndex, const FileReader& read, DoodadStats& stats,
                                std::vector<uint32_t>* fidsOut, const YieldFn& onModel) {
    std::vector<Group> groups;
    stats = DoodadStats();
    if (root.ch.get("MODD").empty()) return groups;
    const std::vector<int> wanted = doodadRecords(root, setIndex);
    Span records = root.ch.get("MODD");
    std::unordered_map<uint32_t, M2Collision> cache;
    for (int i : wanted) {
        const size_t at = static_cast<size_t>(i) * 40;
        const uint32_t fid = doodadFid(root, i);
        if (fid == 0) continue;
        auto it = cache.find(fid);
        if (it == cache.end()) {
            Bytes data;
            require(read(fid, data), "doodad file " + std::to_string(fid) + " unavailable");
            it = cache.emplace(fid, m2Collision(Span(data))).first;
            if (fidsOut) fidsOut->push_back(fid);
            if (onModel) onModel();
        }
        const M2Collision& m = it->second;
        if (m.tri.empty()) continue;
        const V3 pos{records.f32(at + 4), records.f32(at + 8), records.f32(at + 12)};
        const double quat[4] = {records.f32(at + 16), records.f32(at + 20), records.f32(at + 24), records.f32(at + 28)};
        const double scale = records.f32(at + 32);
        require(finite(pos.x) && finite(pos.y) && finite(pos.z) && finite(scale) && scale > 0, "invalid doodad placement");
        const auto r = rotation(quat);
        Group g;
        g.gi = -(i + 1);
        g.fid = fid;
        g.flags = 8;
        g.source = Source::Doodad;
        g.v.resize(m.v.size());
        for (size_t k = 0; k < m.v.size(); ++k) {
            const V3& p = m.v[k];
            // (verts @ R.T) * scale + pos
            g.v[k] = V3{(r[0][0] * p.x + r[0][1] * p.y + r[0][2] * p.z) * scale + pos.x,
                        (r[1][0] * p.x + r[1][1] * p.y + r[1][2] * p.z) * scale + pos.y,
                        (r[2][0] * p.x + r[2][1] * p.y + r[2][2] * p.z) * scale + pos.z};
        }
        g.tri = m.tri;
        g.mopy.assign(g.tri.size(), 8);
        g.placementPos = pos;
        g.recordIndex = i;
        g.scale = scale;
        g.bb = {0, 0, 0, 0, 0, 0};
        groups.push_back(std::move(g));
    }
    stats.placed = static_cast<int>(wanted.size());
    stats.solid = static_cast<int>(groups.size());
    stats.uniqueModels = static_cast<int>(cache.size());
    return groups;
}

std::vector<Door> exitDoors(const Root& root, const std::set<int>& selected, bool includeInternal) {
    const std::vector<V3> points = vectors(root.ch.get("MOPV"), "portal");
    Span info = root.ch.get("MOPT"), refs = root.ch.get("MOPR");
    require(info.n % 20 == 0 && refs.n % 8 == 0, "invalid portal arrays");
    struct Portal { int start, count; float plane[4]; std::vector<int> groups; };
    std::vector<Portal> portals;
    for (size_t at = 0; at < info.n; at += 20) {
        Portal p;
        p.start = info.u16(at);
        p.count = info.u16(at + 2);
        require(static_cast<size_t>(p.start) + p.count <= points.size(), "portal outside vertex array");
        for (int k = 0; k < 4; ++k) p.plane[k] = info.f32(at + 4 + k * 4);
        portals.push_back(p);
    }
    for (size_t at = 0; at < refs.n; at += 8) {
        const uint16_t portal = refs.u16(at), group = refs.u16(at + 2);
        require(portal < portals.size() && group < root.groupIds.size(), "portal references unavailable group");
        portals[portal].groups.push_back(group);
    }
    std::vector<Door> doors;
    for (const Portal& portal : portals) {
        std::vector<int> inside;
        bool outside = false;
        for (int gi : portal.groups) {
            if (!(root.flags[gi] & 8)) inside.push_back(gi);
            else outside = true;
        }
        bool any = false, boundary = false;
        for (int gi : inside) {
            any = any || selected.count(gi);
            boundary = boundary || (includeInternal && !selected.count(gi));
        }
        if (!any || !(outside || portal.groups.size() == 1 || boundary)) continue;
        if (portal.count < 2 || std::fabs(portal.plane[2]) >= 0.7) continue;
        // argmax over the full pairwise squared-distance matrix, first occurrence in row-major order
        double best = -1;
        int ba = 0, bb = 0;
        for (int i = 0; i < portal.count; ++i)
            for (int j = 0; j < portal.count; ++j) {
                const V3 &p = points[portal.start + i], &q = points[portal.start + j];
                const double d = (p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y);
                if (d > best) { best = d; ba = i; bb = j; }
            }
        if (best < 0.25) continue;
        Door d;
        const V3 &pa = points[portal.start + ba], &pb = points[portal.start + bb];
        d.a[0] = pa.x; d.a[1] = pa.y; d.b[0] = pb.x; d.b[1] = pb.y;
        d.zmin = std::numeric_limits<double>::infinity();
        d.zmax = -d.zmin;
        for (int i = 0; i < portal.count; ++i) {
            d.zmin = std::min(d.zmin, points[portal.start + i].z);
            d.zmax = std::max(d.zmax, points[portal.start + i].z);
        }
        d.insideGroup = -1;
        for (int gi : inside) if (selected.count(gi)) { d.insideGroup = gi; break; }
        doors.push_back(d);
    }
    return doors;
}

std::vector<std::vector<int>> portalComponents(const Root& root) {
    std::set<int> selected;
    for (size_t i = 0; i < root.flags.size(); ++i) if (!(root.flags[i] & 8)) selected.insert(static_cast<int>(i));
    std::map<int, int> parent;
    for (int i : selected) parent[i] = i;
    std::function<int(int)> find = [&](int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    };
    Span refs = root.ch.get("MOPR"), info = root.ch.get("MOPT");
    const std::vector<V3> points = vectors(root.ch.get("MOPV"), "portal");
    require(refs.n % 8 == 0 && info.n % 20 == 0, "invalid portal arrays");
    for (size_t at = 0; at < info.n; at += 20)
        require(static_cast<size_t>(info.u16(at)) + info.u16(at + 2) <= points.size(), "portal outside vertex array");
    std::map<int, std::set<int>> portals;
    for (size_t at = 0; at < refs.n; at += 8) {
        const int portal = refs.u16(at), group = refs.u16(at + 2);
        require(portal < static_cast<int>(info.n / 20) && group < static_cast<int>(root.groupIds.size()),
                "portal references undeclared group");
        if (selected.count(group)) portals[portal].insert(group);
    }
    for (auto& kv : portals) {
        std::vector<int> members(kv.second.begin(), kv.second.end());
        for (size_t k = 1; k < members.size(); ++k) {
            const int a = find(members[0]), b = find(members[k]);
            parent[std::max(a, b)] = std::min(a, b);
        }
    }
    std::map<int, std::vector<int>> result;
    for (int gi : selected) result[find(gi)].push_back(gi);
    std::vector<std::vector<int>> out;
    for (auto& kv : result) out.push_back(kv.second);
    return out;
}

std::set<int> expandSelection(const Root& root, std::set<int> selected, int hops) {
    require(hops >= 0 && hops <= 3, "portal expansion must be between zero and three hops");
    for (int gi : selected) require(gi >= 0 && gi < static_cast<int>(root.groupIds.size()), "selection references undeclared group");
    portalComponents(root);  // validates MOPR/MOPT/MOPV before the flag reads below
    std::map<int, std::set<int>> portals;
    Span refs = root.ch.get("MOPR");
    for (size_t at = 0; at < refs.n; at += 8) {
        const int portal = refs.u16(at), gi = refs.u16(at + 2);
        if (!(root.flags[gi] & 8)) portals[portal].insert(gi);
    }
    for (int h = 0; h < hops; ++h) {
        std::set<int> add;
        for (auto& kv : portals) {
            bool touch = false;
            for (int gi : kv.second) touch = touch || selected.count(gi);
            if (touch) add.insert(kv.second.begin(), kv.second.end());
        }
        selected.insert(add.begin(), add.end());
    }
    return selected;
}

static constexpr double TILE = 1600.0 / 3, ORIGIN = 32 * TILE, UNIT = TILE / 128;

V3 Placement::toLocal(const V3& w) const {
    const double dx = w.x - origin.x, dy = w.y - origin.y, dz = w.z - origin.z;
    const double c = std::cos(yaw), s = std::sin(yaw);
    return {-(c * dx + s * dy) / scale, (s * dx - c * dy) / scale, dz / scale};
}

static Span singleModf(Span objAdt) {
    Span body;
    int count = 0;
    forEachChunk(objAdt, [&](const std::string& tag, Span b) {
        if (tag == "MODF") { body = b; ++count; }
    });
    require(count <= 1, "duplicate MODF chunk");
    require(body.n % 64 == 0, "invalid MODF size");
    return body;
}

std::vector<Placement> modfPlacements(Span objAdt, std::vector<std::string>* skipped) {
    Span body = singleModf(objAdt);
    std::vector<Placement> out;
    for (size_t at = 0; at < body.n; at += 64) {
        const uint32_t fid = body.u32(at), uid = body.u32(at + 4);
        const uint16_t flags = body.u16(at + 56), ds = body.u16(at + 58), rawScale = body.u16(at + 62);
        auto skip = [&](const char* why) { if (skipped) skipped->push_back(std::to_string(fid) + ":" + std::to_string(uid) + " " + why); };
        if (!(flags & 8)) { skip("no fileDataID"); continue; }
        if (flags & 128) { skip("MWDS placement sets unsupported"); continue; }
        const double pos[3] = {body.f32(at + 8), body.f32(at + 12), body.f32(at + 16)};
        const double rot[3] = {body.f32(at + 20), body.f32(at + 24), body.f32(at + 28)};
        bool ok = true;
        for (int k = 0; k < 3; ++k) ok = ok && finite(pos[k]) && finite(rot[k]);
        if (!ok) { skip("non-finite MODF transform"); continue; }
        if (std::fabs(rot[0]) > 0.001 || std::fabs(rot[2]) > 0.001) { skip("tilted placement"); continue; }
        const double scale = (flags & 4) ? rawScale / 1024.0 : 1.0;
        if (scale <= 0) { skip("invalid MODF scale"); continue; }
        Placement p;
        p.fid = fid;
        p.uniqueId = uid;
        p.dset = ds;
        p.origin = {ORIGIN - pos[2], ORIGIN - pos[0], pos[1]};
        p.yaw = rot[1] * kPi / 180.0;
        p.scale = scale;
        out.push_back(p);
    }
    return out;
}

Placement placement(Span objAdt, uint32_t rootFid, int dset, int64_t uniqueId) {
    Span body = singleModf(objAdt);
    std::vector<Placement> matches;
    for (size_t at = 0; at < body.n; at += 64) {
        const uint32_t fid = body.u32(at), uid = body.u32(at + 4);
        const uint16_t flags = body.u16(at + 56), ds = body.u16(at + 58), rawScale = body.u16(at + 62);
        if (!(flags & 8) || fid != rootFid || (uniqueId >= 0 && uid != uniqueId)) continue;
        require(!(flags & 128), "MWDS placement sets are not supported by this host reader");
        if (ds != dset) continue;
        const double pos[3] = {body.f32(at + 8), body.f32(at + 12), body.f32(at + 16)};
        const double rot[3] = {body.f32(at + 20), body.f32(at + 24), body.f32(at + 28)};
        for (int k = 0; k < 3; ++k) require(finite(pos[k]) && finite(rot[k]), "non-finite MODF transform");
        require(std::fabs(rot[0]) <= 0.001 && std::fabs(rot[2]) <= 0.001, "tilted WMO terrain transform needs full rotation support");
        const double scale = (flags & 4) ? rawScale / 1024.0 : 1.0;
        require(scale > 0, "invalid MODF scale");
        Placement p;
        p.fid = fid; p.uniqueId = uid; p.dset = ds;
        p.origin = {ORIGIN - pos[2], ORIGIN - pos[0], pos[1]};
        p.yaw = rot[1] * kPi / 180.0;
        p.scale = scale;
        matches.push_back(p);
    }
    require(matches.size() == 1, "expected one matching placement, found " + std::to_string(matches.size()) + "; supply its unique ID");
    return matches[0];
}

std::vector<Group> terrainGroups(Span rootAdt, const Placement& placed, const std::array<double, 4>& box, uint32_t fid,
                                 double pad) {
    std::set<std::pair<int, int>> seen;
    std::vector<Group> groups;
    int64_t version = -1;
    forEachChunk(rootAdt, [&](const std::string& tag, Span body) {
        if (tag == "MVER") {
            require(version < 0 && body.n == 4, "invalid ADT version chunk");
            version = body.u32(0);
        }
        if (tag != "MCNK") return;
        require(version == 18, "unsupported ADT version");
        require(body.n >= 128, "truncated MCNK header");
        const uint32_t flags = body.u32(0), ix = body.u32(4), iy = body.u32(8);
        require(ix < 16 && iy < 16, "MCNK index outside tile");
        const V3 pos{body.f32(104), body.f32(108), body.f32(112)};
        require(finite(pos.x) && finite(pos.y) && finite(pos.z), "non-finite MCNK origin");
        require(body.n >= 136 && std::memcmp(body.p + 128, "TVCM", 4) == 0, "unsupported MCNK height layout");
        const uint32_t size = body.u32(132);
        require(size == 145 * 4 && body.n >= 136 + size, "invalid MCVT heights");
        std::vector<V3> verts;
        int outer[9][9], inner[8][8];
        for (int row = 0; row < 17; ++row) {
            const int odd = row % 2;
            for (int col = 0; col < (odd ? 8 : 9); ++col) {
                if (odd) inner[row / 2][col] = static_cast<int>(verts.size());
                else outer[row / 2][col] = static_cast<int>(verts.size());
                const double h = body.f32(136 + verts.size() * 4);
                require(finite(h), "non-finite MCVT heights");
                verts.push_back({pos.x - row * UNIT / 2, pos.y - (col + odd / 2.0) * UNIT, pos.z + h});
            }
        }
        const uint64_t high = body.u64(20);
        const uint16_t low = body.u16(60);
        std::vector<std::array<int32_t, 3>> tris;
        for (int row = 0; row < 8; ++row)
            for (int col = 0; col < 8; ++col) {
                const bool hole = (flags & 0x10000) ? (high & (1ull << (row * 8 + col))) != 0
                                                    : (low & (1u << ((row / 2) * 4 + col / 2))) != 0;
                if (hole) continue;
                const int a = outer[row][col], b = outer[row + 1][col], c = outer[row + 1][col + 1], d = outer[row][col + 1];
                const int center = inner[row][col];
                tris.push_back({a, b, center});
                tris.push_back({b, c, center});
                tris.push_back({c, d, center});
                tris.push_back({d, a, center});
            }
        const std::pair<int, int> index{static_cast<int>(ix), static_cast<int>(iy)};
        require(!seen.count(index), "duplicate MCNK cell");
        seen.insert(index);
        for (auto& p : verts) p = placed.toLocal(p);
        std::vector<std::array<int32_t, 3>> keep;
        for (auto& t : tris) {
            double lo[2] = {1e300, 1e300}, hi[2] = {-1e300, -1e300};
            for (int k = 0; k < 3; ++k)
                for (int j = 0; j < 2; ++j) {
                    lo[j] = std::min(lo[j], verts[t[k]][j]);
                    hi[j] = std::max(hi[j], verts[t[k]][j]);
                }
            if (hi[0] >= box[0] - pad && lo[0] <= box[1] + pad && hi[1] >= box[2] - pad && lo[1] <= box[3] + pad) keep.push_back(t);
        }
        if (keep.empty()) return;
        Group g;
        g.gi = -100000 - static_cast<int>(iy) * 16 - static_cast<int>(ix);
        g.fid = fid;
        g.flags = 8;
        g.source = Source::Terrain;
        g.v = std::move(verts);
        g.tri = std::move(keep);
        g.mopy.assign(g.tri.size(), 8);
        groups.push_back(std::move(g));
    });
    require(version == 18 && seen.size() == 256, "root ADT must contain all 256 terrain cells");
    return groups;
}

std::vector<TerrainTile> coveringTiles(Span wdt, const Placement& placed, const std::array<double, 4>& box, double pad) {
    ChunkMap ch = chunkMap(wdt);
    Span mver = ch.get("MVER"), mphd = ch.get("MPHD");
    require(mver.n == 4 && mver.u32(0) == 18 && mphd.n == 32, "unsupported WDT header");
    const uint32_t flags = mphd.u32(0);
    require(!(flags & 1) && !(flags & 32), "global-WMO or inverted-terrain maps need a separate terrain path");
    Span main = ch.get("MAIN"), maid = ch.get("MAID");
    require((flags & 512) && main.n == 4096 * 8 && maid.n == 4096 * 32,
            "current terrain discovery requires a complete WDT MAIN/MAID index");
    for (double b : box) require(finite(b), "invalid terrain coverage bounds");
    require(box[0] < box[1] && box[2] < box[3] && pad >= 0, "invalid terrain coverage bounds");
    const double c = std::cos(placed.yaw), s = std::sin(placed.yaw);
    std::vector<int> cols, rows;
    for (double x : {box[0] - pad, box[1] + pad})
        for (double y : {box[2] - pad, box[3] + pad}) {
            const double wx = placed.origin.x - x * placed.scale * c + y * placed.scale * s;
            const double wy = placed.origin.y - x * placed.scale * s - y * placed.scale * c;
            cols.push_back(static_cast<int>(std::floor((ORIGIN - wy) / TILE)));
            rows.push_back(static_cast<int>(std::floor((ORIGIN - wx) / TILE)));
        }
    const int c0 = *std::min_element(cols.begin(), cols.end()), c1 = *std::max_element(cols.begin(), cols.end());
    const int r0 = *std::min_element(rows.begin(), rows.end()), r1 = *std::max_element(rows.begin(), rows.end());
    require(std::min(c0, r0) >= 0 && std::max(c1, r1) < 64, "terrain coverage lies outside the WDT grid");
    std::vector<TerrainTile> out;
    for (int row = r0; row <= r1; ++row)
        for (int col = c0; col <= c1; ++col) {
            const int index = row * 64 + col;
            const bool present = main.u32(index * 8) & 1;
            TerrainTile t{col, row, maid.u32(index * 32), maid.u32(index * 32 + 4), maid.u32(index * 32 + 8)};
            require(present && t.root, "required terrain tile " + std::to_string(col) + "," + std::to_string(row) +
                                           " absent from current WDT");
            out.push_back(t);
        }
    return out;
}

int64_t sampleCount(const V3& a, const V3& b, const V3& c, double step) {
    const double length = std::max({norm(b - a), norm(c - b), norm(a - c)});
    const int64_t d = std::min<int64_t>(400, std::max<int64_t>(1, static_cast<int64_t>(std::ceil(length / step))));
    return (d + 1) * (d + 2) / 2;
}

}  // namespace wc
