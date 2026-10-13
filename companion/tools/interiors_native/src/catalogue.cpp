#include "catalogue.h"

#include "sha256.h"

#include <algorithm>
#include <cmath>
#include <map>

namespace wc {
namespace {
std::string intList(const std::vector<uint32_t>& v) {
    std::string s = "[";
    for (size_t i = 0; i < v.size(); ++i) s += (i ? "," : "") + std::to_string(v[i]);
    return s + "]";
}
// Python's str() of a (tx, ty) tuple, the PC's bucket sort key
std::string tupleRepr(int64_t a, int64_t b) { return "(" + std::to_string(a) + ", " + std::to_string(b) + ")"; }
}  // namespace

Json catalogueUnits(const Root& root, uint32_t rootFid, int dset, double tileSize) {
    Span sets = root.ch.get("MODS");
    require(sets.n % 32 == 0 && dset >= 0, "invalid doodad set");
    require(!((sets.n && dset >= static_cast<int>(sets.n / 32)) || (!sets.n && dset != 0)), "placement doodad set unavailable");
    if (tileSize > 0) require(tileSize >= 10 && tileSize <= 1000, "work tile size must be between 10 and 1000 yards");
    Json out = Json::object();
    for (const std::vector<int>& groups : portalComponents(root)) {
        std::vector<uint32_t> fids;
        for (int gi : groups) fids.push_back(root.groupIds[gi]);
        std::sort(fids.begin(), fids.end());
        const std::string identity = "[" + std::to_string(rootFid) + "," + std::to_string(dset) + "," + intList(fids) + "]";
        const std::string key = std::to_string(rootFid) + "_d" + std::to_string(dset) + "_g" + sha256Hex(identity).substr(0, 16);
        double lo0 = 1e300, lo1 = 1e300, hi0 = -1e300, hi1 = -1e300;
        for (int gi : groups) {
            lo0 = std::min(lo0, root.bounds[gi][0]); lo1 = std::min(lo1, root.bounds[gi][1]);
            hi0 = std::max(hi0, root.bounds[gi][3]); hi1 = std::max(hi1, root.bounds[gi][4]);
        }
        struct Bucket { bool tiled; int64_t tx, ty; std::vector<int> members; };
        std::vector<Bucket> buckets;
        if (tileSize > 0 && std::max(hi0 - lo0, hi1 - lo1) > tileSize) {
            std::map<std::pair<int64_t, int64_t>, std::vector<int>> by;
            for (int gi : groups) {
                const auto& bb = root.bounds[gi];
                by[{static_cast<int64_t>(std::floor((bb[0] + bb[3]) / 2 / tileSize)), static_cast<int64_t>(std::floor((bb[1] + bb[4]) / 2 / tileSize))}].push_back(gi);
            }
            for (auto& kv : by) buckets.push_back({true, kv.first.first, kv.first.second, kv.second});
            std::sort(buckets.begin(), buckets.end(), [](const Bucket& a, const Bucket& b) { return tupleRepr(a.tx, a.ty) < tupleRepr(b.tx, b.ty); });
        } else {
            buckets.push_back({false, 0, 0, groups});
        }
        for (const Bucket& b : buckets) {
            std::vector<uint32_t> child;
            for (int gi : b.members) child.push_back(root.groupIds[gi]);
            std::sort(child.begin(), child.end());
            const std::string unitKey = b.tiled ? key + "_t" + sha256Hex(intList(child)).substr(0, 12) : key;
            Json e = Json::object();
            e["fid"] = rootFid;
            e["gis"] = Json::array();
            for (int gi : b.members) e["gis"].push(gi);
            e["dset"] = dset;
            e["group_fids"] = Json::array();
            for (uint32_t f : child) e["group_fids"].push(f);
            e["selection_source"] = "current-root-portals";
            double u0 = 1e300, u1 = -1e300, u2 = 1e300, u3 = -1e300;  // the unit's local box (world footprints)
            for (int gi : b.members) {
                u0 = std::min(u0, root.bounds[gi][0]); u1 = std::max(u1, root.bounds[gi][3]);
                u2 = std::min(u2, root.bounds[gi][1]); u3 = std::max(u3, root.bounds[gi][4]);
            }
            Json box = Json::array();
            for (double v : {u0, u1, u2, u3}) box.push(v);
            e["box"] = box;
            e["parent_component"] = key;
            if (b.tiled) { Json t = Json::array(); t.push(static_cast<int64_t>(b.tx)); t.push(static_cast<int64_t>(b.ty)); e["work_tile"] = t; }
            else e["work_tile"] = Json();
            out[unitKey] = e;
        }
    }
    return out;
}

namespace {
// Python's json.dumps(list of ints) with default separators
std::string pyList(const std::vector<uint32_t>& v) {
    std::string s = "[";
    for (size_t i = 0; i < v.size(); ++i) s += (i ? ", " : "") + std::to_string(v[i]);
    return s + "]";
}
}  // namespace

Json catalogueRooms(const Root& root, uint32_t rootFid, int dset, double span, double height) {
    Span sets = root.ch.get("MODS");
    require(sets.n % 32 == 0 && dset >= 0, "invalid doodad set");
    require(!((sets.n && dset >= static_cast<int>(sets.n / 32)) || (!sets.n && dset != 0)), "placement doodad set unavailable");
    require(span >= 10 && span <= 150, "room merging requires room units and a span between 10 and 150 yards");
    // portalComponents validates every MOPR/MOPT/MOPV length and index BEFORE any group flag is read below
    const std::vector<std::vector<int>> components = portalComponents(root);
    // building_catalogue.portal_members: interior groups per portal
    std::map<int, std::set<int>> portals;
    Span refs = root.ch.get("MOPR");
    for (size_t at = 0; at + 8 <= refs.n; at += 8) {
        const int portal = refs.u16(at), gi = refs.u16(at + 2);
        require(gi < static_cast<int>(root.groupIds.size()), "portal references undeclared group");
        if (!(root.flags[gi] & 8)) portals[portal].insert(gi);
    }
    Json out = Json::object();
    for (const std::vector<int>& groups : components) {
        std::vector<uint32_t> fids;
        for (int gi : groups) fids.push_back(root.groupIds[gi]);
        std::sort(fids.begin(), fids.end());
        const std::string identity = "[" + std::to_string(rootFid) + "," + std::to_string(dset) + "," + intList(fids) + "]";
        const std::string parent = std::to_string(rootFid) + "_d" + std::to_string(dset) + "_g" + sha256Hex(identity).substr(0, 16);
        // bounded_rooms(root, groups, span)
        const std::set<int> allowed(groups.begin(), groups.end());
        std::map<int, std::set<int>> adjacency;
        for (int gi : groups) adjacency[gi];
        for (auto& kv : portals) {
            std::set<int> in;
            for (int gi : kv.second) if (allowed.count(gi)) in.insert(gi);
            for (int gi : in) for (int other : in) if (other != gi) adjacency[gi].insert(other);
        }
        auto fits = [&](const std::vector<int>& members) {
            double lo[3] = {1e300, 1e300, 1e300}, hi[3] = {-1e300, -1e300, -1e300};
            for (int gi : members) {
                const auto& b = root.bounds[gi];
                if (b[3] - b[0] <= 0 || b[4] - b[1] <= 0) return false;
                for (int k = 0; k < 3; ++k) { lo[k] = std::min(lo[k], b[k]); hi[k] = std::max(hi[k], b[k + 3]); }
            }
            return std::max(hi[0] - lo[0], hi[1] - lo[1]) <= span && hi[2] - lo[2] <= height;
        };
        auto byFid = [&](int a, int b) { return root.groupIds[a] < root.groupIds[b] || (root.groupIds[a] == root.groupIds[b] && a < b); };
        std::vector<int> seeds = groups;
        std::sort(seeds.begin(), seeds.end(), byFid);
        std::set<int> remaining(groups.begin(), groups.end());
        std::vector<std::vector<int>> clusters;
        for (int seed : seeds) {
            if (!remaining.count(seed)) continue;
            std::vector<int> cluster{seed};
            remaining.erase(seed);
            if (fits(cluster)) {
                std::set<int> frontier;
                for (int gi : adjacency[seed]) if (remaining.count(gi)) frontier.insert(gi);
                while (!frontier.empty()) {
                    const int gi = *std::min_element(frontier.begin(), frontier.end(), byFid);
                    frontier.erase(gi);
                    std::vector<int> trial = cluster;
                    trial.push_back(gi);
                    if (!remaining.count(gi) || !fits(trial)) continue;
                    cluster.push_back(gi);
                    remaining.erase(gi);
                    for (int g2 : adjacency[gi]) if (remaining.count(g2)) frontier.insert(g2);
                }
            }
            std::sort(cluster.begin(), cluster.end());
            clusters.push_back(cluster);
        }
        for (const std::vector<int>& members : clusters) {
            std::vector<uint32_t> child;
            for (int gi : members) child.push_back(root.groupIds[gi]);
            std::sort(child.begin(), child.end());
            const std::string key = members.size() == 1
                ? std::to_string(rootFid) + "_d" + std::to_string(dset) + "_r" + std::to_string(child[0])
                : std::to_string(rootFid) + "_d" + std::to_string(dset) + "_b" + sha256Hex(pyList(child)).substr(0, 16);
            double lo[3] = {1e300, 1e300, 1e300}, hi[3] = {-1e300, -1e300, -1e300};
            for (int gi : members)
                for (int k = 0; k < 3; ++k) { lo[k] = std::min(lo[k], root.bounds[gi][k]); hi[k] = std::max(hi[k], root.bounds[gi][k + 3]); }
            Json e = Json::object();
            e["fid"] = rootFid;
            e["gis"] = Json::array();
            for (int gi : members) e["gis"].push(gi);
            e["dset"] = dset;
            e["group_fids"] = Json::array();
            for (uint32_t f : child) e["group_fids"].push(f);
            e["selection_source"] = "current-root-portal-room";
            e["parent_component"] = parent;
            e["work_tile"] = Json();
            e["layout_scope"] = "bounded-portal-room-block";
            e["room_extent_limit"] = span;
            e["room_height_limit"] = height;
            e["oversized"] = std::max(hi[0] - lo[0], hi[1] - lo[1]) > span || hi[2] - lo[2] > height;
            e["degenerate_ownership"] = hi[0] - lo[0] <= 0 || hi[1] - lo[1] <= 0;
            Json box = Json::array();
            for (double v : {lo[0], hi[0], lo[1], hi[1]}) box.push(v);
            e["box"] = box;
            out[key] = e;
        }
    }
    return out;
}

std::vector<uint32_t> unitInputs(const Root& root, uint32_t rootFid, const std::vector<int>& seed, int dset, int portalHops,
                                 bool clip) {
    // the selection is validated before any bounds access, whatever the hop count
    require(!seed.empty(), "empty unit selection");
    for (int gi : seed) require(gi >= 0 && gi < static_cast<int>(root.groupIds.size()), "cluster references undeclared group");
    require(portalHops >= 0 && portalHops <= 3, "portal expansion must be between zero and three hops");
    const std::set<int> seedSet(seed.begin(), seed.end());
    const std::set<int> selected = portalHops ? expandSelection(root, seedSet, portalHops) : seedSet;
    double b0 = 1e300, b1 = -1e300, b2 = 1e300, b3 = -1e300;
    for (int gi : clip ? seedSet : selected) {
        b0 = std::min(b0, root.bounds[gi][0]); b1 = std::max(b1, root.bounds[gi][3]);
        b2 = std::min(b2, root.bounds[gi][1]); b3 = std::max(b3, root.bounds[gi][4]);
    }
    std::vector<uint32_t> out{rootFid};
    for (size_t gi = 0; gi < root.groupIds.size(); ++gi) {
        const auto& b = root.bounds[gi];
        if (b[3] >= b0 - 6 && b[0] <= b1 + 6 && b[4] >= b2 - 6 && b[1] <= b3 + 6) out.push_back(root.groupIds[gi]);
    }
    for (uint32_t f : doodadFids(root, dset)) out.push_back(f);
    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    return out;
}

Json placementsJson(Span file, bool wdt) {
    Json out = Json::object();
    std::vector<std::string> skipped;
    std::vector<Placement> found;
    if (wdt) {
        bool has = false;
        forEachChunk(file, [&](const std::string& tag, Span) { has = has || tag == "MODF"; });
        if (has) found = modfPlacements(file, &skipped);
    } else {
        found = modfPlacements(file, &skipped);
    }
    Json list = Json::array();
    for (const Placement& p : found) {
        Json j = Json::object();
        j["fid"] = p.fid;
        j["uid"] = p.uniqueId;
        j["dset"] = p.dset;
        Json o = Json::array();
        o.push(p.origin.x); o.push(p.origin.y); o.push(p.origin.z);
        j["origin"] = o;
        j["yaw"] = p.yaw;
        j["scale"] = p.scale;
        list.push(j);
    }
    out["placements"] = list;
    Json sk = Json::array();
    for (auto& s : skipped) sk.push(s);
    out["skipped"] = sk;
    return out;
}

std::vector<uint32_t> wdtObjectTiles(Span wdt, bool& wmoOnly) {
    ChunkMap ch = chunkMap(wdt);
    Span mphd = ch.get("MPHD");
    require(mphd.n >= 4, "unsupported WDT header");
    const uint32_t flags = mphd.u32(0);
    wmoOnly = (flags & 1) != 0;
    std::vector<uint32_t> out;
    if (wmoOnly) return out;
    Span main = ch.get("MAIN"), maid = ch.get("MAID");
    require(main.n == 4096 * 8 && maid.n == 4096 * 32, "current terrain discovery requires a complete WDT MAIN/MAID index");
    for (int i = 0; i < 4096; ++i) {
        if (!(main.u32(i * 8) & 1)) continue;
        const uint32_t obj0 = maid.u32(i * 32 + 4);
        if (obj0) out.push_back(obj0);
    }
    return out;
}

}  // namespace wc
