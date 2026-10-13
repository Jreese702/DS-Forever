#include "pipeline.h"

#include "clip.h"

#include <algorithm>

namespace wc {

// A declared profile must carry exactly its own settings (catalogue, spec, digest and produce cannot drift apart).
void checkProfile(const Spec& s) {
    if (s.profileId.empty()) return;
    if (s.profileId == "legacy-r1")
        require(s.portalHops == 1 && !s.contextOnly && !s.clip && !s.portalOwnedRooms && s.tileSize == 60,
                "profile settings disagree with profile legacy-r1");
    else if (s.profileId == "city-room-r1")
        require(s.portalHops == 1 && s.contextOnly && s.clip && s.portalOwnedRooms && s.tileSize == 60 && s.roomSpan == 120 &&
                    s.roomHeight == 80 && !s.terrain,
                "profile settings disagree with profile city-room-r1");
    else
        throw GeometryError("unknown generation profile " + s.profileId);
}

Prepared prepare(const Spec& spec, const FileReader& rawRead, const YieldFn& yield) {
    checkProfile(spec);
    auto step = [&] { if (yield) yield(); };
    Prepared p;
    FileReader read = [&](uint32_t fid, Bytes& out) {
        if (!rawRead(fid, out)) return false;
        p.reads[fid] = out.size();
        return true;
    };
    Bytes rootBytes;
    require(read(spec.root, rootBytes), "root " + std::to_string(spec.root) + " unavailable");
    p.root = wmoRoot(std::move(rootBytes));
    const Root& root = p.root;
    std::set<int> seed(spec.seed.begin(), spec.seed.end());
    require(!seed.empty() && *seed.begin() >= 0 && *seed.rbegin() < static_cast<int>(root.groupIds.size()),
            "cluster references undeclared group");
    auto boundsOf = [&](const std::set<int>& gis) {
        std::array<double, 4> b{1e300, -1e300, 1e300, -1e300};
        for (int gi : gis) {
            b[0] = std::min(b[0], root.bounds[gi][0]);
            b[1] = std::max(b[1], root.bounds[gi][3]);
            b[2] = std::min(b[2], root.bounds[gi][1]);
            b[3] = std::max(b[3], root.bounds[gi][4]);
        }
        return b;
    };
    if (spec.portalOwnedRooms) {
        require(spec.contextOnly, "--portal-owned-rooms requires Recast and context-only neighbors");
        if (!spec.groupFids.empty()) {
            std::vector<uint32_t> have, want = spec.groupFids;
            for (int gi : seed) have.push_back(root.groupIds[gi]);
            std::sort(have.begin(), have.end());
            std::sort(want.begin(), want.end());
            require(have == want, "catalogue group identities changed; refresh from the current root");
        }
        double lo[3] = {1e300, 1e300, 1e300}, hi[3] = {-1e300, -1e300, -1e300};
        for (int gi : seed)
            for (int k = 0; k < 3; ++k) { lo[k] = std::min(lo[k], root.bounds[gi][k]); hi[k] = std::max(hi[k], root.bounds[gi][k + 3]); }
        require(!(hi[0] - lo[0] <= 0 || hi[1] - lo[1] <= 0), "room has no nondegenerate ownership footprint");
        require(!(std::max(hi[0] - lo[0], hi[1] - lo[1]) > spec.roomSpan || hi[2] - lo[2] > spec.roomHeight),
                "room exceeds the bounded layout policy; requires its own spatial/vertical subdivision");
    }
    const std::array<double, 4> seedBox = boundsOf(seed);
    p.selected = spec.portalHops ? expandSelection(root, seed, spec.portalHops) : seed;
    p.inside = spec.contextOnly ? seed : p.selected;
    p.box = spec.clip ? seedBox : boundsOf(p.selected);
    const auto& box = p.box;
    for (size_t gi = 0; gi < root.groupIds.size(); ++gi) {
        const auto& b = root.bounds[gi];
        if (!(b[3] >= box[0] - 6 && b[0] <= box[1] + 6 && b[4] >= box[2] - 6 && b[1] <= box[3] + 6)) continue;
        Bytes data;
        require(read(root.groupIds[gi], data), "group " + std::to_string(root.groupIds[gi]) + " unavailable");
        p.groups.push_back(wmoGroup(Span(data), root, static_cast<int>(gi)));
        step();
    }
    phase("groups loaded");
    if (spec.portalOwnedRooms)
        for (const Group& g : p.groups)
            for (int k = 0; k < 6; ++k)
                require(std::fabs(g.headerBounds[k] - root.bounds[g.gi][k]) <= .05,
                        "group header and ownership bounds disagree with the current root");
    if (spec.clip) p.groups = clipGroups(p.groups, box);
    p.doors = exitDoors(root, p.inside);
    p.accessDoors = spec.portalOwnedRooms ? exitDoors(root, p.inside, true) : p.doors;
    phase("before doodads");
    std::vector<Group> props = doodadGroups(root, spec.dset, read, p.doodads, nullptr, step);
    for (Group& g : props) {
        if (g.v.empty()) continue;
        double lo0 = 1e300, hi0 = -1e300, lo1 = 1e300, hi1 = -1e300;
        for (const V3& v : g.v) { lo0 = std::min(lo0, v.x); hi0 = std::max(hi0, v.x); lo1 = std::min(lo1, v.y); hi1 = std::max(hi1, v.y); }
        if (hi0 >= box[0] - 6 && lo0 <= box[1] + 6 && hi1 >= box[2] - 6 && lo1 <= box[3] + 6) p.props.push_back(std::move(g));
    }
    phase("doodads filtered");
    if (spec.terrain) {
        const TerrainSpec& t = *spec.terrain;
        Bytes obj;
        require(read(t.placementAdt, obj), "placement ADT unavailable");
        const Placement placed = placement(Span(obj), spec.root, spec.dset, t.placementId);
        std::vector<uint32_t> fids = t.adts;
        if (t.wdt) {
            Bytes wdt;
            require(read(t.wdt, wdt), "terrain WDT unavailable");
            fids.clear();
            for (const TerrainTile& tile : coveringTiles(Span(wdt), placed, box)) fids.push_back(tile.root);
        }
        std::sort(fids.begin(), fids.end());
        fids.erase(std::unique(fids.begin(), fids.end()), fids.end());
        for (uint32_t fid : fids) {
            Bytes data;
            require(read(fid, data), "terrain ADT " + std::to_string(fid) + " unavailable");
            for (Group& g : terrainGroups(Span(data), placed, box, fid)) p.terrain.push_back(std::move(g));
            step();
        }
        require(!p.terrain.empty(), "supplied terrain does not intersect the building");
    }
    return p;
}

WalkStage walkStage(const Spec& spec, const FileReader& read, const YieldFn& yield) {
    WalkStage s;
    s.prep = std::make_unique<Prepared>(prepare(spec, read, yield));
    Prepared& p = *s.prep;
    WalkSettings settings;
    settings.tileSize = spec.tileSize;
    settings.ownedFloors = settings.portalOnly = spec.portalOwnedRooms;
    if (spec.maxSpans > 0) settings.mergedMaxSpans = spec.maxSpans;
    std::vector<const Group*> walls;
    for (const Group& g : p.groups) walls.push_back(&g);
    if (spec.baselineWalk) {
        // Only the PC's "empty walk" gate reads the WMO-only walk. Entry-connected access always keeps the largest
        // component, so it never empties a non-empty walk: the kept area before access answers the gate exactly.
        WalkSettings gate = settings;
        gate.areaOnly = true;
        s.baseline = buildWalk(walls, p.inside, p.accessDoors, p.box, gate, yield);
    }
    phase("baseline walk");
    std::vector<const Group*> all = walls;
    for (const Group& g : p.props) all.push_back(&g);
    for (const Group& g : p.terrain) all.push_back(&g);
    s.enhanced = buildWalk(all, p.inside, p.accessDoors, p.box, settings, yield);
    if (spec.entryConnected) s.access = entryConnected(s.enhanced, p.accessDoors);
    phase("enhanced walk");
    return s;
}

}  // namespace wc
