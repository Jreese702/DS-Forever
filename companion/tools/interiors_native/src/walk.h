// The measured walk: upstream Recast v1.6.0 (pinned 6dc1667f) under a bounded allocator, optional stitched tiles, and the
// component/inside/keep rules of recast_geometry.Walk + walk_access.entry_connected.
#pragma once
#include "formats.h"

#include <set>

namespace wc {

struct Cell { float x, y, z, inside; };  // global cell x/y, height, overhead-interior evidence (raster.cpp layout)

struct WalkPoly {
    std::vector<Tri> ltris;
    bool inside = false, keep = false, door = false;
    double area = 0;
};

struct Walk {
    int floors = 0;  // raw span count, the Python "floors" field
    std::vector<WalkPoly> polys;
    std::vector<std::vector<int>> adj;  // sorted, unique
    double keptArea = 0;
    std::vector<V3> keptCells;
    std::vector<int> keptCellPolys;
    size_t recastPeak = 0;
    std::vector<std::pair<const Tri*, int>> keptTris() const;
};

struct WalkSettings {
    double tileSize = 60;          // 0 = one raster over the whole box
    int maxCells = 2000000, maxSpans = 2000000, maxTriangles = 2000000;
    // the stitched walk across all tiles (recast_geometry.raster_tiled max_spans). 4M by the user's 1.0 ruling (2026-10-13): the
    // largest dungeons (cot_arm1/2, AQ, Dire Maul) need 2.0-3.5M; per-tile maxSpans and the 500k section face cap are unchanged.
    int mergedMaxSpans = 4000000;
    size_t allocatorLimit = 128u * 1024 * 1024;
    bool areaOnly = false;         // stop after the kept area (the baseline gate needs nothing else)
    // CITY_RULE room profile: selected-room walkable faces are inside evidence on their own (within 0.15 yd), and only the
    // full walk-graph components touching an actual boundary portal are kept (none = failure, never the largest layer).
    bool ownedFloors = false, portalOnly = false;
};

// recast_geometry.Walk(dll, groups, selected, doors, box, tile_size)
Walk buildWalk(const std::vector<const Group*>& groups, const std::set<int>& selected, const std::vector<Door>& doors,
               const std::array<double, 4>& box, const WalkSettings& settings, const YieldFn& onTile = nullptr);

struct EntryAccess {
    struct Removed { int component, polygons; double area, zmin, zmax; };
    std::vector<Removed> removed;
    std::vector<int> allowed;
    double keptArea = 0;
};
// walk_access.entry_connected (mutates [walk])
EntryAccess entryConnected(Walk& walk, const std::vector<Door>& doors);

constexpr double kCell = 0.2667;
// The host-adapter allocation peak of the last raster call (process-wide; generation is single-threaded).
size_t recastAllocationPeak();

}  // namespace wc
