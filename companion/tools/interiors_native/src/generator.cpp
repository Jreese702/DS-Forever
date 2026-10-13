#include "generator.h"

namespace wc {

GenerateResult generate(const Spec& spec, const std::string& pathLabel, const std::vector<PlacedBuilding>& placements,
                        const FileReader& read, const YieldFn& yield) {
    GenerateResult out;
    Json& r = out.report;
    r = Json::object();
    r["key"] = spec.key;
    WalkStage s = walkStage(spec, read, yield);
    Prepared& p = *s.prep;
    r["selected_groups"] = Json::array();
    for (int g : p.selected) r["selected_groups"].push(g);
    r["inside_groups"] = Json::array();
    for (int g : p.inside) r["inside_groups"].push(g);
    r["loaded_groups"] = p.groups.size();
    r["exits"] = p.doors.size();
    r["access_rule"] = spec.portalOwnedRooms ? "actual-room-boundary-portals" : "prototype-largest-plus-door-components";
    r["access_portals"] = p.accessDoors.size();
    r["inside_rule"] = spec.portalOwnedRooms ? "selected-room-floor-or-overhead" : "prototype-any-overhead-face";
    r["profile"] = !spec.profileId.empty() ? spec.profileId : spec.portalOwnedRooms ? std::string("city-room-r1") : std::string("legacy-r1");
    r["props_near_cluster"] = p.props.size();
    r["baseline_area"] = s.baseline.keptArea;
    r["with_doodads_area"] = s.enhanced.keptArea;
    r["recast_peak"] = std::max(s.baseline.recastPeak, s.enhanced.recastPeak);
    // validate_walk's status gate: an empty walk is investigated, never published
    require(!(s.enhanced.keptArea == 0 || (spec.baselineWalk && s.baseline.keptArea == 0 && !spec.terrain)),
            "empty_walk_requires_investigation");
    s.baseline = Walk();  // the baseline is only a gate; free it before rendering
    std::vector<const Group*> walls;
    for (const Group& g : p.groups) if (p.selected.count(g.gi) && !g.tri.empty()) walls.push_back(&g);
    std::vector<const Group*> interiorWmo;
    for (const Group& g : p.groups) if (!(g.flags & 8) && !g.tri.empty()) interiorWmo.push_back(&g);
    const double share = modalPlaneShare(interiorWmo);
    r["unit_modal_plane_share"] = share;
    bool dungeon = spec.dungeon;
    if (spec.dungeonFromGeometry) dungeon = share < 0.40;
    r["dungeon_rule"] = spec.dungeonFromGeometry ? std::string("geometry-modal-plane-share<0.40") : spec.dungeonRule;
    r["dungeon"] = dungeon;
    if (!spec.dungeonSource.empty()) r["dungeon_source"] = spec.dungeonSource;
    const std::vector<Furniture> furniture = furnitureRecords(p.props);
    r["furniture_candidates"] = furniture.size();
    RenderResult rendered = renderSections(walls, s.enhanced, spec.key, dungeon, furniture, kPlanPpy, kMaxStoreys, yield);
    Json cls = Json::object();
    cls["cave"] = rendered.cave;
    cls["sculpted"] = rendered.sculpted;
    r["section_classification"] = cls;
    require(!(spec.portalOwnedRooms && rendered.cave), "bounded room block has no coherent stacked-floor layout; continuous projection rejected");
    r["section_floor_votes"] = Json::array();
    for (const FloorVote& v : rendered.votes) {
        Json j = Json::object();
        j["hint"] = v.hint;
        j["measured"] = v.measured;
        j["support_area"] = v.supportArea;
        j["basis"] = v.basis;
        if (v.hasPlanned) j["planned"] = v.planned;
        r["section_floor_votes"].push(j);
    }
    r["section_floor_supplements"] = rendered.supplements.size();
    phase("rendered");
    PostReport post;
    Model model = postprocess(spec.key, std::move(rendered.levels), s.enhanced, p.doors, p.groups, post, yield);
    Json ws = Json::object();
    ws["blobs_erased"] = post.blobsErased;
    ws["erased_area"] = std::nearbyint(post.erasedArea * 1000) / 1000;
    ws["storeys_dropped"] = post.storeysDropped + static_cast<int>(post.unsupported.size());
    Json zones = Json::object();
    for (auto& kv : post.zones) zones[kv.first] = kv.second;
    ws["zones"] = zones;
    ws["storey_walk_support_area"] = Json::array();
    for (double a : post.support) ws["storey_walk_support_area"].push(a);
    ws["stair_links"] = model.stairs.size();
    ws["routes"] = post.routes;
    ws["portals_with_floor"] = post.portalsWithFloor;
    ws["height_grid_samples"] = post.gridSamples;
    r["walk_sections"] = ws;
    phase("postprocessed");
    out.files = buildingFiles(spec.key, model, pathLabel, placements, yield);
    r["storeys"] = model.levels.size();
    r["loader_contract"] = "passed";
    return out;
}

}  // namespace wc
