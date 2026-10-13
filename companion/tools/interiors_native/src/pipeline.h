// One building, start to finish: validate_walk.main's per-key body (selection, groups, clipping, doodads, terrain,
// exits, both walks, entry access) followed by the section renderer and walk_sections.postprocess.
#pragma once
#include "formats.h"
#include "walk.h"

#include <memory>
#include <optional>

namespace wc {

struct TerrainSpec {
    uint32_t placementAdt = 0;   // obj ADT with the MODF record
    int64_t placementId = -1;    // MODF unique id, -1 = the only matching record
    uint32_t wdt = 0;            // 0 = use [adts]
    std::vector<uint32_t> adts;  // explicit root ADTs
};

struct Spec {
    std::string key;
    uint32_t root = 0;
    std::vector<int> seed;      // the work unit's groups (catalogue 'gis')
    int dset = 0;
    int portalHops = 1;
    bool contextOnly = false;   // expanded groups are collision/section context only
    bool clip = false;          // clip to the seed box plus a six-yard halo
    bool entryConnected = true;
    bool baselineWalk = true;   // the WMO-only walk; an empty one without terrain fails the building (PC gate)
    double tileSize = 60;
    bool dungeon = false;       // the PC renderer's "'/dungeon/' in path" (cave classification)
    // The phone has no listfile: when set, [dungeon] is replaced by the geometric rule (modal-plane share of the unit's
    // interior WMO faces < 0.40), which agrees with the PC's path rule on 364/424 placed interior roots (85.8%).
    bool dungeonFromGeometry = false;
    std::string dungeonRule = "pc-listfile-path";  // where an explicit [dungeon] came from (reported as dungeon_rule)
    std::string dungeonSource;                      // the catalogue's provenance verdict source (reported as dungeon_source)
    std::optional<TerrainSpec> terrain;
    // CITY_RULE.md (reviewed room profile, revision 1): portal-owned room blocks. Requires contextOnly; the unit's own group
    // fileDataIDs (when given) must still match the root; oversized / degenerate blocks fail explicitly.
    bool portalOwnedRooms = false;
    std::string profileId;  // "legacy-r1" / "city-room-r1" when the caller declares one; its settings are checked
    std::vector<uint32_t> groupFids;
    double roomSpan = 120, roomHeight = 80;
    int maxSpans = 0;  // 0 = WalkSettings' default (4,000,000); host measurement override only
};

struct Prepared {
    Root root;
    std::set<int> selected, inside;
    std::array<double, 4> box{};
    std::vector<Group> groups;   // nearby WMO groups (clipped when requested)
    std::vector<Group> props;    // placed M2 collision near the box
    std::vector<Group> terrain;
    std::vector<Door> doors;        // red-exit candidates (validate_walk.exit_doors)
    std::vector<Door> accessDoors;  // the walk's access portals (room profile: internal boundary portals too)
    DoodadStats doodads;
    std::map<uint32_t, uint64_t> reads;  // fid -> bytes read
};

struct WalkStage {
    std::unique_ptr<Prepared> prep;
    Walk baseline, enhanced;
    EntryAccess baselineAccess, access;
};

Prepared prepare(const Spec& spec, const FileReader& read, const YieldFn& yield);
WalkStage walkStage(const Spec& spec, const FileReader& read, const YieldFn& yield);

}  // namespace wc
