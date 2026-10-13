// Checked WMO / M2 / ADT / WDT readers. Ports collision_geometry.py, terrain_geometry.py and validate_walk.exit_doors
// (tools/interiors_test @ 8a8b7c28). No server data, no listfile: everything is addressed by fileDataID.
#pragma once
#include "common.h"

#include <map>
#include <set>

namespace wc {

struct ChunkMap {
    // tag -> body span inside the owning buffer. Repeated MOTV/MOCV keep the first, like chunk_map().
    std::map<std::string, Span> chunks;
    Span get(const std::string& tag) const {
        auto it = chunks.find(tag);
        return it == chunks.end() ? Span() : it->second;
    }
    bool has(const std::string& tag) const { return chunks.count(tag) != 0; }
};
// collision_geometry.chunks / chunk_map
void forEachChunk(Span data, const std::function<void(const std::string&, Span)>& f);
ChunkMap chunkMap(Span data);

struct Root {
    // Move-only: the chunk spans point into [data]'s heap buffer, which a move keeps and a copy would not.
    Root() = default;
    Root(Root&&) = default;
    Root& operator=(Root&&) = default;
    Root(const Root&) = delete;
    Root& operator=(const Root&) = delete;
    Bytes data;
    ChunkMap ch;
    std::vector<uint32_t> groupIds;
    std::vector<uint32_t> flags;
    std::vector<std::array<double, 6>> bounds;
};
// collision_geometry.wmo_root. The returned Root owns its bytes (chunk spans point into them).
Root wmoRoot(Bytes data);

enum class Source { Wmo, Doodad, Terrain, DerivedWalk };

struct Group {
    int gi = 0;
    uint32_t fid = 0;
    uint32_t flags = 0;
    std::array<double, 6> bb{};
    std::vector<V3> v;
    std::vector<std::array<int32_t, 3>> tri;
    bool hasMonr = false;
    std::vector<V3> monr;        // per vertex, when hasMonr
    std::vector<uint16_t> mopy;  // per triangle collision flags (first byte of MOPY / first u16 of MPY2)
    std::string collisionFormat;
    std::array<double, 6> headerBounds{};  // MOGP's own bounds (checked against MOGI in the room profile)
    std::string name;                      // MOGP technical room name (review only)
    Source source = Source::Wmo;
    // doodads only
    V3 placementPos;
    int recordIndex = -1;
    double scale = 1;
    // section_geometry adapters
    bool sectionWallsOnly = false;
};

// collision_geometry.wmo_group
Group wmoGroup(Span data, const Root& root, int index);

struct M2Collision { std::vector<V3> v; std::vector<std::array<int32_t, 3>> tri; };
// collision_geometry.m2_collision
M2Collision m2Collision(Span data);

struct DoodadStats { int placed = 0, solid = 0, uniqueModels = 0; };
// collision_geometry.doodad_groups. [onModel] is called after each new unique model (a yield point).
std::vector<Group> doodadGroups(const Root& root, int setIndex, const FileReader& read, DoodadStats& stats,
                                std::vector<uint32_t>* fidsOut = nullptr, const YieldFn& onModel = nullptr);
// the doodad fileDataIDs a set uses, without reading them (catalogue input digests)
std::vector<uint32_t> doodadFids(const Root& root, int setIndex);

struct Door {
    double a[2], b[2];
    double zmin, zmax;
    int insideGroup;
    double n[2] = {0, 0};  // set by walk_sections.postprocess
};
// validate_walk.exit_doors; [includeInternal] also takes portals to interior groups outside [selected] (room access)
std::vector<Door> exitDoors(const Root& root, const std::set<int>& selected, bool includeInternal = false);

// building_catalogue.components / expand_selection
std::vector<std::vector<int>> portalComponents(const Root& root);
std::set<int> expandSelection(const Root& root, std::set<int> selected, int hops);

// terrain_geometry.Placement
struct Placement {
    uint32_t fid = 0, uniqueId = 0;
    int dset = 0;
    V3 origin;
    double yaw = 0, scale = 1;
    V3 toLocal(const V3& w) const;
};
// every upright MODF record in an obj ADT (tilted / MWDS records are reported in [skipped] with a reason)
std::vector<Placement> modfPlacements(Span objAdt, std::vector<std::string>* skipped = nullptr);
// terrain_geometry.placement
Placement placement(Span objAdt, uint32_t rootFid, int dset, int64_t uniqueId = -1);
// terrain_geometry.terrain_groups
std::vector<Group> terrainGroups(Span rootAdt, const Placement& placed, const std::array<double, 4>& box, uint32_t fid,
                                 double pad = 6);
struct TerrainTile { int col, row; uint32_t root, obj0, obj1; };
// terrain_geometry.covering_tiles
std::vector<TerrainTile> coveringTiles(Span wdt, const Placement& placed, const std::array<double, 4>& box, double pad = 6);

// collision_geometry.sample_count (only used for budget reporting parity)
int64_t sampleCount(const V3& a, const V3& b, const V3& c, double step = 0.19);

}  // namespace wc
