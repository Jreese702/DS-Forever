// The building's files in IndoorBakeStore's per-building layout (models.json / places.json / interiors.json, level
// PNGs, outline PNGs, the zlib height grid), checked by output_contract.validate_model BEFORE anything reaches the store.
#pragma once
#include "json.h"
#include "pipeline.h"
#include "geom.h"
#include "zones.h"

namespace wc {

Bytes pngEncode(const Image& im);
bool pngDecode(const Bytes& data, Image& out);  // 8-bit RGBA, non-interlaced (what pngEncode writes)

struct PlacedBuilding {
    int continent = 0;
    Placement placement;
};

struct BuildingFiles {
    std::map<std::string, Bytes> files;  // leaf name -> bytes
    Json model;                          // the model record (also inside models.json / interiors.json)
};

Json modelJson(const Model& m, const std::string& path);
// pack_preview.place_record
Json placeRecord(const std::string& key, const Json& model, const Placement& placed);
// output_contract.validate_model, on the in-memory files
void validateModel(const Json& model, const std::map<std::string, Bytes>& files, const YieldFn& yield = nullptr);
// everything one building writes; placements may be empty only for host parity runs (the store needs one)
BuildingFiles buildingFiles(const std::string& key, const Model& m, const std::string& path, const std::vector<PlacedBuilding>& placements,
                            const YieldFn& yield = nullptr);
// writes each file then renames it into place (the store syncs and seals the folder)
void writeFiles(const std::string& directory, const std::map<std::string, Bytes>& files);

}  // namespace wc
