// The device catalogue's native half: work units per placed WMO root (building_catalogue.entries, identical keys), the
// files each unit reads (for input digests from CASC content keys), and MODF placements from obj ADTs / WMO-only WDTs.
#pragma once
#include "formats.h"
#include "json.h"

namespace wc {

// building_catalogue.entries(root_fid, root, dset, tile_size); tileSize <= 0 = no work tiles.
// -> {key: {fid, gis, dset, group_fids, selection_source, parent_component, work_tile, box}}
Json catalogueUnits(const Root& root, uint32_t rootFid, int dset, double tileSize);
// building_catalogue.entries(..., room_units=True, room_merge_span=span) (CITY_RULE.md): bounded portal room blocks,
// keys FID_dSET_rGROUPFID / FID_dSET_b<sha16>, with oversized / degenerate_ownership flags and the ownership box.
Json catalogueRooms(const Root& root, uint32_t rootFid, int dset, double span, double height = 80);

// every file generate() reads for a unit of [root] under its profile: root, the groups overlapping the work box plus a six-yard
// halo (the seed box when [clip], else the portal-expanded selection's), the doodad set's models (the PC reads the whole set,
// then keeps the props near the box). Sorted, unique.
std::vector<uint32_t> unitInputs(const Root& root, uint32_t rootFid, const std::vector<int>& seed, int dset, int portalHops,
                                 bool clip);

// MODF records of an obj ADT, or of a WMO-only map's WDT (MPHD flag 1). Tilted / MWDS records are reported, not used.
Json placementsJson(Span file, bool wdt);
// the obj0 fileDataIDs of every present tile of a WDT (MAIN/MAID), or empty for a WMO-only map
std::vector<uint32_t> wdtObjectTiles(Span wdt, bool& wmoOnly);

}  // namespace wc
